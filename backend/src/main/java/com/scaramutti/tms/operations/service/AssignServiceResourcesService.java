package com.scaramutti.tms.operations.service;

import com.scaramutti.tms.auth.security.CurrentUser;
import com.scaramutti.tms.operations.OperationsError;
import com.scaramutti.tms.operations.dto.ServiceDetailResponse;
import com.scaramutti.tms.operations.dto.ServiceResourceConflictResponse;
import com.scaramutti.tms.operations.model.ServiceAuditChangeType;
import com.scaramutti.tms.operations.model.ServiceEventType;
import com.scaramutti.tms.operations.model.ServiceResourceKind;
import com.scaramutti.tms.operations.model.ServiceStatus;
import com.scaramutti.tms.operations.service.cmd.AssignServiceResourcesCommand;
import com.scaramutti.tms.shared.entity.Driver;
import com.scaramutti.tms.shared.entity.Service;
import com.scaramutti.tms.shared.entity.ServiceAuditLog;
import com.scaramutti.tms.shared.entity.ServiceEvent;
import com.scaramutti.tms.shared.entity.Tractor;
import com.scaramutti.tms.shared.entity.Trailer;
import com.scaramutti.tms.shared.exception.CommonError;
import com.scaramutti.tms.shared.repository.DriverRepository;
import com.scaramutti.tms.shared.repository.ServiceAuditLogRepository;
import com.scaramutti.tms.shared.repository.ServiceEventRepository;
import com.scaramutti.tms.shared.repository.ServiceRepository;
import com.scaramutti.tms.shared.repository.ServiceResourceConflictRepository;
import com.scaramutti.tms.shared.repository.ServiceResourceConflictRepository.ServiceResourceConflictRow;
import com.scaramutti.tms.shared.repository.TractorRepository;
import com.scaramutti.tms.shared.repository.TrailerRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.util.ArrayList;
import java.util.Locale;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Asignacion de los recursos principales de un viaje: conductor, tracto y, si va, carreta.
 *
 * <p>Es la operacion que saca al viaje de "pendiente de asignacion". El estado nuevo NO se pide:
 * es efecto de asignar, y por eso este endpoint no comparte camino ni codigos con las
 * transiciones de estado.
 *
 * <p>Orden del flow, y cada paso esta donde esta por una razon: identificar al usuario, tomar la
 * fila del viaje bajo lock (que ademas pone el tope de espera para todo lo que sigue), rechazar
 * el estado que no admite la accion, resolver los tres recursos (un id invalido se rechaza ANTES
 * de tomar ningun lock), serializar esos recursos, consultar el conflicto, y recien ahi escribir.
 */
@ApplicationScoped
public class AssignServiceResourcesService {

    /** Unico estado desde el que se asigna: el viaje todavia no tiene recursos. */
    private static final ServiceStatus ASSIGNABLE_STATUS = ServiceStatus.PENDING_ASSIGNMENT;

    /** Los dos terminales inmutables, que llevan codigo propio (el mismo que rechaza la edicion). */
    private static final Set<ServiceStatus> IMMUTABLE_STATUSES =
        EnumSet.of(ServiceStatus.CANCELLED, ServiceStatus.DELETED);

    private static final String ASSIGNMENT_DESCRIPTION = "Asignación de recursos";

    private static final String FORCED_ASSIGNMENT_DESCRIPTION =
        "Asignación de recursos forzando un conflicto de disponibilidad";

    /** Claves de los miembros de extension del error de conflicto, como las declara el contrato. */
    private static final String FORCIBLE_FIELD = "forcible";
    private static final String CONFLICTS_FIELD = "conflicts";

    @Inject ServiceRepository serviceRepository;
    @Inject ServiceEventRepository serviceEventRepository;
    @Inject ServiceAuditLogRepository serviceAuditLogRepository;
    @Inject ServiceResourceConflictRepository serviceResourceConflictRepository;
    @Inject DriverRepository driverRepository;
    @Inject TractorRepository tractorRepository;
    @Inject TrailerRepository trailerRepository;
    @Inject CurrentUser currentUser;
    @Inject GetServiceService getServiceService;
    @Inject ServiceRowLock serviceRowLock;

