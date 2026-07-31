package com.scaramutti.tms.operations.api;

import com.scaramutti.tms.operations.dto.ServiceCreateRequest;
import com.scaramutti.tms.operations.dto.ServiceDetailResponse;
import com.scaramutti.tms.operations.mapper.ServiceResourceMapper;
import com.scaramutti.tms.operations.service.CreateServiceService;
import com.scaramutti.tms.shared.util.Etag;
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

/** Servicios de transporte (viajes) del modulo de operaciones. */
@Path("/services")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ServiceResource {

    @Inject CreateServiceService createServiceService;
    @Inject ServiceResourceMapper serviceResourceMapper;

    /**
     * El despacho no registra viajes: los recibe ya cargados por ventas o la gerencia, igual
     * que en el sistema anterior.
     */
    @POST
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
