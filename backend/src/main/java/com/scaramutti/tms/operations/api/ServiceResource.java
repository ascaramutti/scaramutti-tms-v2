package com.scaramutti.tms.operations.api;

import com.scaramutti.tms.operations.dto.ServiceCreateRequest;
import com.scaramutti.tms.operations.dto.ServiceDetailResponse;
import com.scaramutti.tms.operations.dto.ServiceSummaryResponse;
import com.scaramutti.tms.operations.mapper.ServiceResourceMapper;
import com.scaramutti.tms.operations.service.CreateServiceService;
import com.scaramutti.tms.operations.service.ListServicesService;
import com.scaramutti.tms.shared.dto.PageResponse;
import com.scaramutti.tms.shared.util.Etag;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.net.URI;

/** Servicios de transporte (viajes) del modulo de operaciones. */
@Path("/services")
@Produces(MediaType.APPLICATION_JSON)
public class ServiceResource {

    @Inject CreateServiceService createServiceService;
    @Inject ListServicesService listServicesService;
    @Inject ServiceResourceMapper serviceResourceMapper;

    /**
     * El despacho ve el listado (lo necesita para operar), pero sin precios: eso lo resuelve el
     * service, no la interfaz.
     */
    @GET
    @RolesAllowed({"admin", "sales", "general_manager", "operations_manager", "dispatcher"})
    public Response listServices(
        @QueryParam("q")        String q,
        @QueryParam("status")   String status,
        @QueryParam("clientId") String clientId,
        @QueryParam("dateFrom") String dateFrom,
        @QueryParam("dateTo")   String dateTo,
        @QueryParam("page")     String page,
        @QueryParam("size")     String size
    ) {
        PageResponse<ServiceSummaryResponse> services = listServicesService.listServices(
            serviceResourceMapper.toListServicesQuery(q, status, clientId, dateFrom, dateTo, page, size)
        );
        // El cuerpo depende de QUIEN pregunta (el despacho no recibe precios), asi que no debe
        // guardarse en ningun cache intermedio: serviria la respuesta de un rol a otro.
        return Response.ok(services).header("Cache-Control", "no-store").build();
    }

    /**
     * El despacho no registra viajes: los recibe ya cargados por ventas o la gerencia, igual
     * que en el sistema anterior.
     */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed({"admin", "sales", "general_manager", "operations_manager"})
    public Response createService(@Valid @NotNull ServiceCreateRequest serviceCreateRequest) {
        ServiceDetailResponse response = createServiceService.createService(
            serviceResourceMapper.toCreateServiceCommand(serviceCreateRequest)
        );
        return Response.created(URI.create("services/" + response.id()))
            .header("ETag", Etag.of(response.updatedAt()))
            .entity(response)
            .build();
    }
}