    /**
     * NO se exige poder ver precios, a diferencia del alta y de la edicion: el cuerpo de esta
     * operacion no lleva importes, y el despacho —que nunca los ve— es justamente quien asigna.
     * Los precios se le omiten de la respuesta por el camino de siempre.
     *
     * <p>La traduccion del conflicto de lock envuelve TODA la operacion por el mismo motivo que en
     * la edicion: desde que el tope de espera esta puesto, cualquier sentencia posterior puede
     * rendirse, incluidas las que arman el detalle de la respuesta. Con una excepcion conocida:
     * el COMMIT ocurre despues de que este metodo retorna, o sea afuera. Hoy no importa —ninguna
     * restriccion del esquema es DEFERRABLE, asi que el chequeo de claves corre dentro del
     * volcado, que si esta adentro— pero la primera que lo sea reabre ese hueco.
     */
    @Transactional
    public ServiceDetailResponse assignServiceResources(
            AssignServiceResourcesCommand assignServiceResourcesCommand) {
        Integer userId = currentUser.requireId();

        return serviceRowLock.runTranslatingLockConflicts(
            () -> assignLockedServiceResources(assignServiceResourcesCommand, userId),
            assignServiceResourcesCommand.serviceId());
    }

    private ServiceDetailResponse assignLockedServiceResources(
            AssignServiceResourcesCommand command, Integer userId) {
        Service service = serviceRowLock.findByIdForUpdate(command.serviceId());
        requireAssignableStatus(service);

        // Los recursos se resuelven ANTES de tomar sus locks: un id inexistente o dado de baja
        // termina en 400, y tomar locks por una request que igual se rechaza haria esperar de
        // gusto a las asignaciones que si son validas.
        AssignedResources resources = resolveResources(command);

        // La consulta toma ella misma el lock de los recursos que mira, y por eso va DESPUES del
        // lock de la fila: ese es el que puso el tope de espera, y al reves la espera por el lock
        // de un recurso no tendria techo.
        List<ServiceResourceConflictResponse> conflicts = findConflicts(command, resources);
        if (!conflicts.isEmpty() && !command.force()) {
            throw conflictException(conflicts);
        }

        // Los valores ANTERIORES se leen antes de pisarlos. Hoy los tres recursos son siempre
        // null —la guarda de estado lo garantiza—, pero la auditoria es append-only y nadie la
        // corrige despues: un viaje que el cutover importe ya con conductor, o un futuro
        // endpoint de reasignar, dejarian el rastro afirmando que no habia nada donde si habia.
        PreviousValues previous = new PreviousValues(ServiceStatus.valueOf(service.status),
            service.driverId, service.tractorId, service.trailerId);
        applyAssignment(service, command, userId);
        writeAssignmentAuditLogs(service, resources, previous, conflicts, command, userId);
        writeAssignmentEvent(service, resources, conflicts, command, userId);
        // El gancho que mueve la version corre AL VOLCAR: sin este flush el detalle podria armarse
        // con la version vieja y la respuesta saldria con un ETag que la base ya no tiene.
        //
        // Medido con una mutacion: quitandolo, hoy NINGUN test se pone rojo, porque la primera
        // consulta que hace el armado del detalle dispara el volcado automatico igual. Se deja
        // igual, y no es terquedad: ese volcado automatico solo ocurre si Hibernate cree que la
        // consulta toca las tablas que estan sucias, y esa decision es una heuristica suya, no
        // algo que el contrato prometa. El dia que el armado del detalle cambie de consultas, el
        // ETag empezaria a salir viejo sin que nada falle y el proximo If-Match del cliente
        // contestaria 412 para siempre. Una linea es barata; ese diagnostico no.
        serviceRepository.flush();

        return getServiceService.getService(service.id);
    }

    // ---------- Precondiciones -------------------------------------------------

    /**
     * Los dos terminales inmutables se contestan con SU codigo y no con el del estado que no
     * admite la accion: es el mismo rechazo que da la edicion sobre el mismo viaje, y contestar
     * codigos distintos por la misma razon segun el endpoint deja al cliente sin poder confiar en
     * ninguno de los dos.
     */
    private void requireAssignableStatus(Service service) {
        ServiceStatus status = ServiceStatus.valueOf(service.status);
        if (IMMUTABLE_STATUSES.contains(status)) {
            throw OperationsError.SERVICE_NOT_EDITABLE.toException();
        }
        if (status != ASSIGNABLE_STATUS) {
            throw OperationsError.ACTION_NOT_ALLOWED_FOR_STATUS.toException();
        }
    }

    // ---------- Recursos -------------------------------------------------------

    /** Los tres recursos ya resueltos: su id y la etiqueta con la que los lee una persona. */
    private record AssignedResources(
        Integer driverId, String driverFullName,
        Integer tractorId, String tractorPlate,
        Integer trailerId, String trailerPlate) {}

