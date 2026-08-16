package com.scaramutti.tms.operations.service;

import com.scaramutti.tms.auth.security.CurrentUser;
import com.scaramutti.tms.operations.OperationsError;
import com.scaramutti.tms.operations.dto.ServiceDetailResponse;
import com.scaramutti.tms.operations.dto.ServiceResourceConflictResponse;
import com.scaramutti.tms.operations.model.ServiceAuditChangeType;
import com.scaramutti.tms.operations.model.ServiceEventType;
import com.scaramutti.tms.operations.model.ServiceResourceKind;
import com.scaramutti.tms.operations.model.ServiceStatus;
import com.scaramutti.tms.operations.service.cmd.AddServiceResourcesCommand;
import com.scaramutti.tms.shared.entity.Driver;
import com.scaramutti.tms.shared.entity.Service;
import com.scaramutti.tms.shared.entity.ServiceAssignment;
import com.scaramutti.tms.shared.entity.ServiceAuditLog;
import com.scaramutti.tms.shared.entity.ServiceEvent;
import com.scaramutti.tms.shared.entity.Tractor;
import com.scaramutti.tms.shared.entity.Trailer;
import com.scaramutti.tms.shared.repository.DriverRepository;
import com.scaramutti.tms.shared.repository.ServiceAssignmentRepository;
import com.scaramutti.tms.shared.repository.ServiceAuditLogRepository;
import com.scaramutti.tms.shared.repository.ServiceEventRepository;
import com.scaramutti.tms.shared.repository.ServiceRepository;
import com.scaramutti.tms.shared.repository.TractorRepository;
import com.scaramutti.tms.shared.repository.TrailerRepository;
import com.scaramutti.tms.shared.util.DateUtils;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Recursos de REFUERZO de un viaje que ya esta en ruta: el relevo de un conductor que agoto su
 * descanso reglamentario, la unidad de apoyo que sale a un varado.
 *
 * <p>No reemplazan a los principales: se SUMAN. El viaje se queda en ruta y sus conductor, tracto
 * y carreta originales no se tocan, asi que esta operacion no es una reasignacion ni una
 * transicion de estado.
 *
 * <p>Orden del flow, calcado del de la asignacion y con cada paso donde esta por una razon:
 * identificar al usuario, tomar la fila del viaje bajo lock (que ademas pone el tope de espera
 * para todo lo que sigue), rechazar el estado que no admite la accion, resolver los recursos
 * presentes (un id invalido se rechaza ANTES de tomar ningun lock), rechazar el duplicado del
 * MISMO viaje, consultar el conflicto con OTROS viajes, y recien ahi escribir.
 *
 * <p><b>Los dos rechazos de recurso son distintos y el ORDEN entre ellos es parte de la regla.</b>
 * El duplicado del propio viaje es duro y va primero; el conflicto con otro viaje es forzable y va
 * despues. Al reves, un {@code force} colaria el duplicado que el primero existe para rechazar.
 *
 * <p><b>Presupuesto de esperas de lock: 4, el mismo techo que la asignacion, y no lo mueve.</b>
 * Una espera por la fila del viaje mas una por cada recurso PRESENTE (a lo sumo tres, porque el
 * cuerpo admite uno de cada tipo). El caso tipico —un relevo de conductor— usa dos. El chequeo del
 * duplicado propio NO suma ninguna: no toma locks, y no le hacen falta, porque la fila del viaje
 * ya tomada serializa a dos refuerzos concurrentes sobre el mismo viaje.
 *
 * <p>Salvedad conocida: los chequeos de integridad referencial toman locks de clave compartida
 * bajo el mismo tope y quedan FUERA de esa cuenta, por la misma decision ya escrita en
 * {@code ServiceRowLock}: los absorbe la holgura entre el total contado y la espera del pool. Acá
 * son hasta cinco por la fila de refuerzo (viaje, conductor, tracto, carreta y usuario, aunque
 * PostgreSQL solo verifica las columnas NO nulas: el relevo tipico son tres) mas dos por cada fila
 * de rastro. El UPDATE del viaje casi no aporta: solo mueve {@code updated_by} y
 * {@code updated_at}, y se revalidan unicamente las claves cuyas columnas cambiaron.
 */
