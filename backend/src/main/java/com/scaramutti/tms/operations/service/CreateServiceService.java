package com.scaramutti.tms.operations.service;

import com.scaramutti.tms.auth.dto.UserResponse;
import com.scaramutti.tms.auth.security.CurrentUser;
import com.scaramutti.tms.auth.service.UserLookup;
import com.scaramutti.tms.operations.OperationsError;
import com.scaramutti.tms.operations.dto.ServiceDetailResponse;
import com.scaramutti.tms.operations.dto.embedded.ServiceUserSummary;
import com.scaramutti.tms.operations.mapper.ServiceServiceMapper;
import com.scaramutti.tms.operations.model.ServiceAuditChangeType;
import com.scaramutti.tms.operations.model.ServiceEventType;
import com.scaramutti.tms.operations.model.ServiceStatus;
import com.scaramutti.tms.operations.service.cmd.CreateServiceCommand;
import com.scaramutti.tms.shared.entity.CargoType;
import com.scaramutti.tms.shared.entity.Client;
import com.scaramutti.tms.shared.entity.Currency;
import com.scaramutti.tms.shared.entity.Service;
import com.scaramutti.tms.shared.entity.ServiceAuditLog;
import com.scaramutti.tms.shared.entity.ServiceEvent;
import com.scaramutti.tms.shared.exception.CommonError;
import com.scaramutti.tms.shared.repository.CargoTypeRepository;
import com.scaramutti.tms.shared.repository.ClientRepository;
import com.scaramutti.tms.shared.repository.CurrencyRepository;
import com.scaramutti.tms.shared.repository.ServiceAuditLogRepository;
import com.scaramutti.tms.shared.repository.ServiceEventRepository;
import com.scaramutti.tms.shared.repository.ServiceRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.List;

/**
 * Alta de un servicio de transporte. El viaje nace {@code PENDING_ASSIGNMENT} (sin conductor ni
 * unidad), estrena su bitacora y deja su registro de auditoria, todo en la misma transaccion.
 *
 * <p>Orden del flow: resolver el usuario, validar que cliente, tipo de carga y moneda existan y
 * esten activos, cerrar la puerta al doble-click, persistir el viaje con su codigo y escribir
 * bitacora y auditoria.
 */
@ApplicationScoped
public class CreateServiceService {

    private static final Logger LOG = Logger.getLogger(CreateServiceService.class);

    /** Texto de la primera linea de la bitacora y de la descripcion de su auditoria. */
    private static final String CREATION_NOTE = "Servicio registrado";

    @ConfigProperty(name = "app.operations.anti-duplicate-window-seconds")
    int antiDuplicateWindowSeconds;

    @Inject ServiceRepository serviceRepository;
    @Inject ServiceEventRepository serviceEventRepository;
    @Inject ServiceAuditLogRepository serviceAuditLogRepository;
    @Inject ClientRepository clientRepository;
    @Inject CargoTypeRepository cargoTypeRepository;
    @Inject CurrencyRepository currencyRepository;
    @Inject UserLookup userLookup;
    @Inject CurrentUser currentUser;
    @Inject ServiceServiceMapper serviceServiceMapper;
    @Inject ServicePriceVisibility servicePriceVisibility;

    @Transactional
    public ServiceDetailResponse createService(CreateServiceCommand command) {
        Integer userId = currentUser.requireId();
        servicePriceVisibility.requireCanSeePrices();

        Client client = requireActiveClient(command.clientId());
        CargoType cargoType = requireActiveCargoType(command.cargoTypeId());
        Currency currency = requireActiveCurrency(command.currencyId());

        // El lock por (usuario, cliente) va ANTES del chequeo para cerrar la ventana entre
        // consultar e insertar: dos altas simultaneas quedan serializadas y la segunda ve a la
        // primera ya persistida. Se libera solo al terminar la transaccion.
        serviceRepository.acquireAntiDuplicateLock(userId, command.clientId());
        rejectIfRecentDuplicate(command, userId);

        Service service = persistService(command, userId);
        ServiceEvent event = writeCreationEvent(service, userId);
        writeCreationAuditLog(service, userId);

        UserResponse currentUserResponse = userLookup.require(userId);
        ServiceUserSummary author = serviceServiceMapper.toServiceUserSummary(currentUserResponse);

        return serviceServiceMapper.toServiceDetailResponse(
            service,
            serviceServiceMapper.toServiceClientSummary(client),
            serviceServiceMapper.toServiceCargoTypeSummary(cargoType),
            service.price,
            currency.code,
            // Un viaje recien creado nace pendiente de asignacion, o sea sin ningun recurso: no
            // hay nada que consultar. Los pone el endpoint que los asigna.
            null,
            null,
            null,
            List.of(serviceServiceMapper.toServiceEventResponse(event, author)),
            author
        );
    }

