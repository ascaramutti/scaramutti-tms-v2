package com.scaramutti.tms.catalogs.currency.service;

import com.scaramutti.tms.catalogs.currency.dto.CurrencyResponse;
import com.scaramutti.tms.catalogs.currency.mapper.CurrencyServiceMapper;
import com.scaramutti.tms.catalogs.currency.service.cmd.ListCurrenciesQuery;
import com.scaramutti.tms.shared.entity.Currency;
import com.scaramutti.tms.shared.repository.CurrencyRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests del branching del service. Aisla la decision "isActive null vs
 * not-null" del IO de BD usando mocks. Los integration tests
 * (CurrenciesResourceTest) cubren el resultado end-to-end con BD real.
 */
@ExtendWith(MockitoExtension.class)
class CurrencyServiceTest {

    @Mock CurrencyRepository currencyRepository;
    @Mock CurrencyServiceMapper currencyServiceMapper;
    @InjectMocks CurrencyService currencyService;

    @Test
    void listCurrencies_withNullFilter_callsListAllOrderedByCode() {
        ListCurrenciesQuery query = new ListCurrenciesQuery(null);
        when(currencyRepository.listAllOrderedByCode()).thenReturn(List.of());
        when(currencyServiceMapper.toCurrencyResponseList(any())).thenReturn(List.of());

        currencyService.listCurrencies(query);

        verify(currencyRepository).listAllOrderedByCode();
        verify(currencyRepository, never()).listByIsActiveOrderedByCode(anyBoolean());
    }

    @Test
    void listCurrencies_withTrueFilter_callsListByIsActiveOrderedByCodeWithTrue() {
        ListCurrenciesQuery query = new ListCurrenciesQuery(true);
        when(currencyRepository.listByIsActiveOrderedByCode(true)).thenReturn(List.of());
        when(currencyServiceMapper.toCurrencyResponseList(any())).thenReturn(List.of());

        currencyService.listCurrencies(query);

        verify(currencyRepository).listByIsActiveOrderedByCode(true);
        verify(currencyRepository, never()).listAllOrderedByCode();
    }

    @Test
    void listCurrencies_withFalseFilter_callsListByIsActiveOrderedByCodeWithFalse() {
        ListCurrenciesQuery query = new ListCurrenciesQuery(false);
        when(currencyRepository.listByIsActiveOrderedByCode(false)).thenReturn(List.of());
        when(currencyServiceMapper.toCurrencyResponseList(any())).thenReturn(List.of());

        currencyService.listCurrencies(query);

        verify(currencyRepository).listByIsActiveOrderedByCode(false);
        verify(currencyRepository, never()).listAllOrderedByCode();
    }

    @Test
    void listCurrencies_returnsResultFromMapper() {
        // Verifica que el resultado del mapper SE devuelve (no se ignora ni
        // se re-procesa). Defensa ante futuros refactors que rompan la cadena
        // repo → mapper → return.
        ListCurrenciesQuery query = new ListCurrenciesQuery(null);
        Currency usd = new Currency();
        usd.id = 1;
        usd.code = "USD";
        usd.symbol = "$";
        usd.name = "Dólar Estadounidense";
        usd.isActive = true;
        List<Currency> entitiesFromRepository = List.of(usd);

        CurrencyResponse usdResponse = new CurrencyResponse(1, "USD", "$", "Dólar Estadounidense", true);
        List<CurrencyResponse> mappedResponses = List.of(usdResponse);

        when(currencyRepository.listAllOrderedByCode()).thenReturn(entitiesFromRepository);
        when(currencyServiceMapper.toCurrencyResponseList(entitiesFromRepository)).thenReturn(mappedResponses);

        List<CurrencyResponse> result = currencyService.listCurrencies(query);

        assertEquals(mappedResponses, result);
    }
}