    /**
     * Existencia y ALTA de los tres recursos. La disponibilidad del catalogo
     * ({@code available} / {@code maintenance} / {@code not_available}) NO se valida: mandar una
     * unidad en mantenimiento es una decision operativa, y el catalogo de estados existe para
     * ordenar la lista de la pantalla, no para prohibir. Lo que si se rechaza es un recurso dado
     * de baja, que es lo mismo que hace el alta con el cliente, el tipo de carga y la moneda.
     */
    private AssignedResources resolveResources(AssignServiceResourcesCommand command) {
        Driver driver = driverRepository.findById(command.driverId());
        requireActive(driver != null && Boolean.TRUE.equals(driver.isActive),
            ServiceResourceKind.DRIVER);
        String driverName = driverRepository.findFullNameById(command.driverId());

        Tractor tractor = tractorRepository.findById(command.tractorId());
        requireActive(tractor != null && Boolean.TRUE.equals(tractor.isActive),
            ServiceResourceKind.TRACTOR);

        String trailerPlate = null;
        if (command.trailerId() != null) {
            Trailer trailer = trailerRepository.findById(command.trailerId());
            requireActive(trailer != null && Boolean.TRUE.equals(trailer.isActive),
                ServiceResourceKind.TRAILER);
            trailerPlate = trailer.plate;
        }

        return new AssignedResources(
            command.driverId(), driverName,
            command.tractorId(), tractor.plate,
            command.trailerId(), trailerPlate);
    }

    /**
     * El mensaje concuerda por genero, como el del conflicto: recibe el TIPO y no una etiqueta ya
     * armada, para que la concordancia se decida en un solo lugar. Con la etiqueta suelta, "La
     * carreta indicado no existe o esta inactivo" se cuela sin que nada falle.
     */
    private void requireActive(boolean usable, ServiceResourceKind kind) {
        if (!usable) {
            throw CommonError.VALIDATION_FAILED.toException(kind == ServiceResourceKind.TRAILER
                ? label(kind) + " indicada no existe o está inactiva"
                : label(kind) + " indicado no existe o está inactivo");
        }
    }

    // ---------- Conflicto ------------------------------------------------------

    /**
     * Los recursos pedidos que ya retiene otro viaje activo, con su etiqueta pegada.
     *
     * <p>El nombre no sale de la consulta: los conflictos solo pueden ser sobre los tres recursos
     * que se pidieron, y esos ya estan resueltos. Traerlo de la consulta obligaria a colgarle
     * tres uniones externas para volver a leer lo mismo.
     */
    private List<ServiceResourceConflictResponse> findConflicts(
            AssignServiceResourcesCommand command, AssignedResources resources) {
        Map<ServiceResourceKind, String> namesByKind = new LinkedHashMap<>();
        namesByKind.put(ServiceResourceKind.DRIVER, resources.driverFullName());
        namesByKind.put(ServiceResourceKind.TRACTOR, resources.tractorPlate());
        namesByKind.put(ServiceResourceKind.TRAILER, resources.trailerPlate());

        List<ServiceResourceConflictRow> rows = serviceResourceConflictRepository.findActiveConflicts(
            command.serviceId(), command.driverId(), command.tractorId(), command.trailerId());

        List<ServiceResourceConflictResponse> conflicts = new ArrayList<>();
        for (ServiceResourceConflictRow row : rows) {
            // Se aplastan ACA, al armar el DTO, y no solo en el texto del error: los dos valores
            // salen de la base y las dos salidas de la misma respuesta —el mensaje y la lista—
            // no pueden discrepar sobre si el salto de linea se ve. La pantalla que junte la
            // lista en un bloque de texto tendria el mismo agujero que la bitacora cierra.
            conflicts.add(new ServiceResourceConflictResponse(
                row.resource(), flat(namesByKind.get(row.resource())),
                flat(row.serviceCode()), row.serviceStatus()));
        }
        return conflicts;
    }

    /**
     * El 409 forzable, con los dos miembros de extension que la interfaz necesita para ofrecer
     * "Forzar asignacion". El texto nombra al primero y cuenta el resto: el detalle completo ya
     * viaja en la lista, y un mensaje que enumere cinco recursos no se lee.
     */
    private RuntimeException conflictException(List<ServiceResourceConflictResponse> conflicts) {
        return OperationsError.RESOURCE_CONFLICT.toException(
            conflictDetail(conflicts),
            Map.of(FORCIBLE_FIELD, true, CONFLICTS_FIELD, conflicts));
    }

