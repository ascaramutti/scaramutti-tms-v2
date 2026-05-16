package com.scaramutti.tms.catalogs.currency.api;

import com.scaramutti.tms.catalogs.currency.dto.CurrencyResponse;
import com.scaramutti.tms.catalogs.currency.mapper.CurrencyResourceMapper;
import com.scaramutti.tms.catalogs.currency.service.CurrencyService;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import java.util.List;

@Path("/currencies")
@Produces(MediaType.APPLICATION_JSON)
public class CurrencyResource {

    @Inject CurrencyService currencyService;
    @Inject CurrencyResourceMapper currencyResourceMapper;

    @GET
    public List<CurrencyResponse> listCurrencies(@QueryParam("isActive") Boolean isActive) {
        return currencyService.listCurrencies(
            currencyResourceMapper.toListCurrenciesQuery(isActive)
        );
    }
}