@ApplicationScoped
public class AddServiceResourcesService {

    /** Unico estado desde el que se suman refuerzos: el viaje ya arranco. */
    private static final ServiceStatus REINFORCEABLE_STATUS = ServiceStatus.IN_PROGRESS;

    private static final String REINFORCEMENT_DESCRIPTION = "Refuerzo de recursos";

    private static final String FORCED_REINFORCEMENT_DESCRIPTION =
        "Refuerzo de recursos forzando un conflicto de disponibilidad";

    @Inject ServiceRepository serviceRepository;
    @Inject ServiceAssignmentRepository serviceAssignmentRepository;
    @Inject ServiceEventRepository serviceEventRepository;
    @Inject ServiceAuditLogRepository serviceAuditLogRepository;
    @Inject ServiceResourceConflicts serviceResourceConflicts;
    @Inject DriverRepository driverRepository;
    @Inject TractorRepository tractorRepository;
    @Inject TrailerRepository trailerRepository;
    @Inject CurrentUser currentUser;
    @Inject GetServiceService getServiceService;
    @Inject ServiceRowLock serviceRowLock;

    /**
     * NO se exige poder ver precios, por lo mismo que la asignacion: el cuerpo no lleva importes y
     * el despacho —que nunca los ve— es justamente quien manda el refuerzo a la ruta.
     *
     * <p>La traduccion del conflicto de lock envuelve TODA la operacion, con la misma salvedad
     * conocida que la asignacion: el COMMIT ocurre despues de que este metodo retorna.
     */
    @Transactional
    public ServiceDetailResponse addServiceResources(
            AddServiceResourcesCommand addServiceResourcesCommand) {
        Integer userId = currentUser.requireId();

        return serviceRowLock.runTranslatingLockConflicts(
            () -> addLockedServiceResources(addServiceResourcesCommand, userId),
            addServiceResourcesCommand.serviceId());
    }

    private ServiceDetailResponse addLockedServiceResources(
            AddServiceResourcesCommand command, Integer userId) {
        Service service = serviceRowLock.findByIdForUpdate(command.serviceId());
        requireReinforceableStatus(service);

        // Los recursos se resuelven ANTES de tomar sus locks, igual que en la asignacion: un id
        // inexistente o dado de baja termina en 400, y tomar locks por un pedido que igual se
        // rechaza haria esperar de gusto a los refuerzos que si son validos.
        AddedResources resources = resolveResources(command);

        // El duplicado del PROPIO viaje va primero y no toma locks. Ver el javadoc de la clase: el
        // orden es la regla, no una preferencia de escritura.
        rejectResourcesAlreadyInThisService(command, resources);

        // La consulta toma ella misma el lock de los recursos que mira, y por eso va DESPUES del
        // lock de la fila: ese es el que puso el tope de espera.
        List<ServiceResourceConflictResponse> conflicts = serviceResourceConflicts.find(
            command.serviceId(), command.driverId(), command.tractorId(), command.trailerId(),
            resources.driverFullName(), resources.tractorPlate(), resources.trailerPlate());
        if (!conflicts.isEmpty() && !command.force()) {
            throw serviceResourceConflicts.asForcibleConflict(conflicts);
        }

        writeAssignmentRow(service, command, userId);
        writeReinforcementAuditLogs(service, resources, conflicts, command, userId);
        writeReinforcementEvent(service, resources, conflicts, command, userId);
        touchServiceVersion(service, userId);
        // Sin este flush pasan DOS cosas, no una: el detalle podria armarse con la version vieja
        // —y la respuesta saldria con un ETag que la base ya no tiene— y ademas la consulta de
        // refuerzos, que es NATIVA, no veria la fila recien insertada, asi que el 200 contestaria
        // sin el refuerzo que acaba de crear. Quien concluya que la version se resuelve por otro
        // lado y lo saque, se lleva puesta la respuesta del propio endpoint.
        serviceRepository.flush();

        return getServiceService.getService(service.id);
    }

