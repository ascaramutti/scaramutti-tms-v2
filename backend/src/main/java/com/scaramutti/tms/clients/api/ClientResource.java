package com.scaramutti.tms.clients.api;

import com.scaramutti.tms.clients.dto.ClientRequest;
import com.scaramutti.tms.clients.dto.ClientResponse;
import com.scaramutti.tms.clients.mapper.ClientResourceMapper;
import com.scaramutti.tms.clients.service.ClientService;
import com.scaramutti.tms.shared.dto.PageResponse;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.jboss.resteasy.reactive.ResponseStatus;

@Path("/clients")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ClientResource {

    @Inject ClientService clientService;
    @Inject ClientResourceMapper clientResourceMapper;

    /**
     * Sin @RolesAllowed: el contrato listClients no tiene `x-required-roles`,
     * cualquier autenticado puede listar. La policy global protected-paths
     * cubre el caso authn (sin token → 401).
     *
     * Bean Validation en query-params: violaciones disparan
     * ConstraintViolationException → ValidationExceptionMapper → 400 COM-001.
     */
    @GET
    public PageResponse<ClientResponse> listClients(
        @QueryParam("q")        @Size(max = 200)                  String q,
        @QueryParam("isActive")                                   Boolean isActive,
        @QueryParam("page")     @DefaultValue("0")  @Min(0)       int page,
        @QueryParam("size")     @DefaultValue("20") @Min(1) @Max(100) int size
    ) {
        return clientService.listClients(
            clientResourceMapper.toListClientsQuery(q, isActive, page, size)
        );
    }

    @POST
    @RolesAllowed({"admin", "general_manager", "sales", "operations_manager"})
    @ResponseStatus(201) // Response.Status.CREATED — el default de JAX-RS para POST que retorna body es 200, lo sobreescribimos.
    public ClientResponse createClient(@Valid ClientRequest clientRequest) {
        return clientService.createClient(
            clientResourceMapper.toCreateClientCommand(clientRequest)
        );
    }
}
