package com.scaramutti.tms.catalogs.condition.api;

import com.scaramutti.tms.catalogs.condition.dto.ConditionResponse;
import com.scaramutti.tms.catalogs.condition.mapper.ConditionResourceMapper;
import com.scaramutti.tms.catalogs.condition.service.ConditionService;
import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import java.util.List;

/**
 * Catálogo de condiciones generales del PDF de cotización. Filtrable por {@code ?isActive}
 * (convención de catálogos: omitido = todas; true = solo activas — lo que usa el wizard, RN-07;
 * false = solo inactivas). Acceso: cualquier usuario autenticado, igual que el resto de
 * catálogos del módulo. Espeja {@code PaymentTermResource}.
 *
 * <p>La sesión se exige también acá y no solo en la política por ruta de
 * {@code application.properties}: esa política se evalúa sobre la URL tal como llega, y hay
 * avisos publicados de rutas que la esquivan escribiendo el mismo camino con punto y coma o
 * con barras codificadas. La comprobación del código no depende de cómo se escriba la ruta.
 */
@Authenticated
@Path("/quotation-conditions")
@Produces(MediaType.APPLICATION_JSON)
public class ConditionResource {

    @Inject ConditionService conditionService;
    @Inject ConditionResourceMapper conditionResourceMapper;

    @GET
    public List<ConditionResponse> listQuotationConditions(@QueryParam("isActive") Boolean isActive) {
        return conditionService.listConditions(
            conditionResourceMapper.toListConditionsQuery(isActive)
        );
    }
}
