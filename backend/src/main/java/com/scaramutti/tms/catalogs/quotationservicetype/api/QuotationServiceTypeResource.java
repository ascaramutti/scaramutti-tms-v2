package com.scaramutti.tms.catalogs.quotationservicetype.api;

import com.scaramutti.tms.catalogs.quotationservicetype.dto.QuotationServiceTypeResponse;
import com.scaramutti.tms.catalogs.quotationservicetype.mapper.QuotationServiceTypeResourceMapper;
import com.scaramutti.tms.catalogs.quotationservicetype.service.QuotationServiceTypeService;
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
@Path("/quotation-service-types")
@Produces(MediaType.APPLICATION_JSON)
public class QuotationServiceTypeResource {

    @Inject QuotationServiceTypeService quotationServiceTypeService;
    @Inject QuotationServiceTypeResourceMapper quotationServiceTypeResourceMapper;

    @GET
    public List<QuotationServiceTypeResponse> listQuotationServiceTypes(@QueryParam("isActive") Boolean isActive) {
        return quotationServiceTypeService.listQuotationServiceTypes(
            quotationServiceTypeResourceMapper.toListQuotationServiceTypesQuery(isActive)
        );
    }
}
