package com.scaramutti.tms.clients.api;

import com.scaramutti.tms.clients.dto.ClientRequest;
import com.scaramutti.tms.clients.dto.ClientResponse;
import com.scaramutti.tms.clients.mapper.ClientResourceMapper;
import com.scaramutti.tms.clients.service.ClientService;
import com.scaramutti.tms.shared.dto.PageResponse;
import io.quarkus.security.Authenticated;
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
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.jboss.resteasy.reactive.ResponseStatus;
import org.jboss.resteasy.reactive.RestResponse;

/**
 * Exige sesion tambien en el codigo, no solo en la policy por ruta de application.properties:
 * esa policy se evalua sobre la URL tal como llega, y hay avisos publicados de rutas que la
 * esquivan escribiendo el mismo camino con punto y coma o con barras codificadas. La
 * comprobacion del codigo no depende de como se escriba la ruta. Con todos los metodos bajo
 * @RolesAllowed es redundante a proposito: cubre el dia que se sume uno sin lista de roles.
 */
@Authenticated
@Path("/clients")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ClientResource {

    @Inject ClientService clientService;
    @Inject ClientResourceMapper clientResourceMapper;

    /**
     * Leer clientes es de los roles que los buscan desde una pantalla (cotizaciones, viajes y
     * clientes); los demas no buscan clientes en ninguna. El rol va en el recurso y no en el
     * service, porque cotizaciones usa el service para validar el cliente. El cuerpo es
     * sensible: no-store para que no sobreviva a la sesion en el navegador. El minimo de 3 en q
     * es el mismo que el combobox exige antes de buscar.
     */
    @GET
    @RolesAllowed({"admin", "sales", "general_manager", "operations_manager"})
    public RestResponse<PageResponse<ClientResponse>> listClients(
        @QueryParam("q")        @Size(min = 3, max = 200)         String q,
        @QueryParam("isActive")                                   Boolean isActive,
        @QueryParam("page")     @DefaultValue("0")  @Min(0)       int page,
        @QueryParam("size")     @DefaultValue("20") @Min(1) @Max(100) int size
    ) {
        return notStored(clientService.listClients(
            clientResourceMapper.toListClientsQuery(q, isActive, page, size)
        ));
    }

    /**
     * Mismos roles y mismas cabeceras que el listado, por lo mismo. El rol se decide antes de
     * convertir o buscar el id: un rol sin acceso no averigua por esta ruta que clientes existen.
     * Devuelve activos e inactivos porque la edicion tambien corrige a un inactivo. Con un rol
     * permitido, un id que no entra en un entero responde 404 sin cuerpo: el conversor falla
     * antes de llegar aca.
     */
    @GET
    @Path("/{id}")
    @RolesAllowed({"admin", "sales", "general_manager", "operations_manager"})
    public RestResponse<ClientResponse> getClient(@PathParam("id") Integer id) {
        return notStored(clientService.findById(id));
    }

    /**
     * Reemplaza los cuatro datos editables. Sin @ResponseStatus: 200 ya es el
     * default de JAX-RS para un metodo con cuerpo (el 201 del POST se declara
     * justamente porque no lo es). Devuelve el DTO sin las cabeceras de las lecturas:
     * sin If-Match no hay ETag que colgar, y un PUT no se guarda en cache.
     *
     * `@Valid @NotNull` los dos: sin @NotNull un cuerpo vacio llegaria como null
     * al mapper y saldria un 500 en vez del 400 que declara el contrato.
     */
    @PUT
    @Path("/{id}")
    @RolesAllowed({"admin", "general_manager", "operations_manager"})
    public ClientResponse updateClient(
        @PathParam("id") Integer id,
        @Valid @NotNull ClientRequest clientRequest
    ) {
        return clientService.updateClient(id, clientResourceMapper.toUpdateClientCommand(clientRequest));
    }

    @POST
    @RolesAllowed({"admin", "general_manager", "sales", "operations_manager"})
    @ResponseStatus(201) // Response.Status.CREATED — el default de JAX-RS para POST que retorna body es 200, lo sobreescribimos.
    public ClientResponse createClient(@Valid @NotNull ClientRequest clientRequest) {
        return clientService.createClient(
            clientResourceMapper.toCreateClientCommand(clientRequest)
        );
    }

    /**
     * El tipo ata la respuesta al cuerpo: devolver la entidad en vez del DTO no compila. Vary
     * ademas de no-store: si alguien quitara el no-store, un cache no mezcla sesiones.
     */
    private static <T> RestResponse<T> notStored(T body) {
        return RestResponse.ResponseBuilder.ok(body)
            .header("Cache-Control", "no-store")
            .header("Vary", "Authorization")
            .build();
    }
}