    private String conflictDetail(List<ServiceResourceConflictResponse> conflicts) {
        ServiceResourceConflictResponse first = conflicts.get(0);
        // Los valores ya vienen aplastados desde findConflicts: aca solo se arma la frase.
        String detail = label(first.resource()) + " " + first.resourceName()
            + " ya está " + assignedParticiple(first.resource()) + " al servicio "
            + first.serviceCode() + " (" + statusLabel(first.serviceStatus()) + ").";
        // Se cuentan RECURSOS distintos, no filas: el mismo conductor retenido por dos viajes
        // son dos filas y UN solo recurso en conflicto, y decir "hay 1 recurso mas" ahi seria
        // falso. Es alcanzable con este endpoint solo, forzando la segunda asignacion.
        long others = conflicts.stream()
            .map(ServiceResourceConflictResponse::resource).distinct().count() - 1;
        if (others > 0) {
            detail += others == 1
                ? " Hay 1 recurso más en conflicto."
                : " Hay " + others + " recursos más en conflicto.";
        }
        return detail;
    }

    private String label(ServiceResourceKind kind) {
        return switch (kind) {
            case DRIVER -> "El conductor";
            case TRACTOR -> "El tracto";
            case TRAILER -> "La carreta";
        };
    }

    /**
     * El participio concuerda con el genero del recurso. Parece un detalle de estilo y no lo es:
     * el mensaje lo lee la persona que decide si pisa un control de disponibilidad, y "La carreta
     * ABC123 ya está asignado" delata que el texto se arma pegando pedazos, que es justo lo que
     * hace dudar de si el dato tambien esta armado a los pedazos.
     */
    private String assignedParticiple(ServiceResourceKind kind) {
        return kind == ServiceResourceKind.TRAILER ? "asignada" : "asignado";
    }

    /**
     * El estado, como lo nombra el negocio. Solo los dos que pueden retener un recurso: los otros
     * cuatro no llegan nunca a este mensaje, y ponerlos seria prometer un texto para un caso
     * imposible.
     */
    private String statusLabel(ServiceStatus status) {
        return switch (status) {
            case PENDING_START -> "pendiente de inicio";
            case IN_PROGRESS -> "en ruta";
            default -> status.name();
        };
    }

    // ---------- Escritura ------------------------------------------------------

    private void applyAssignment(Service service, AssignServiceResourcesCommand command, Integer userId) {
        service.driverId = command.driverId();
        service.tractorId = command.tractorId();
        service.trailerId = command.trailerId();
        service.status = ServiceStatus.PENDING_START.name();
        service.updatedBy = userId;
        // La version la mueve el gancho de la entity, que es la unica fuente para todos los
        // endpoints que escriben sobre esta fila.
    }

    /**
     * Una fila por recurso asignado, mas una por el cambio de estado. La de la carreta NO se
     * escribe cuando no hay carreta: una fila que dice que un campo paso de null a null afirma un
     * cambio que no ocurrio.
     *
     * <p>El cambio de estado lleva el tipo ASSIGNMENT y no el de transicion, porque no se pidio:
     * es efecto de esta operacion. El tipo de transicion queda para el endpoint que la pide.
     *
     * <p>Los valores son los IDS y no las etiquetas: la auditoria existe para reconstruir el
     * estado, y el valor real de la columna es el id. Un nombre de conductor ni siquiera es unico.
     * La lectura humana ya la cubre la bitacora, que escribe nombre y placas.
     */
    private void writeAssignmentAuditLogs(Service service, AssignedResources resources,
            PreviousValues previous, List<ServiceResourceConflictResponse> conflicts,
            AssignServiceResourcesCommand command, Integer userId) {
        String description = auditDescription(conflicts, command);
        writeAuditLog(service, "driver", "Conductor", asText(previous.driverId()),
            String.valueOf(resources.driverId()), description, userId);
        writeAuditLog(service, "tractor", "Tracto", asText(previous.tractorId()),
            String.valueOf(resources.tractorId()), description, userId);
        if (resources.trailerId() != null) {
            writeAuditLog(service, "trailer", "Carreta", asText(previous.trailerId()),
                String.valueOf(resources.trailerId()), description, userId);
        }
        writeAuditLog(service, "status", "Estado", previous.status().name(),
            ServiceStatus.PENDING_START.name(), description, userId);
    }

    /** Lo que el viaje tenia ANTES de la asignacion, para que la auditoria diga de que a que. */
    private record PreviousValues(
        ServiceStatus status, Integer driverId, Integer tractorId, Integer trailerId) {}

    private String asText(Integer value) {
        return value == null ? null : String.valueOf(value);
    }