    // ---------- Dependencias ---------------------------------------------------

    private Client requireActiveClient(Integer clientId) {
        Client client = clientRepository.findById(clientId);
        if (client == null || Boolean.FALSE.equals(client.isActive)) {
            throw CommonError.VALIDATION_FAILED.toException(
                "El cliente indicado no existe o está inactivo");
        }
        return client;
    }

    private CargoType requireActiveCargoType(Integer cargoTypeId) {
        CargoType cargoType = cargoTypeRepository.findById(cargoTypeId);
        if (cargoType == null || Boolean.FALSE.equals(cargoType.isActive)) {
            throw CommonError.VALIDATION_FAILED.toException(
                "El tipo de carga indicado no existe o está inactivo");
        }
        return cargoType;
    }

    private Currency requireActiveCurrency(Integer currencyId) {
        Currency currency = currencyRepository.findById(currencyId);
        if (currency == null || Boolean.FALSE.equals(currency.isActive)) {
            throw CommonError.VALIDATION_FAILED.toException(
                "La moneda indicada no existe o está inactiva");
        }
        return currency;
    }

    // ---------- Anti doble-click -----------------------------------------------

    /**
     * Rechaza el reenvio del mismo formulario: mismo usuario, mismo cliente y misma ruta dentro
     * de la ventana configurada. No es una restriccion de unicidad — dos viajes iguales
     * separados en el tiempo son legitimos —, por eso el codigo es propio y no un duplicado.
     */
    private void rejectIfRecentDuplicate(CreateServiceCommand command, Integer userId) {
        List<Service> recent = serviceRepository.findRecentByCreatedByAndClientAndRoute(
            userId, command.clientId(), command.origin(), command.destination(),
            antiDuplicateWindowSeconds);
        if (recent.isEmpty()) {
            return;
        }
        LOG.warnf("Anti-duplicate triggered: createdBy=%d clientId=%d origin=%s destination=%s recentCount=%d",
            userId, command.clientId(), command.origin(), command.destination(), recent.size());
        throw OperationsError.DUPLICATE_SERVICE_DETECTED.toException();
    }

    // ---------- Persistencia ---------------------------------------------------

    /**
     * Persiste el viaje con su codigo ya puesto. El id se reserva de la secuencia ANTES del
     * INSERT porque el codigo se deriva de el: Hibernate arma la sentencia con el estado que la
     * entity tenia al persistirse, asi que asignar el codigo entre el persist y el flush lo deja
     * fuera y la fila sale con el codigo en null. Verificado con las dos estrategias de id que
     * podrian evitar esta reserva manual (IDENTITY y SEQUENCE): ninguna lo recoge.
     */
    private Service persistService(CreateServiceCommand command, Integer userId) {
        Service service = serviceServiceMapper.toServiceEntity(command, userId);
        service.id = serviceRepository.nextId();
        service.code = formatServiceCode(service.id);
        serviceRepository.persist(service);
        serviceRepository.flush();
        return service;
    }

    /**
     * {@code SRV-} + el id con ceros a la izquierda hasta un minimo de 4 digitos. A partir del
     * id 10000 el codigo crece a 5 o mas digitos y NUNCA se trunca: recortarlo a un ancho fijo
     * haria colisionar codigos distintos contra su propia unicidad.
     */
    static String formatServiceCode(Long id) {
        return String.format("SRV-%04d", id);
    }

    private ServiceEvent writeCreationEvent(Service service, Integer userId) {
        ServiceEvent event = new ServiceEvent();
        event.serviceId = service.id;
        event.eventType = ServiceEventType.CREATED.name();
        event.note = CREATION_NOTE;
        event.createdBy = userId;
        serviceEventRepository.persist(event);
        return event;
    }

    private void writeCreationAuditLog(Service service, Integer userId) {
        ServiceAuditLog auditLog = new ServiceAuditLog();
        auditLog.serviceId = service.id;
        auditLog.changedBy = userId;
        auditLog.changeType = ServiceAuditChangeType.CREATED.name();
        auditLog.fieldName = "status";
        auditLog.fieldLabel = "Estado";
        auditLog.newValue = ServiceStatus.PENDING_ASSIGNMENT.name();
        auditLog.description = CREATION_NOTE;
        serviceAuditLogRepository.persist(auditLog);
    }
}
