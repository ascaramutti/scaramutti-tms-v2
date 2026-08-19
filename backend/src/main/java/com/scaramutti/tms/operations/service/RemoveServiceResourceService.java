package com.scaramutti.tms.operations.service;

import com.scaramutti.tms.auth.security.CurrentUser;
import com.scaramutti.tms.operations.OperationsError;
import com.scaramutti.tms.operations.dto.ServiceDetailResponse;
import com.scaramutti.tms.operations.model.ServiceAuditChangeType;
import com.scaramutti.tms.operations.model.ServiceEventType;
import com.scaramutti.tms.operations.model.ServiceStatus;
import com.scaramutti.tms.shared.entity.Service;
import com.scaramutti.tms.shared.entity.ServiceAuditLog;
import com.scaramutti.tms.shared.entity.ServiceEvent;
import com.scaramutti.tms.shared.repository.ServiceAssignmentRepository;
import com.scaramutti.tms.shared.repository.ServiceAssignmentRepository.ServiceAdditionalResourceRow;
import com.scaramutti.tms.shared.repository.ServiceAuditLogRepository;
import com.scaramutti.tms.shared.repository.ServiceEventRepository;
import com.scaramutti.tms.shared.util.DateUtils;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * Baja de un refuerzo cargado por error. Es la contracara de {@link AddServiceResourcesService} y
 * comparte su guarda de estado: solo con el viaje EN RUTA.
 *
 * <p><b>No toma advisory lock, y eso no es un olvido.</b> El alta lo necesita porque ELIGE recursos
 * y puede chocar con otro viaje; la baja solo LIBERA, asi que no puede crear un conflicto nuevo y no
 * hay nada que consultar contra otros viajes.
 *
 * <p>Esperas: la de la fila del VIAJE, y en rigor tambien la de la fila del refuerzo que toma el
 * propio {@code DELETE}. Esa segunda es inalcanzable en la practica —el lock del viaje serializa a
 * todos los escritores del modulo antes de llegar ahi—, asi que el presupuesto de esperas no se
 * mueve; se dice igual para que el numero no se lea como uno solo por definicion.
 *
 * <p><b>Sobre la reapertura (D-OPS-18).</b> La guarda de {@code OPS-009} cuenta filas VIVAS, asi
 * que este endpoint SI cambia la reabribilidad: un viaje al que se le dan de baja los refuerzos
 * ANTES de cancelarlo vuelve a ser reabrible, y ese es el camino que hay que ofrecer. Lo que la baja
 * no puede es rescatar a uno YA cancelado, porque para quitar el refuerzo hay que estar EN RUTA:
 * para esos, el callejon de D-OPS-18 sigue igual.
 *
 * <p>El borrado es FISICO: la tabla no tiene columna de baja y agregarla seria una migracion que
 * este endpoint no justifica. El RASTRO no se va con la fila, porque la bitacora y la auditoria
 * cuelgan del viaje y no del refuerzo.
 */
@ApplicationScoped
public class RemoveServiceResourceService {

    /** El unico estado que admite tocar los refuerzos, igual que el alta. */
    private static final ServiceStatus REINFORCEABLE_STATUS = ServiceStatus.IN_PROGRESS;

    private static final String REMOVAL_DESCRIPTION = "Baja de refuerzo";

    @Inject ServiceAssignmentRepository serviceAssignmentRepository;
    @Inject ServiceEventRepository serviceEventRepository;
    @Inject ServiceAuditLogRepository serviceAuditLogRepository;
    @Inject CurrentUser currentUser;
    @Inject GetServiceService getServiceService;
    @Inject ServiceRowLock serviceRowLock;