    // ---------- Precondiciones -------------------------------------------------

    /**
     * Los dos terminales inmutables se contestan con SU codigo y no con el del estado que no admite
     * la accion, igual que en la asignacion: es el mismo rechazo que da la edicion sobre el mismo
     * viaje, y contestar codigos distintos por la misma razon segun el endpoint deja al cliente sin
     * poder confiar en ninguno.
     */
    private void requireReinforceableStatus(Service service) {
        ServiceStatus status = ServiceStatus.valueOf(service.status);
        ServiceStatusGuards.rejectIfImmutable(status);
        if (status != REINFORCEABLE_STATUS) {
            throw OperationsError.ACTION_NOT_ALLOWED_FOR_STATUS.toException();
        }
    }

    // ---------- Recursos -------------------------------------------------------

    /**
     * Los recursos PRESENTES ya resueltos: su id y la etiqueta con la que los lee una persona. Los
     * ausentes quedan en null de punta a punta, y esa ausencia es la que decide que ramas se
     * consultan, que filas de auditoria se escriben y que lineas lleva la bitacora.
     */
    private record AddedResources(
        Integer driverId, String driverFullName,
        Integer tractorId, String tractorPlate,
        Integer trailerId, String trailerPlate) {}

    /**
     * Existencia y ALTA de los recursos que vinieron. Se revalida igual que en la asignacion, y no
     * como en la reapertura: sumar un refuerzo ELIGE un recurso —y elegir uno dado de baja es un
     * error que se corrige eligiendo otro—, mientras que reabrir RESTAURA una decision ya tomada.
     *
     * <p>La disponibilidad del catalogo ({@code maintenance}, {@code not_available}) NO se valida,
     * por lo mismo que alla: mandar una unidad en mantenimiento es una decision operativa, y mas
     * todavia en un refuerzo, que por definicion sale a resolver un imprevisto con lo que hay.
     */
    private AddedResources resolveResources(AddServiceResourcesCommand command) {
        String driverName = null;
        if (command.driverId() != null) {
            Driver driver = driverRepository.findById(command.driverId());
            serviceResourceConflicts.requireActiveResource(driver != null && Boolean.TRUE.equals(driver.isActive),
                ServiceResourceKind.DRIVER);
            driverName = driverRepository.findFullNameById(command.driverId());
        }

        String tractorPlate = null;
        if (command.tractorId() != null) {
            Tractor tractor = tractorRepository.findById(command.tractorId());
            serviceResourceConflicts.requireActiveResource(tractor != null && Boolean.TRUE.equals(tractor.isActive),
                ServiceResourceKind.TRACTOR);
            tractorPlate = tractor.plate;
        }

        String trailerPlate = null;
        if (command.trailerId() != null) {
            Trailer trailer = trailerRepository.findById(command.trailerId());
            serviceResourceConflicts.requireActiveResource(trailer != null && Boolean.TRUE.equals(trailer.isActive),
                ServiceResourceKind.TRAILER);
            trailerPlate = trailer.plate;
        }

        return new AddedResources(
            command.driverId(), driverName,
            command.tractorId(), tractorPlate,
            command.trailerId(), trailerPlate);
    }

    /**
     * El rechazo DURO: alguno de los recursos pedidos ya participa de este mismo viaje, como
     * principal o como refuerzo anterior.
     *
     * <p>Se reportan TODOS los repetidos y no solo el primero, en el orden en que los declara el
     * enum: quien mando los tres de una y repitio dos tiene que poder corregir los dos de una,
     * en vez de descubrirlos de a uno por reintento.
     */
    private void rejectResourcesAlreadyInThisService(
            AddServiceResourcesCommand command, AddedResources resources) {
        Set<ServiceResourceKind> alreadyInService =
            serviceAssignmentRepository.findResourcesAlreadyInService(
                command.serviceId(), command.driverId(), command.tractorId(), command.trailerId());
        if (alreadyInService.isEmpty()) {
            return;
        }
        List<ServiceResourceKind> duplicated = new ArrayList<>();
        for (ServiceResourceKind kind : ServiceResourceKind.values()) {
            if (alreadyInService.contains(kind)) {
                duplicated.add(kind);
            }
        }
        throw serviceResourceConflicts.asDuplicateInSameService(duplicated,
            serviceResourceConflicts.namesByKind(resources.driverFullName(),
                resources.tractorPlate(), resources.trailerPlate()));
    }

