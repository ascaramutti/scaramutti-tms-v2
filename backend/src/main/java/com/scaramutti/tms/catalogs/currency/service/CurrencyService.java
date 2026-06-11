package com.scaramutti.tms.catalogs.currency.service;

import com.scaramutti.tms.catalogs.CatalogsError;
import com.scaramutti.tms.catalogs.currency.dto.CurrencyResponse;
import com.scaramutti.tms.catalogs.currency.mapper.CurrencyServiceMapper;
import com.scaramutti.tms.catalogs.currency.service.cmd.ListCurrenciesQuery;
import com.scaramutti.tms.shared.entity.Currency;
import com.scaramutti.tms.shared.repository.CurrencyRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;

@ApplicationScoped
public class CurrencyService {

    @Inject CurrencyRepository currencyRepository;
    @Inject CurrencyServiceMapper currencyServiceMapper;

    /**
     * Devuelve la moneda con el id dado, o tira CAT-003 (404) si no existe.
     * NO filtra por isActive — el caller decide.
     */
    public CurrencyResponse findById(Integer id) {
        Currency currency = currencyRepository.findById(id);
        if (currency == null) {
            throw CatalogsError.CURRENCY_NOT_FOUND.toException();
        }
        return currencyServiceMapper.toCurrencyResponse(currency);
    }

    public List<CurrencyResponse> listCurrencies(ListCurrenciesQuery listCurrenciesQuery) {
        Boolean isActiveFilter = listCurrenciesQuery.isActive();
        List<Currency> currencies = (isActiveFilter == null)
            ? currencyRepository.listAllOrderedByCode()
            : currencyRepository.listByIsActiveOrderedByCode(isActiveFilter);

        return currencyServiceMapper.toCurrencyResponseList(currencies);
    }
}