    @Transactional
    public ServiceDetailResponse removeServiceResource(long serviceId, long assignmentId) {
        Integer userId = currentUser.requireId();

        // ⚠️ Este envoltorio EXTERNO no lo mata ninguna prueba: la lectura con lock ya trae el suyo
        // adentro, asi que el 409 de la fila del viaje sale igual sin esto. Lo unico que cubre es un
        // choque POSTERIOR al lock —el DELETE sobre los refuerzos, o el volcado—, y fabricarlo pide
        // un arnes que el modulo solo tiene para advisory locks, que esta baja no toma. Se conserva
        // porque el contrato promete OPS-008 "desde cualquier punto", y queda escrito para que nadie
        // crea que hay una red que no existe.
        return serviceRowLock.runTranslatingLockConflicts(
            () -> removeLockedServiceResource(serviceId, assignmentId, userId), serviceId);
    }

    private ServiceDetailResponse removeLockedServiceResource(
            long serviceId, long assignmentId, Integer userId) {
        Service service = serviceRowLock.findByIdForUpdate(serviceId);
        requireReinforceableStatus(service);

        // El viaje entra en la CONSULTA: un refuerzo de otro viaje no se encuentra, asi que no se
        // puede borrar y ademas no se distingue de uno que no existe.
        ServiceAdditionalResourceRow removed = serviceAssignmentRepository
            .findByIdAndServiceId(assignmentId, serviceId)
            .orElseThrow(OperationsError.REINFORCEMENT_NOT_FOUND::toException);

        // El rastro va antes del borrado por LECTURA, no por necesidad: `removed` ya es un record en
        // memoria, asi que moverlo despues del delete daria el mismo resultado. Se dice para que
        // nadie lea aca una red que no existe.
        writeRemovalAuditLogs(service, removed, userId);
        writeRemovalEvent(service, removed, userId);

        // El borrado de Panache es una sentencia INMEDIATA, no un cambio pendiente de flush, asi
        // que el detalle de abajo ya no encuentra la fila. Se midio: agregar un flush aca no cambia
        // nada. Ojo con no leer de ahi que el flush del ALTA sobre: aquel si es portante.
        //
        // El conteo se ATIENDE en vez de descartarse: si la fila se fue entre la busqueda y el
        // borrado, contestar 200 publicaria una bitacora y una fila de auditoria por recurso por una
        // baja que no ocurrio. Hoy el lock de la fila del viaje serializa a todos los escritores y no hay
        // otro codigo que borre refuerzos, asi que es inalcanzable; se atiende igual porque es una
        // linea y porque el javadoc del repositorio lo promete.
        if (serviceAssignmentRepository.deleteByIdAndServiceId(assignmentId, serviceId) == 0) {
            throw OperationsError.REINFORCEMENT_NOT_FOUND.toException();
        }

        // El borrado va a OTRA tabla, asi que el gancho de version del viaje NO se dispara solo. Sin
        // esto, un If-Match tomado ANTES de la baja seguiria siendo valido sobre un viaje cuyo
        // contenido ya cambio. Es el mismo agujero que documenta el alta.
        touchServiceVersion(service, userId);

        return getServiceService.getService(serviceId);
    }

    /**
     * Los dos terminales se rechazan con su propio codigo, ANTES de mirar el estado: el mismo
     * rechazo sobre el mismo viaje no puede contestar codigos distintos segun por que puerta entro.
     */
    private void requireReinforceableStatus(Service service) {
        ServiceStatus status = ServiceStatus.valueOf(service.status);
        ServiceStatusGuards.rejectIfImmutable(status);
        if (status != REINFORCEABLE_STATUS) {
            throw OperationsError.ACTION_NOT_ALLOWED_FOR_STATUS.toException();
        }
    }

    /**
     * Una fila por recurso que el refuerzo TRAIA. Los que la fila no tenia no dejan rastro, igual
     * que en el alta: una fila que diga que un campo paso de null a null afirma un cambio que no
     * ocurrio.
     *
     * <p>Es el espejo exacto del alta y ahi esta toda la diferencia entre las dos: alla el recurso
     * va en {@code newValue} con {@code oldValue} en null, y aca al reves.
     */
    private void writeRemovalAuditLogs(
            Service service, ServiceAdditionalResourceRow removed, Integer userId) {
        if (removed.driverId() != null) {
            writeAuditLog(service, "driver", "Conductor", String.valueOf(removed.driverId()), userId);
        }
        if (removed.tractorId() != null) {
            writeAuditLog(service, "tractor", "Tracto", String.valueOf(removed.tractorId()), userId);
        }
        if (removed.trailerId() != null) {
            writeAuditLog(service, "trailer", "Carreta", String.valueOf(removed.trailerId()), userId);
        }
    }

