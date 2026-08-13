package com.scaramutti.tms.operations.mapper;

import com.scaramutti.tms.auth.dto.UserResponse;
import com.scaramutti.tms.operations.dto.ServiceDetailResponse;
import com.scaramutti.tms.operations.dto.ServiceEventResponse;
import com.scaramutti.tms.operations.dto.ServiceSummaryResponse;
import com.scaramutti.tms.operations.dto.embedded.ServiceCargoTypeSummary;
import com.scaramutti.tms.operations.dto.embedded.ServiceClientSummary;
import com.scaramutti.tms.operations.dto.embedded.ServiceDriverSummary;
import com.scaramutti.tms.operations.dto.embedded.ServiceUserSummary;
import com.scaramutti.tms.operations.model.ServiceStatus;
import com.scaramutti.tms.operations.model.TripScope;
import com.scaramutti.tms.operations.service.cmd.CreateServiceCommand;
import com.scaramutti.tms.shared.entity.CargoType;
import com.scaramutti.tms.shared.entity.Client;
import com.scaramutti.tms.shared.entity.Service;
import com.scaramutti.tms.shared.entity.ServiceEvent;
import com.scaramutti.tms.shared.mapper.SharedMapperConfig;
import com.scaramutti.tms.shared.repository.ServiceRepository.ServiceListRow;
import com.scaramutti.tms.sharedcatalogs.fleetunit.dto.FleetUnitRef;
import com.scaramutti.tms.warehouse.model.FleetUnitKind;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.math.BigDecimal;
import java.util.List;

/**
 * Mapper de la capa Service: arma la entity del viaje a partir del command + el usuario
 * autenticado, y le da forma al detalle. Los lookups (cliente, tipo de carga, moneda, usuarios)
 * los resuelve el service; aca llegan resueltos.
 *
 * <p>El {@code code} NO se mapea: se deriva del id, asi que lo asigna el service cuando la
 * secuencia ya entrego el id.
 */
@Mapper(config = SharedMapperConfig.class)
public interface ServiceServiceMapper {

    @Mapping(target = "id",            ignore = true)
    @Mapping(target = "code",          ignore = true)
    @Mapping(target = "status",        constant = "PENDING_ASSIGNMENT")
    @Mapping(target = "driverId",      ignore = true)
    @Mapping(target = "tractorId",     ignore = true)
    @Mapping(target = "trailerId",     ignore = true)
    @Mapping(target = "startDateTime", ignore = true)
    @Mapping(target = "endDateTime",   ignore = true)
    @Mapping(target = "createdAt",     ignore = true)
    @Mapping(target = "updatedAt",     ignore = true)
    @Mapping(target = "weight",        source = "command.weightKg")
    @Mapping(target = "length",        source = "command.lengthM")
    @Mapping(target = "width",         source = "command.widthM")
    @Mapping(target = "height",        source = "command.heightM")
    @Mapping(target = "createdBy",     source = "userId")
    @Mapping(target = "updatedBy",     source = "userId")
    Service toServiceEntity(CreateServiceCommand command, Integer userId);

    @Mapping(target = "id",            source = "service.id")
    @Mapping(target = "code",          source = "service.code")
    @Mapping(target = "client",        source = "client")
    @Mapping(target = "origin",        source = "service.origin")
    @Mapping(target = "destination",   source = "service.destination")
    @Mapping(target = "tentativeDate", source = "service.tentativeDate")
    @Mapping(target = "tripScope",     source = "service.tripScope")
    @Mapping(target = "cargoType",     source = "cargoType")
    @Mapping(target = "weightKg",      source = "service.weight")
    @Mapping(target = "lengthM",       source = "service.length")
    @Mapping(target = "widthM",        source = "service.width")
    @Mapping(target = "heightM",       source = "service.height")
    @Mapping(target = "observations",  source = "service.observations")
    @Mapping(target = "price",         source = "price")
    @Mapping(target = "currencyCode",  source = "currencyCode")
    @Mapping(target = "status",        source = "service.status")
    @Mapping(target = "driver",        source = "driver")
    @Mapping(target = "tractor",       source = "tractor")
    @Mapping(target = "trailer",       source = "trailer")
    @Mapping(target = "startDateTime", source = "service.startDateTime")
    @Mapping(target = "endDateTime",   source = "service.endDateTime")
    @Mapping(target = "events",        source = "events")
    @Mapping(target = "createdBy",     source = "createdBy")
    @Mapping(target = "createdAt",     source = "service.createdAt")
    @Mapping(target = "updatedAt",     source = "service.updatedAt")
    ServiceDetailResponse toServiceDetailResponse(
        Service service, ServiceClientSummary client, ServiceCargoTypeSummary cargoType,
        BigDecimal price, String currencyCode, ServiceDriverSummary driver,
        FleetUnitRef tractor, FleetUnitRef trailer, List<ServiceEventResponse> events,
        ServiceUserSummary createdBy
    );

    /**
     * Los recursos asignados de una fila, ya resueltos por consulta, a los tipos de la respuesta.
     * Devuelven null cuando el viaje todavia no tiene ese recurso.
     *
     * <p>Se arman a mano y no por MapStruct porque cada uno sale de DOS columnas sueltas de una
     * proyeccion plana, y la union de esas dos columnas —id sin etiqueta, o al reves— es
     * justamente lo que hay que decidir en un solo lugar.
     */
    default ServiceDriverSummary toServiceDriverSummary(Integer driverId, String driverFullName) {
        return driverId == null ? null : new ServiceDriverSummary(driverId, driverFullName);
    }

    default FleetUnitRef toFleetUnitRef(FleetUnitKind kind, Integer unitId, String plate) {
        return unitId == null ? null : new FleetUnitRef(kind, unitId, plate);
    }

    /**
     * Fila del listado a respuesta. {@code includePrices} decide si el precio y su moneda viajan:
     * en false quedan en null y la anotacion de inclusion del DTO los deja AFUERA del JSON, que
     * es lo que pide el contrato (ausentes, no nulos).
     */
    default ServiceSummaryResponse toServiceSummaryResponse(ServiceListRow row, boolean includePrices) {
        return new ServiceSummaryResponse(
            row.id(),
            row.code(),
            new ServiceClientSummary(row.clientId(), row.clientName(), row.clientRuc(),
                row.clientPhone(), row.clientContactName()),
            row.origin(),
            row.destination(),
            row.tentativeDate(),
            TripScope.valueOf(row.tripScope()),
            ServiceStatus.valueOf(row.status()),
            toServiceDriverSummary(row.driverId(), row.driverFullName()),
            toFleetUnitRef(FleetUnitKind.TRACTOR, row.tractorId(), row.tractorPlate()),
            includePrices ? row.price() : null,
            includePrices ? row.currencyCode() : null,
            row.createdAt()
        );
    }

    ServiceClientSummary toServiceClientSummary(Client client);

    ServiceCargoTypeSummary toServiceCargoTypeSummary(CargoType cargoType);

    ServiceUserSummary toServiceUserSummary(UserResponse user);

    @Mapping(target = "id",        source = "event.id")
    @Mapping(target = "eventType", source = "event.eventType")
    @Mapping(target = "note",      source = "event.note")
    @Mapping(target = "createdBy", source = "createdBy")
    @Mapping(target = "createdAt", source = "event.createdAt")
    ServiceEventResponse toServiceEventResponse(ServiceEvent event, ServiceUserSummary createdBy);
}
