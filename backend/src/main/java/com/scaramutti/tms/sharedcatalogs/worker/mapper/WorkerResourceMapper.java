package com.scaramutti.tms.sharedcatalogs.worker.mapper;

import com.scaramutti.tms.shared.mapper.SharedMapperConfig;
import com.scaramutti.tms.shared.util.StringUtils;
import com.scaramutti.tms.sharedcatalogs.worker.service.cmd.ListWorkersQuery;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.NullValueMappingStrategy;

/**
 * Mapper de la capa REST del listado de trabajadores. {@code q} se normaliza con
 * {@code trimToNull} (mismo criterio que products/suppliers): un {@code q} en blanco no
 * filtra. {@code isActive} pasa tal cual.
 *
 * <p>{@code RETURN_DEFAULT} es obligatorio: ambos params son opcionales, y sin filtros
 * (GET /workers pelado) los dos llegan null; sin esta estrategia MapStruct devolveria el
 * Query null y el service reventaria (gotcha conocido del proyecto con params opcionales).
 */
@Mapper(
    config = SharedMapperConfig.class,
    uses = StringUtils.class,
    nullValueMappingStrategy = NullValueMappingStrategy.RETURN_DEFAULT
)
public interface WorkerResourceMapper {

    @Mapping(target = "q", source = "q", qualifiedByName = "trimToNull")
    ListWorkersQuery toListWorkersQuery(String q, Boolean isActive);
}