    private void writeAuditLog(Service service, String fieldName, String fieldLabel,
            String oldValue, Integer userId) {
        ServiceAuditLog auditLog = new ServiceAuditLog();
        auditLog.serviceId = service.id;
        auditLog.changedBy = userId;
        // ASSIGNMENT, el mismo tipo que el alta: un valor nuevo obligaria a migrar el CHECK de la
        // tabla, y lo que distingue una de otra es la descripcion, no el tipo.
        auditLog.changeType = ServiceAuditChangeType.ASSIGNMENT.name();
        auditLog.fieldName = fieldName;
        auditLog.fieldLabel = fieldLabel;
        auditLog.oldValue = oldValue;
        auditLog.newValue = null;
        auditLog.description = REMOVAL_DESCRIPTION;
        serviceAuditLogRepository.persist(auditLog);
    }

    /**
     * UNA entrada de bitacora que nombra solo los recursos que se dieron de baja. Los demas
     * refuerzos del viaje NO se nombran: la linea cuenta lo que paso, no el estado resultante.
     */
    private void writeRemovalEvent(
            Service service, ServiceAdditionalResourceRow removed, Integer userId) {
        List<String> lines = new ArrayList<>();
        lines.add(REMOVAL_DESCRIPTION);
        if (removed.driverId() != null) {
            lines.add("Conductor: " + flat(removed.driverFullName()));
        }
        if (removed.tractorId() != null) {
            lines.add("Tracto: " + flat(removed.tractorPlate()));
        }
        if (removed.trailerId() != null) {
            lines.add("Carreta: " + flat(removed.trailerPlate()));
        }

        ServiceEvent event = new ServiceEvent();
        event.serviceId = service.id;
        event.eventType = ServiceEventType.ASSIGNMENT.name();
        event.note = String.join("\n", lines);
        event.createdBy = userId;
        serviceEventRepository.persist(event);
    }

    /**
     * Mueve la version del viaje. La fecha se asigna A MANO y esa linea NO se puede sacar, aunque el
     * {@code @PreUpdate} de {@link Service} vuelva a estamparla en el volcado: lo que el gancho pisa
     * es el VALOR, y lo que hace falta aca es que la fila quede SUCIA.
     *
     * <p>Asignar {@code updatedBy} no alcanza. Si quien da de baja es el mismo que toco el viaje la
     * vez anterior —el caso NORMAL, no el raro— ese campo ya vale eso, la entity no queda sucia, no
     * hay UPDATE y el gancho ni siquiera corre. Es el mismo razonamiento que el alta escribio en su
     * momento, y esta MEDIDO: borrar la asignacion de la fecha mata el caso del ETag.
     */
    private void touchServiceVersion(Service service, Integer userId) {
        service.updatedBy = userId;
        service.updatedAt = DateUtils.nowUtcMicros();
    }

    /**
     * Los nombres y placas salen de tablas del sistema anterior, que si tiene formularios para
     * escribirlas: se aplastan por el mismo motivo que en el alta, que es de SEGURIDAD y no de
     * formato (una linea forjada entraria a la bitacora como si la hubiera escrito el sistema).
     *
     * <p>⚠️ Solo esta EJERCITADO sobre el nombre del conductor: el caso de la nota forja el salto ahi
     * y no en las placas, porque el fixture de flota genera la placa el mismo. Quitar el aplastado de
     * las dos placas sobrevive la suite.
     */
    private String flat(String value) {
        return ServiceLogText.display(value);
    }
}
