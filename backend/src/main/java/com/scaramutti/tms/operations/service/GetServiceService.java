package com.scaramutti.tms.operations.service;

import com.scaramutti.tms.auth.dto.UserResponse;
import com.scaramutti.tms.auth.service.UserLookup;
import com.scaramutti.tms.operations.OperationsError;
import com.scaramutti.tms.operations.dto.ServiceDetailResponse;
import com.scaramutti.tms.operations.dto.ServiceEventResponse;
import com.scaramutti.tms.operations.dto.embedded.ServiceUserSummary;
import com.scaramutti.tms.operations.mapper.ServiceServiceMapper;
import com.scaramutti.tms.shared.entity.CargoType;
import com.scaramutti.tms.shared.entity.Client;
import com.scaramutti.tms.shared.entity.Currency;
import com.scaramutti.tms.shared.entity.Service;
import com.scaramutti.tms.shared.entity.ServiceEvent;
import com.scaramutti.tms.shared.exception.CommonError;
import com.scaramutti.tms.shared.repository.CargoTypeRepository;
import com.scaramutti.tms.shared.repository.ClientRepository;
import com.scaramutti.tms.shared.repository.CurrencyRepository;
import com.scaramutti.tms.shared.repository.ServiceEventRepository;
import com.scaramutti.tms.shared.repository.ServiceRepository;
import com.scaramutti.tms.shared.repository.ServiceRepository.ServiceAssignedResourcesRow;
import com.scaramutti.tms.warehouse.model.FleetUnitKind;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;

/**
 * Detalle de un servicio de transporte, con su bitacora.
 *
 * <p>El cliente, el tipo de carga y la moneda se leen por su id en vez de viajar como relaciones
 * de la entidad: el schema {@code operaciones} guarda los ids sueltos contra tablas de
 * {@code public} que son de v1, y mapearlas como asociaciones ataria este modulo al ciclo de vida
 * de un schema que todavia comparte con el sistema viejo.
 */
@ApplicationScoped
public class GetServiceService {

    private static final Logger LOG = Logger.getLogger(GetServiceService.class);

    @Inject ServiceRepository serviceRepository;
    @Inject ServiceEventRepository serviceEventRepository;
    @Inject ClientRepository clientRepository;
    @Inject CargoTypeRepository cargoTypeRepository;
    @Inject CurrencyRepository currencyRepository;
    @Inject ServiceServiceMapper serviceServiceMapper;
    @Inject ServicePriceVisibility servicePriceVisibility;
    @Inject UserLookup userLookup;

    /**
     * Transaccional aunque solo lea: arma la respuesta con varias consultas (el servicio, su
     * bitacora, los autores y los tres catalogos) y asi comparten una sola conexion en vez de
     * tomar y devolver una por cada una.
     *
     * <p>Los autores de la bitacora se resuelven en UN lote: una bitacora larga tiene muchas
     * lineas pero poquisimos autores distintos (el equipo), asi que preguntar por cada linea
     * seria pagar N consultas para traer siempre los mismos dos o tres usuarios.
     *
     * <p>La moneda se resuelve SIEMPRE, aunque el precio no viaje: si se resolviera solo cuando
     * se muestra, una fila con la clave foranea rota respondería 500 a unos roles y 200 a otros,
     * y el estado de la base no puede depender de quien pregunta.
     */
    @Transactional
    public ServiceDetailResponse getService(long serviceId) {
        Service service = serviceRepository.findById(serviceId);
        if (service == null) {
            throw OperationsError.SERVICE_NOT_FOUND.toException();
        }

        List<ServiceEvent> events = serviceEventRepository.listByServiceIdOrderedByCreatedAt(serviceId);
        Map<Integer, UserResponse> authorsById = userLookup.requireAllById(
            events.stream().map(event -> event.createdBy).toList());

        Currency currency = requireCurrency(service);
        boolean includePrices = servicePriceVisibility.includePrices();
        // Los tres recursos en UNA consulta con uniones externas, en vez de tres lecturas por
        // clave: los tres cuelgan de la misma fila y ninguno es obligatorio.
        ServiceAssignedResourcesRow resources = serviceRepository.findAssignedResources(serviceId);

        return serviceServiceMapper.toServiceDetailResponse(
            service,
            serviceServiceMapper.toServiceClientSummary(requireClient(service)),
            serviceServiceMapper.toServiceCargoTypeSummary(requireCargoType(service)),
            includePrices ? service.price : null,
            includePrices ? currency.code : null,
            serviceServiceMapper.toServiceDriverSummary(
                resources.driverId(), resources.driverFullName()),
            serviceServiceMapper.toFleetUnitRef(
                FleetUnitKind.TRACTOR, resources.tractorId(), resources.tractorPlate()),
            serviceServiceMapper.toFleetUnitRef(
                FleetUnitKind.TRAILER, resources.trailerId(), resources.trailerPlate()),
            toEventResponses(events, authorsById),
            summaryOf(service.createdBy, authorsById));
    }

    private List<ServiceEventResponse> toEventResponses(
            List<ServiceEvent> events, Map<Integer, UserResponse> authorsById) {
        return events.stream()
            .map(event -> serviceServiceMapper.toServiceEventResponse(
                event, summaryOf(event.createdBy, authorsById)))
            .toList();
    }

    /**
     * El lote ya trae a los autores de la bitacora; quien creo el servicio casi siempre es uno de
     * ellos (la primera linea es suya), asi que se busca ahi antes de ir a la base.
     */
    private ServiceUserSummary summaryOf(Integer userId, Map<Integer, UserResponse> authorsById) {
        UserResponse user = authorsById.get(userId);
        return serviceServiceMapper.toServiceUserSummary(
            user != null ? user : userLookup.require(userId));
    }

    /**
     * Los tres catalogos son claves foraneas OBLIGATORIAS: que no resuelvan no es un dato faltante
     * sino una base rota, y se contesta como error del servidor en vez de servir un detalle con
     * agujeros que el contrato declara imposibles.
     */
    private Client requireClient(Service service) {
        Client client = clientRepository.findById(service.clientId);
        return requireReference(client, "cliente", service.clientId, service.id);
    }

    private CargoType requireCargoType(Service service) {
        CargoType cargoType = cargoTypeRepository.findById(service.cargoTypeId);
        return requireReference(cargoType, "tipo de carga", service.cargoTypeId, service.id);
    }

    private Currency requireCurrency(Service service) {
        Currency currency = currencyRepository.findById(service.currencyId);
        return requireReference(currency, "moneda", service.currencyId, service.id);
    }

    private <T> T requireReference(T reference, String what, Integer referenceId, Long serviceId) {
        if (reference == null) {
            LOG.errorf("Orphan FK: el %s con id=%s del servicio id=%s no existe", what, referenceId, serviceId);
            throw CommonError.INTERNAL_ERROR.toException(
                "El servicio referencia un " + what + " que ya no existe");
        }
        return reference;
    }
}
