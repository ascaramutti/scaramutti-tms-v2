package com.scaramutti.tms.clients.api;

import com.scaramutti.tms.clients.dto.ClientRequest;
import com.scaramutti.tms.clients.dto.ClientResponse;
import com.scaramutti.tms.clients.mapper.ClientResourceMapper;
import com.scaramutti.tms.clients.service.ClientService;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.jboss.resteasy.reactive.ResponseStatus;

@Path("/clients")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ClientResource {

    @Inject ClientService clientService;
    @Inject ClientResourceMapper clientResourceMapper;

    @POST
    @RolesAllowed({"admin", "general_manager", "sales", "operations_manager"})
    @ResponseStatus(201) // Response.Status.CREATED — el default de JAX-RS para POST que retorna body es 200, lo sobreescribimos.
    public ClientResponse createClient(@Valid ClientRequest clientRequest) {
        return clientService.createClient(
            clientResourceMapper.toCreateClientCommand(clientRequest)
        );
    }
}
