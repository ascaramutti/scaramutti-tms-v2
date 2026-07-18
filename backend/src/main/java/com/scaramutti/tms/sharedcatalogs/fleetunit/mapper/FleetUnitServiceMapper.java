package com.scaramutti.tms.sharedcatalogs.fleetunit.mapper;

import com.scaramutti.tms.shared.mapper.SharedMapperConfig;
import com.scaramutti.tms.shared.repository.FleetUnitRepository.FleetUnitRow;
import com.scaramutti.tms.sharedcatalogs.fleetunit.dto.FleetUnitResponse;
import org.mapstruct.Mapper;

import java.util.List;

/**
 * Mapper de la capa Service: fila de la union de flota a FleetUnitResponse. El
 * {@code kind} viaja como String literal controlado por rama del UNION y MapStruct
 * lo reconstruye al enum de dominio (valueOf generado).
 */
@Mapper(config = SharedMapperConfig.class)
public interface FleetUnitServiceMapper {

    FleetUnitResponse toFleetUnitResponse(FleetUnitRow row);

    List<FleetUnitResponse> toFleetUnitResponseList(List<FleetUnitRow> rows);
}
