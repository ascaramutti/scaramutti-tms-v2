package com.scaramutti.tms.quotations.api;

import com.scaramutti.tms.quotations.dto.QuotationRequest;
import com.scaramutti.tms.quotations.dto.QuotationResponse;
import com.scaramutti.tms.quotations.mapper.QuotationResourceMapper;
import com.scaramutti.tms.quotations.service.CreateQuotationService;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.net.URI;

@Path("/quotations")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class QuotationResource {

    @Inject CreateQuotationService createQuotationService;
    @Inject QuotationResourceMapper resourceMapper;

    /**
     * Crea una cotizacion.
     *
     * @RolesAllowed: admin, sales, general_manager, operations_manager pueden crear
     * cotizaciones. Dispatcher NO (mismo criterio que POST /clients, POST /cargo-types).
     *
     * Devuelve 201 con `QuotationResponse` completo: cabecera + items (jerarquia
     * con children del Servicio Integral) + totales calculados + expiresAt
     * computado. El frontend puede generar el PDF desde este response sin
     * necesidad de GET adicional.
     *
     * Headers de la respuesta (contrato OpenAPI):
     *  - {@code Location}: URI relativa al recurso recien creado.
     *  - {@code ETag}: hash del {@code updatedAt} — source para optimistic
     *    locking en futuros PUT/PATCH con {@code If-Match}.
     */
    @POST
    @RolesAllowed({"admin", "sales", "general_manager", "operations_manager"})
    public Response createQuotation(@Valid @NotNull QuotationRequest quotationRequest) {
        QuotationResponse quotation = createQuotationService.createQuotation(
            resourceMapper.toCreateQuotationCommand(quotationRequest)
        );
        return Response.created(URI.create("/api/v1/quotations/" + quotation.id()))
            .header("ETag", "\"" + quotation.updatedAt().toString() + "\"")
            .entity(quotation)
            .build();
    }
}
