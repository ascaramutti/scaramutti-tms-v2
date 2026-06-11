package com.scaramutti.tms.catalogs.currency.mapper;

import com.scaramutti.tms.catalogs.currency.service.cmd.ListCurrenciesQuery;
import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;
import org.mapstruct.NullValueMappingStrategy;

/**
 * Mapper de la capa REST: traduce parametros HTTP a Queries del service.
 * Lo inyecta CurrencyResource.
 *
 * `nullValueMappingStrategy = RETURN_DEFAULT` hace que MapStruct genere una
 * impl que devuelve un Query inicializado con isActive=null (en vez de
 * devolver null) cuando el parametro es null. Esto preserva la semantica
 * "isActive=null significa sin filtro".
 */
@Mapper(componentModel = MappingConstants.ComponentModel.CDI,
        nullValueMappingStrategy = NullValueMappingStrategy.RETURN_DEFAULT)
public interface CurrencyResourceMapper {

    ListCurrenciesQuery toListCurrenciesQuery(Boolean isActive);
}