    // ---------- Escritura ------------------------------------------------------

    /**
     * UNA fila por pedido, con los recursos que vinieron. No una por recurso: los tres que entran
     * juntos comparten el motivo y el momento, y partirlos en tres filas inventaria tres decisiones
     * donde hubo una.
     */
    private void writeAssignmentRow(
            Service service, AddServiceResourcesCommand command, Integer userId) {
        ServiceAssignment assignment = new ServiceAssignment();
        assignment.serviceId = service.id;
        assignment.driverId = command.driverId();
        assignment.tractorId = command.tractorId();
        assignment.trailerId = command.trailerId();
        // El motivo se guarda CRUDO, con sus saltos, igual que la descripcion de la auditoria: esta
        // columna es el registro reconstruible. El aplastado es cosa de la bitacora, que es la que
        // tiene una linea por dato.
        assignment.reason = command.reason();
        assignment.assignedBy = userId;
        serviceAssignmentRepository.persist(assignment);
    }

    /**
     * El alta del refuerzo NO toca {@code operaciones.services}, asi que el gancho que mueve la
     * version no se dispara solo y la respuesta saldria con el ETag ANTERIOR pese a que el cuerpo
     * ya es otro: el detalle trae una lista de refuerzos mas larga.
     *
     * <p>No alcanza con asignar {@code updatedBy}: si el que suma el refuerzo es el mismo que toco
     * el viaje la vez anterior —el caso NORMAL, no el raro—, la entity no queda sucia, no hay
     * UPDATE, y el gancho no corre. Por eso se mueve la fecha explicitamente: eso ensucia la fila
     * siempre, y el gancho vuelve a estamparla en el volcado.
     *
     * <p>Lo que esta en juego no es el header: un {@code If-Match} guardado antes del refuerzo
     * seguiria siendo valido, y el PUT lo aceptaria sobre un viaje cuyo contenido ya cambio.
     */
    private void touchServiceVersion(Service service, Integer userId) {
        service.updatedBy = userId;
        service.updatedAt = DateUtils.nowUtcMicros();
    }

    /**
     * Una fila por recurso sumado. Los que no vinieron NO dejan fila: una que diga que un campo
     * paso de null a null afirma un cambio que no ocurrio.
     *
     * <p>{@code oldValue} va en null a proposito y no con el recurso principal del viaje: el
     * refuerzo no REEMPLAZA a nadie, se suma, y escribir el principal ahi diria que lo piso.
     *
     * <p>El tipo es ASSIGNMENT, el mismo que la asignacion principal: es lo que el enum ya anticipa
     * en su javadoc, y un valor nuevo obligaria a migrar el CHECK de la tabla. La diferencia entre
     * una y otra la da la descripcion, no el tipo.
     */
    private void writeReinforcementAuditLogs(Service service, AddedResources resources,
            List<ServiceResourceConflictResponse> conflicts,
            AddServiceResourcesCommand command, Integer userId) {
        String description = auditDescription(conflicts, command);
        if (resources.driverId() != null) {
            writeAuditLog(service, "driver", "Conductor",
                String.valueOf(resources.driverId()), description, userId);
        }
        if (resources.tractorId() != null) {
            writeAuditLog(service, "tractor", "Tracto",
                String.valueOf(resources.tractorId()), description, userId);
        }
        if (resources.trailerId() != null) {
            writeAuditLog(service, "trailer", "Carreta",
                String.valueOf(resources.trailerId()), description, userId);
        }
    }

