package com.scaramutti.tms.sharedcatalogs.fleetunit.mapper;

import com.scaramutti.tms.shared.mapper.SharedMapperConfig;
import com.scaramutti.tms.sharedcatalogs.fleetunit.service.cmd.ListFleetUnitsQuery;
import com.scaramutti.tms.warehouse.model.FleetUnitKind;
import org.mapstruct.Mapper;
import org.mapstruct.NullValueMappingStrategy;

/**
 * Mapper de la capa REST del listado de flota. Solo agrupa los params en el Query (llegan
 * tipados por JAX-RS). {@code RETURN_DEFAULT} es obligatorio: ambos params son opcionales y
 * sin filtros llegan null; sin esta estrategia MapStruct devolveria el Query null y el
 * service reventaria (gotcha conocido del proyecto con params opcionales).
 */
@Mapper(
    config = SharedMapperConfig.class,
    nullValueMappingStrategy = NullValueMappingStrategy.RETURN_DEFAULT
)
public interface FleetUnitResourceMapper {

    ListFleetUnitsQuery toListFleetUnitsQuery(FleetUnitKind kind, Boolean isActive);
}
