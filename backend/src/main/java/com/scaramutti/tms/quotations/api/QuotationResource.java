package com.scaramutti.tms.quotations.api;

import com.scaramutti.tms.quotations.dto.QuotationConfigResponse;
import com.scaramutti.tms.quotations.dto.QuotationRequest;
import com.scaramutti.tms.quotations.dto.QuotationResponse;
import com.scaramutti.tms.quotations.mapper.QuotationResourceMapper;
import com.scaramutti.tms.quotations.service.CreateQuotationService;
import com.scaramutti.tms.quotations.service.GetQuotationService;
import com.scaramutti.tms.quotations.service.QuotationConfigService;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.net.URI;

@Path("/quotations")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class QuotationResource {

    @Inject CreateQuotationService createQuotationService;
    @Inject GetQuotationService getQuotationService;
    @Inject QuotationConfigService quotationConfigService;
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

    /**
     * Devuelve la cotizacion completa por id: cabecera + items jerarquicos
     * (con {@code children} del Servicio Integral) + totales calculados +
     * {@code expiresAt} computado + {@code isExpired} flag runtime.
     *
     * <p>Mismos roles que POST — dispatcher excluido. Si el id no existe,
     * 404 con {@code code=QUO-003} y {@code detail} con el id pedido.
     *
     * <p>Header {@code ETag} con el {@code updatedAt} del recurso (mismo
     * formato exacto que el POST: comillas envolventes). El cliente puede
     * usarlo para futuros PUT/PATCH con {@code If-Match} (optimistic
     * locking) sin tener que parsear el body.
     *
     * <p>Totales recalculados desde los items: {@code subtotal = unitPrice * quantity}
     * por item root + {@code igv = subtotal * item.igvPercentage / 100}.
     * El IGV usa el SNAPSHOT persistido en cada item (no el config actual)
     * — preserva integridad del documento firmado para cotizaciones viejas.
     *
     * <p>{@code isExpired} se computa en runtime ({@code now() > createdAt + validityDays}),
     * no se persiste. Las FKs (client, currency, etc.) NO se validan {@code isActive}:
     * cotizaciones viejas pueden referenciar entidades desactivadas y se exponen igual.
     */
    @GET
    @Path("/{id}")
    @RolesAllowed({"admin", "sales", "general_manager", "operations_manager"})
    public Response getQuotation(@PathParam("id") Long id) {
        QuotationResponse quotation = getQuotationService.getById(id);
        return Response.ok(quotation)
            .header("ETag", "\"" + quotation.updatedAt().toString() + "\"")
            .build();
    }

    /**
     * Devuelve las constantes runtime del modulo (IGV vigente, max items root,
     * validez por defecto) que el frontend necesita para precargar el wizard
     * y mostrar previews de subtotal/IGV/total sin hardcodearlas.
     *
     * <p>Mismos roles que POST /quotations — dispatcher excluido porque no
     * opera el wizard de cotizaciones.
     *
     * <p>Cacheable 1 hora ({@code Cache-Control: max-age=3600}): los valores
     * cambian raramente (IGV es legal/nacional, los otros 2 son constantes
     * de negocio). Si el frontend re-renderiza el wizard varias veces en
     * la sesion, el browser sirve la cacheada en vez de pegarle al backend.
     */
    @GET
    @Path("/config")
    @RolesAllowed({"admin", "sales", "general_manager", "operations_manager"})
    public Response getQuotationConfig() {
        QuotationConfigResponse config = quotationConfigService.getConfig();
        // private: cacheable solo en el browser del usuario, no en proxies
        // compartidos / CDN. Coherente con el deploy interno por VPN — el
        // contenido es por-usuario-autenticado, no debe quedar en cache de
        // un proxy intermedio.
        return Response.ok(config)
            .header("Cache-Control", "max-age=3600, private")
            .build();
    }
}
