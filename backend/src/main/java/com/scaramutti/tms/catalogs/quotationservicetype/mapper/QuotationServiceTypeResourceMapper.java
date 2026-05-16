package com.scaramutti.tms.catalogs.quotationservicetype.mapper;

import com.scaramutti.tms.catalogs.quotationservicetype.service.cmd.ListQuotationServiceTypesQuery;
import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;
import org.mapstruct.NullValueMappingStrategy;

/**
 * Mapper de la capa REST: traduce parametros HTTP a Queries del service.
 * Lo inyecta QuotationServiceTypeResource.
 *
 * `nullValueMappingStrategy = RETURN_DEFAULT` hace que MapStruct genere una
 * impl que devuelve un Query con isActive=null (en vez de devolver null)
 * cuando el parametro es null. Preserva la semantica "null = sin filtro".
 */
@Mapper(componentModel = MappingConstants.ComponentModel.CDI,
        nullValueMappingStrategy = NullValueMappingStrategy.RETURN_DEFAULT)
public interface QuotationServiceTypeResourceMapper {

    ListQuotationServiceTypesQuery toListQuotationServiceTypesQuery(Boolean isActive);
}