    /**
     * OJO con {@code description}: guarda el texto del usuario CRUDO, sin aplastar sus saltos de
     * linea, a diferencia de la bitacora. Es deliberado —la auditoria es el registro reconstruible
     * y ahi el texto exacto es el dato—, pero significa que quien alguna vez MUESTRE esta columna
     * es el responsable de escaparla. Hoy no la expone ningun endpoint.
     */
    private void writeAuditLog(Service service, String fieldName, String fieldLabel,
            String newValue, String description, Integer userId) {
        ServiceAuditLog auditLog = new ServiceAuditLog();
        auditLog.serviceId = service.id;
        auditLog.changedBy = userId;
        auditLog.changeType = ServiceAuditChangeType.ASSIGNMENT.name();
        auditLog.fieldName = fieldName;
        auditLog.fieldLabel = fieldLabel;
        auditLog.oldValue = null;
        auditLog.newValue = newValue;
        auditLog.description = description;
        serviceAuditLogRepository.persist(auditLog);
    }

    /**
     * El motivo SIEMPRE se concatena, a diferencia de la asignacion, donde la nota es opcional:
     * aca es obligatorio, asi que no hay caso en que la descripcion quede sin el.
     */
    private String auditDescription(List<ServiceResourceConflictResponse> conflicts,
            AddServiceResourcesCommand command) {
        String base = wasForced(conflicts, command)
            ? FORCED_REINFORCEMENT_DESCRIPTION
            : REINFORCEMENT_DESCRIPTION;
        return base + ": " + command.reason();
    }

    /**
     * Se forzo de verdad cuando habia conflicto Y se pidio forzar. Mandar {@code force} sin
     * conflicto no ensucia el rastro con una excepcion que nunca ocurrio.
     */
    private boolean wasForced(List<ServiceResourceConflictResponse> conflicts,
            AddServiceResourcesCommand command) {
        return command.force() && !conflicts.isEmpty();
    }

    /**
     * UNA entrada de bitacora por refuerzo, no una por recurso: la bitacora es la linea de tiempo
     * de las ACCIONES del viaje, y sumar tres recursos de una es una sola accion.
     *
     * <p>La primera linea nombra la accion, y ahi esta la diferencia con la asignacion principal:
     * las dos entradas llevan el mismo tipo, asi que si la bitacora no lo dijera en el texto, quien
     * la lea no podria distinguir "se asigno el viaje" de "salio un relevo".
     *
     * <p>Todo valor que entra a una linea se aplasta, no solo el texto del usuario: el nombre del
     * conductor y las placas salen de tablas del sistema anterior, que SI tiene formularios para
     * escribirlas, y un salto de linea ahi planta una linea falsa con el formato del servidor.
     */
    private void writeReinforcementEvent(Service service, AddedResources resources,
            List<ServiceResourceConflictResponse> conflicts,
            AddServiceResourcesCommand command, Integer userId) {
        List<String> lines = new ArrayList<>();
        lines.add("Refuerzo de recursos");
        if (resources.driverId() != null) {
            lines.add("Conductor: " + flat(resources.driverFullName()));
        }
        if (resources.tractorId() != null) {
            lines.add("Tracto: " + flat(resources.tractorPlate()));
        }
        if (resources.trailerId() != null) {
            lines.add("Carreta: " + flat(resources.trailerPlate()));
        }
        if (wasForced(conflicts, command)) {
            for (ServiceResourceConflictResponse conflict : conflicts) {
                lines.add("Refuerzo forzado: " + serviceResourceConflicts.forcedLine(conflict));
            }
        }
        lines.add("Motivo: " + flat(command.reason()));

        ServiceEvent event = new ServiceEvent();
        event.serviceId = service.id;
        event.eventType = ServiceEventType.ASSIGNMENT.name();
        event.note = String.join("\n", lines);
        event.createdBy = userId;
        serviceEventRepository.persist(event);
    }

    private String flat(String value) {
        return ServiceLogText.display(value);
    }
}
