package com.scaramutti.tms.sharedcatalogs.driver.mapper;

import com.scaramutti.tms.shared.mapper.SharedMapperConfig;
import com.scaramutti.tms.sharedcatalogs.driver.service.cmd.ListDriversQuery;
import org.mapstruct.Mapper;
import org.mapstruct.NullValueMappingStrategy;

/**
 * Mapper de la capa REST del listado de conductores. Solo agrupa el filtro en el Query (llega
 * tipado por JAX-RS). {@code RETURN_DEFAULT} es obligatorio: el param es opcional y sin filtro
 * llega null; sin esta estrategia MapStruct devolveria el Query null y el service reventaria
 * (gotcha conocido del proyecto con params opcionales).
 */
@Mapper(
    config = SharedMapperConfig.class,
    nullValueMappingStrategy = NullValueMappingStrategy.RETURN_DEFAULT
)
public interface DriverResourceMapper {

    ListDriversQuery toListDriversQuery(Boolean isActive);
}
