package com.scaramutti.tms.quotations.api;

import com.scaramutti.tms.quotations.dto.QuotationConfigResponse;
import com.scaramutti.tms.quotations.dto.QuotationRequest;
import com.scaramutti.tms.quotations.dto.QuotationResponse;
import com.scaramutti.tms.quotations.dto.QuotationSummaryResponse;
import com.scaramutti.tms.quotations.mapper.QuotationResourceMapper;
import com.scaramutti.tms.quotations.model.QuotationStatus;
import com.scaramutti.tms.quotations.model.QuotationType;
import com.scaramutti.tms.quotations.pdf.QuotationPdfService;
import com.scaramutti.tms.quotations.service.CreateQuotationService;
import com.scaramutti.tms.quotations.service.GetQuotationService;
import com.scaramutti.tms.quotations.service.ListQuotationsService;
import com.scaramutti.tms.quotations.service.QuotationConfigService;
import com.scaramutti.tms.quotations.service.UpdateQuotationService;
import com.scaramutti.tms.shared.dto.PageResponse;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.net.URI;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

@Path("/quotations")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class QuotationResource {

    @Inject CreateQuotationService createQuotationService;
    @Inject GetQuotationService getQuotationService;
    @Inject UpdateQuotationService updateQuotationService;
    @Inject ListQuotationsService listQuotationsService;
    @Inject QuotationConfigService quotationConfigService;
    @Inject QuotationResourceMapper resourceMapper;
    @Inject QuotationPdfService pdfService;

    /**
     * Lista cotizaciones paginadas con busqueda + multifiltro. Todos los filtros
     * son opcionales y se combinan con AND. Orden fijo: createdAt DESC.
     *
     * <p>Mismos roles que el resto del modulo — dispatcher excluido. Un listado
     * sin resultados devuelve 200 con content vacio (no 404 — vacio es valido).
     *
     * <p>El usuario interactua con nombres en el frontend (comboboxes); el backend
     * recibe IDs (clientId, currencyId, etc.). El filtro `q` busca por texto sobre
     * code/client.name/client.ruc/origin/destination (minimo 3 chars).
     *
     * <p>{@code totalAmount}/{@code itemsCount} se calculan en runtime (no se
     * persisten). Las fechas se interpretan en zona Lima (UTC-5).
     */
    @GET
    @RolesAllowed({"admin", "sales", "general_manager", "operations_manager"})
    public PageResponse<QuotationSummaryResponse> listQuotations(
        @QueryParam("q")             @Size(min = 3, max = 255)        String q,
        @QueryParam("status")                                        QuotationStatus status,
        @QueryParam("quotationType")                                 QuotationType quotationType,
        @QueryParam("clientId")                                      Integer clientId,
        @QueryParam("createdById")                                   Integer createdById,
        @QueryParam("currencyId")                                    Integer currencyId,
        @QueryParam("cargoTypeId")                                   Integer cargoTypeId,
        @QueryParam("serviceTypeId")                                 Integer serviceTypeId,
        @QueryParam("dateFrom")                                      LocalDate dateFrom,
        @QueryParam("dateTo")                                        LocalDate dateTo,
        @QueryParam("page")  @DefaultValue("0")  @Min(0)             int page,
        @QueryParam("size")  @DefaultValue("20") @Min(1) @Max(100)   int size
    ) {
        return listQuotationsService.list(
            resourceMapper.toListQuotationsQuery(
                q, status, quotationType, clientId, createdById, currencyId,
                cargoTypeId, serviceTypeId, dateFrom, dateTo, page, size
            )
        );
    }

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
            resourceMapper.toSaveQuotationCommand(quotationRequest)
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
     * Edita una cotizacion completa (reemplazo). Reusa {@code QuotationRequest} (mismo
     * body que el POST). Requiere {@code If-Match} con el ETag vigente (optimistic
     * locking): si otro usuario edito en el medio → 412 (COM-004). El {@code quotationType}
     * y el {@code clientId} son inmutables (400 QUO-004 si el body los cambia);
     * {@code code}/{@code createdBy}/{@code createdAt}/{@code status} se preservan.
     *
     * <p>Reemplaza la lista completa de items (borra los viejos e inserta los nuevos).
     * Devuelve la cotizacion actualizada + el nuevo {@code ETag} ({@code updatedAt}).
     */
    @PUT
    @Path("/{id}")
    @RolesAllowed({"admin", "sales", "general_manager", "operations_manager"})
    public Response updateQuotation(
            @PathParam("id") Long id,
            @HeaderParam("If-Match") String ifMatch,
            @Valid @NotNull QuotationRequest quotationRequest) {
        QuotationResponse quotation = updateQuotationService.updateQuotation(
            id, ifMatch, resourceMapper.toSaveQuotationCommand(quotationRequest)
        );
        return Response.ok(quotation)
            .header("ETag", "\"" + quotation.updatedAt().toString() + "\"")
            .build();
    }

    /**
     * Descarga o previsualiza el PDF de la cotizacion. {@code ?preview=true} lo sirve inline
     * (abrir en el navegador); por defecto fuerza descarga. El ETag es el {@code updatedAt}
     * (mismo que el GET): si el cliente manda {@code If-None-Match} con el ETag vigente,
     * responde 304 sin regenerar el PDF. 404 si la cotizacion no existe.
     *
     * <p>{@code Cache-Control: private, no-cache} es OBLIGATORIO: sin el, el browser aplica
     * frescura heuristica y sirve el PDF cacheado SIN revalidar el ETag — tras editar la
     * cotizacion, el preview mostraba la version vieja. {@code no-cache} = puede cachear
     * pero debe revalidar siempre (el 304 mantiene el ahorro de no regenerar).
     */
    @GET
    @Path("/{id}/pdf")
    @Produces("application/pdf")
    @RolesAllowed({"admin", "sales", "general_manager", "operations_manager"})
    public Response downloadQuotationPdf(
            @PathParam("id") Long id,
            @QueryParam("preview") @DefaultValue("false") boolean preview,
            @HeaderParam("If-None-Match") String ifNoneMatch) {
        QuotationResponse quotation = getQuotationService.getById(id);
        String etag = "\"" + quotation.updatedAt().toString() + "\"";
        if (etag.equals(ifNoneMatch)) {
            return Response.notModified()
                .header("ETag", etag)
                .header("Cache-Control", "private, no-cache")
                .build();
        }
        byte[] pdf = pdfService.generate(quotation);
        String disposition = (preview ? "inline" : "attachment")
            + "; filename=\"cotizacion-" + quotation.code() + ".pdf\"";
        return Response.ok(pdf)
            .type("application/pdf")
            .header("Content-Disposition", disposition)
            .header("ETag", etag)
            .header("Cache-Control", "private, no-cache")
            .header("Last-Modified", DateTimeFormatter.RFC_1123_DATE_TIME.format(quotation.updatedAt()))
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
