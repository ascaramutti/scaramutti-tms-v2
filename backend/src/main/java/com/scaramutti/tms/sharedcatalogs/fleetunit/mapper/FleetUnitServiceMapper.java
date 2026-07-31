package com.scaramutti.tms.sharedcatalogs.fleetunit.mapper;

import com.scaramutti.tms.shared.mapper.SharedMapperConfig;
import com.scaramutti.tms.shared.repository.FleetUnitRepository.FleetUnitRow;
import com.scaramutti.tms.sharedcatalogs.fleetunit.dto.FleetUnitResponse;
import com.scaramutti.tms.sharedcatalogs.model.FleetResourceStatus;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

/**
 * Mapper de la capa Service: fila de la union de flota a FleetUnitResponse. El
 * {@code kind} viaja como String literal controlado por rama del UNION y MapStruct
 * lo reconstruye al enum de dominio (valueOf generado).
 *
 * <p>La disponibilidad NO usa ese valueOf: en la BD son nombres en minusculas de un catalogo
 * de v1, asi que la traduccion pasa por {@link FleetResourceStatus#fromCatalogName}, que
 * revienta ante un nombre desconocido en vez de dejarlo pasar como null.
 */
@Mapper(config = SharedMapperConfig.class)
public interface FleetUnitServiceMapper {

    @Mapping(target = "status", source = "statusName")
    FleetUnitResponse toFleetUnitResponse(FleetUnitRow row);

    List<FleetUnitResponse> toFleetUnitResponseList(List<FleetUnitRow> rows);

    default FleetResourceStatus toFleetResourceStatus(String catalogName) {
        return FleetResourceStatus.fromCatalogName(catalogName);
    }
}
