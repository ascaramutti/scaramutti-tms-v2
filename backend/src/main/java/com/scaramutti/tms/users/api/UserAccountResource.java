package com.scaramutti.tms.users.api;

import com.scaramutti.tms.shared.util.CacheHeaders;
import com.scaramutti.tms.users.dto.UserAccountSummaryResponse;
import com.scaramutti.tms.users.service.UserAccountService;
import io.quarkus.security.Authenticated;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.jboss.resteasy.reactive.RestResponse;

import java.util.List;

/**
 * Las cuentas del sistema ({@code public.users}). La lista no lleva texto de busqueda ni paginas: son
 * decenas de filas y se filtran solo por estado. Sin {@code @Consumes}: nada lleva cuerpo, y un GET
 * con otro {@code Content-Type} no tiene por que responder 415.
 */
@Authenticated
@Path("/users")
@Produces(MediaType.APPLICATION_JSON)
public class UserAccountResource {

    @Inject UserAccountService userAccountService;

    /**
     * Lectura plana: los cuatro cargos que administran cuentas ven todas, tambien las de cargos por
     * encima del suyo. Se decide por el cargo del token.
     */
    @GET
    @RolesAllowed({"admin", "general_manager", "operations_manager", "finance_manager"})
    public RestResponse<List<UserAccountSummaryResponse>> listUserAccounts(@QueryParam("isActive") Boolean isActive) {
        // Quien tiene acceso al sistema es el padron de accesos: ninguna cache lo guarda.
        return CacheHeaders.notStored(RestResponse.ResponseBuilder.ok(userAccountService.listUserAccounts(isActive)));
    }
}
