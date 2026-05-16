package com.scaramutti.tms.catalogs.currency.mapper;

import com.scaramutti.tms.catalogs.currency.dto.CurrencyResponse;
import com.scaramutti.tms.shared.entity.Currency;
import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;

import java.util.List;

/**
 * Mapper de la capa Service: traduce entidades del dominio a DTOs de salida.
 * Lo inyecta CurrencyService cuando arma respuestas para el cliente.
 *
 * El metodo de lista lo genera MapStruct automaticamente usando el de single
 * por debajo — no hace falta escribir la implementacion.
 */
@Mapper(componentModel = MappingConstants.ComponentModel.CDI)
public interface CurrencyServiceMapper {

    CurrencyResponse toCurrencyResponse(Currency currency);

    List<CurrencyResponse> toCurrencyResponseList(List<Currency> currencies);
}