    /**
     * OJO con {@code description}: guarda el texto del usuario CRUDO, sin aplastar sus saltos de
     * linea, a diferencia de la bitacora. Es deliberado —la auditoria es el registro
     * reconstruible y ahi el texto exacto es el dato—, pero significa que quien alguna vez
     * MUESTRE esta columna es el responsable de escaparla. Hoy no la expone ningun endpoint.
     */
    private void writeAuditLog(Service service, String fieldName, String fieldLabel,
            String oldValue, String newValue, String description, Integer userId) {
        ServiceAuditLog auditLog = new ServiceAuditLog();
        auditLog.serviceId = service.id;
        auditLog.changedBy = userId;
        auditLog.changeType = ServiceAuditChangeType.ASSIGNMENT.name();
        auditLog.fieldName = fieldName;
        auditLog.fieldLabel = fieldLabel;
        auditLog.oldValue = oldValue;
        auditLog.newValue = newValue;
        auditLog.description = description;
        serviceAuditLogRepository.persist(auditLog);
    }

    /**
     * La columna es NOT NULL y la nota del usuario es OPCIONAL, asi que siempre hay un texto
     * base. La nota se AGREGA, no reemplaza: si la pisara, forzar una asignacion sin dejar
     * constancia seria tan facil como escribir cualquier cosa en la nota.
     */
    private String auditDescription(List<ServiceResourceConflictResponse> conflicts,
            AssignServiceResourcesCommand command) {
        String base = wasForced(conflicts, command)
            ? FORCED_ASSIGNMENT_DESCRIPTION
            : ASSIGNMENT_DESCRIPTION;
        return command.note() == null ? base : base + ": " + command.note();
    }

    /**
     * TODO valor que entra a una linea de la bitacora se aplasta, no solo el texto del usuario.
     *
     * <p>La bitacora tiene UNA linea por dato, asi que cualquier salto de linea que se cuele
     * planta una linea FALSA con el formato del servidor, en un rastro que despues nadie puede
     * editar ni borrar. El texto del usuario es el camino obvio, pero no es el unico: el nombre
     * del conductor y las placas salen de tablas del sistema anterior, que SI tiene formularios
     * para escribirlas, y el codigo del otro viaje sale de una columna. Aplastar solo la nota
     * seria cerrar la puerta y dejar la ventana abierta.
     */
    private String flat(String value) {
        // El nombre del conductor sale de un JOIN contra los trabajadores del sistema anterior y
        // puede venir vacio si esa fila no esta: sin esto, la bitacora guardaria literalmente
        // "Conductor: null" en un rastro que despues nadie puede corregir.
        return ServiceLogText.display(value);
    }

    /**
     * Se forzo de verdad cuando habia conflicto Y se pidio forzar. Mandar {@code force} sin
     * conflicto no ensucia el rastro con una excepcion que nunca ocurrio.
     */
    private boolean wasForced(List<ServiceResourceConflictResponse> conflicts,
            AssignServiceResourcesCommand command) {
        return command.force() && !conflicts.isEmpty();
    }

    /**
     * UNA entrada de bitacora por asignacion, no una por recurso: la bitacora es la linea de
     * tiempo de las ACCIONES del viaje. El detalle por campo ya vive en la auditoria.
     *
     * <p>Escribe nombre y placas, no ids: la lee una persona, y a este detalle entra el despacho.
     * No hay riesgo de filtrar importes porque aca no aparece ninguno.
     */
    private void writeAssignmentEvent(Service service, AssignedResources resources,
            List<ServiceResourceConflictResponse> conflicts,
            AssignServiceResourcesCommand command, Integer userId) {
        List<String> lines = new ArrayList<>();
        lines.add("Conductor: " + flat(resources.driverFullName()));
        lines.add("Tracto: " + flat(resources.tractorPlate()));
        if (resources.trailerId() != null) {
            lines.add("Carreta: " + flat(resources.trailerPlate()));
        }
        if (wasForced(conflicts, command)) {
            for (ServiceResourceConflictResponse conflict : conflicts) {
                lines.add("Asignación forzada: "
                    + label(conflict.resource()).toLowerCase(Locale.ROOT)
                    + " " + flat(conflict.resourceName())
                    + " ya estaba " + assignedParticiple(conflict.resource()) + " al servicio "
                    + flat(conflict.serviceCode())
                    + " (" + statusLabel(conflict.serviceStatus()) + ")");
            }
        }
        if (command.note() != null) {
            lines.add("Nota: " + flat(command.note()));
        }

        ServiceEvent event = new ServiceEvent();
        event.serviceId = service.id;
        event.eventType = ServiceEventType.ASSIGNMENT.name();
        event.note = String.join("\n", lines);
        event.createdBy = userId;
        serviceEventRepository.persist(event);
    }
}
