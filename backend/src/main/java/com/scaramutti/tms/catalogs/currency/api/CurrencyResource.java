package com.scaramutti.tms.catalogs.currency.api;

import com.scaramutti.tms.catalogs.currency.dto.CurrencyResponse;
import com.scaramutti.tms.catalogs.currency.mapper.CurrencyResourceMapper;
import com.scaramutti.tms.catalogs.currency.service.CurrencyService;
import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import java.util.List;

/**
 * Exige sesión también en el código, no solo en la política por ruta de
 * {@code application.properties}: esa política se evalúa sobre la URL tal como llega, y hay
 * avisos publicados de rutas que la esquivan escribiendo el mismo camino con punto y coma o
 * con barras codificadas. La comprobación del código no depende de cómo se escriba la ruta.
 * No cambia quién puede leer: sigue bastando con estar autenticado, sin rol particular.
 */
@Authenticated
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
