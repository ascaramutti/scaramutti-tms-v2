package com.scaramutti.tms.catalogs.condition.mapper;

import com.scaramutti.tms.catalogs.condition.service.cmd.ListConditionsQuery;
import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;
import org.mapstruct.NullValueMappingStrategy;

/**
 * Mapper de la capa REST: traduce el parámetro HTTP {@code isActive} a la Query del service.
 * Lo inyecta {@code ConditionResource}.
 *
 * {@code nullValueMappingStrategy = RETURN_DEFAULT} hace que, ante {@code isActive=null},
 * MapStruct devuelva un Query con {@code isActive=null} (en vez de null), preservando la
 * semántica "null = sin filtro (todas)". Espeja {@code PaymentTermResourceMapper}.
 */
@Mapper(componentModel = MappingConstants.ComponentModel.CDI,
        nullValueMappingStrategy = NullValueMappingStrategy.RETURN_DEFAULT)
public interface ConditionResourceMapper {

    ListConditionsQuery toListConditionsQuery(Boolean isActive);
}
