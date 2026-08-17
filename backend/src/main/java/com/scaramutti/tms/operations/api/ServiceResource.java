package com.scaramutti.tms.operations.api;

import com.scaramutti.tms.operations.dto.ServiceAddResourcesRequest;
import com.scaramutti.tms.operations.dto.ServiceAssignResourcesRequest;
import com.scaramutti.tms.operations.dto.ServiceCreateRequest;
import com.scaramutti.tms.operations.dto.ServiceDetailResponse;
import com.scaramutti.tms.operations.dto.ServiceStatusChangeRequest;
import com.scaramutti.tms.operations.dto.ServiceStatsResponse;
import com.scaramutti.tms.operations.dto.ServiceSummaryResponse;
import com.scaramutti.tms.operations.dto.ServiceUpdateRequest;
import com.scaramutti.tms.operations.mapper.ServiceResourceMapper;
import com.scaramutti.tms.operations.service.AddServiceResourcesService;
import com.scaramutti.tms.operations.service.AssignServiceResourcesService;
import com.scaramutti.tms.operations.service.ChangeServiceStatusService;
import com.scaramutti.tms.operations.service.CreateServiceService;
import com.scaramutti.tms.operations.service.GetServiceService;
import com.scaramutti.tms.operations.service.GetServiceStatsService;
import com.scaramutti.tms.operations.service.ListServicesService;
import com.scaramutti.tms.operations.service.UpdateServiceService;
import com.scaramutti.tms.shared.dto.PageResponse;
import com.scaramutti.tms.shared.util.Etag;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
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

/** Servicios de transporte (viajes) del modulo de operaciones. */
@Path("/services")
@Produces(MediaType.APPLICATION_JSON)
public class ServiceResource {

    @Inject AddServiceResourcesService addServiceResourcesService;
    @Inject AssignServiceResourcesService assignServiceResourcesService;
    @Inject ChangeServiceStatusService changeServiceStatusService;
    @Inject CreateServiceService createServiceService;
    @Inject GetServiceService getServiceService;
    @Inject GetServiceStatsService getServiceStatsService;
    @Inject ListServicesService listServicesService;
    @Inject UpdateServiceService updateServiceService;
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
        return Response.ok(services)
            .header("Cache-Control", "no-store")
            .header("Vary", "Authorization")
            .build();
    }

    /**
     * Los indicadores del tablero. Misma audiencia que el listado, que es la pantalla donde viven.
     *
     * <p>Sin recorte por rol: el cuerpo no lleva un solo importe, asi que es IDENTICO para los
     * cinco roles. Se deja escrito para que nadie copie aca la maquinaria que le esconde los precios
     * al despacho.
     *
     * <p>Las dos cabeceras van igual que en los vecinos, aunque el argumento de cada una sea
     * distinto aca. El {@code no-store} protege de que la respuesta sobreviva a la SESION: sin el,
     * un proxy o un perfil de browser compartido puede guardar el cuerpo y servirlo despues del
     * cierre de sesion. El {@code Vary} protegeria de mezclar ROLES, que hoy no puede pasar porque
     * el cuerpo no depende de quien pregunta; se manda igual porque es la segunda red del dia en
     * que alguien saque el {@code no-store} para poder cachear —y este, que es un tablero, es
     * justamente el cuerpo donde mas tienta hacerlo—. Un test fija las DOS cabeceras, y otro
     * fija la premisa de la segunda: que el cuerpo es igual para los cinco roles.
     *
     * <p>⚠️ Declarado ANTES que {@code @Path("/{id}")} a proposito. El detalle recibe el id como
     * TEXTO —para poder contestar un 400 con detalle en vez del 404 vacio del framework— asi que
     * su plantilla matchea el literal {@code stats} sin ningun filtro de tipo que lo salve. JAX-RS
     * resuelve bien (el segmento literal gana), pero aca no hay red: si esa precedencia fallara,
     * {@code GET /services/stats} caeria en el detalle y contestaria "el servicio no existe". Hay
     * un test que lo fija.
     */
    @GET
    @Path("/stats")
    @RolesAllowed({"admin", "sales", "general_manager", "operations_manager", "dispatcher"})
    public Response getServiceStats() {
        return Response.ok(getServiceStatsService.getServiceStats())
            .header("Cache-Control", "no-store")
            .header("Vary", "Authorization")
            .build();
    }

    /**
     * El despacho ve el detalle completo salvo los precios, igual que en el listado.
     *
     * <p>El {@code id} llega como texto y se convierte aca: declarado con su tipo, un valor que
     * no parsea termina en un 404 sin cuerpo del framework, que se confunde con el 404 legitimo
     * de "ese viaje no existe" y no dice nada de por que.
     */
    @GET
    @Path("/{id}")
    @RolesAllowed({"admin", "sales", "general_manager", "operations_manager", "dispatcher"})
    public Response getService(@PathParam("id") String id) {
        ServiceDetailResponse response = getServiceService.getService(
            serviceResourceMapper.toServiceId(id));
        // El cuerpo depende de QUIEN pregunta (el despacho no recibe precios), asi que no debe
        // guardarse en ningun cache intermedio: serviria la respuesta de un rol a otro.
        return Response.ok(response)
            .header("ETag", Etag.of(response.updatedAt()))
            .header("Cache-Control", "no-store")
            .header("Vary", "Authorization")
            .build();
    }

    /**
     * El despacho no edita viajes, por el mismo motivo por el que no los registra.
     *
     * <p>El {@code If-Match} llega como texto y se pasa tal cual: el helper compartido lo compara
     * contra la version actual y contesta 412 tanto si falta como si quedo viejo.
     */
    @PUT
    @Path("/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed({"admin", "sales", "general_manager", "operations_manager"})
    public Response updateService(
        @PathParam("id") String id,
        @HeaderParam("If-Match") String ifMatch,
        @Valid @NotNull ServiceUpdateRequest serviceUpdateRequest
    ) {
        ServiceDetailResponse response = updateServiceService.updateService(
            serviceResourceMapper.toUpdateServiceCommand(
                serviceResourceMapper.toServiceId(id), ifMatch, serviceUpdateRequest)
        );
        // El cuerpo es el mismo detalle que sirve el GET, asi que arrastra su misma condicion:
        // depende de QUIEN pregunta y no debe guardarse en ningun cache intermedio. El `Vary`
        // lo deja explicito: el ETag no distingue el rol, asi que si algun dia alguien saca el
        // `no-store` por rendimiento, la misma version identificaria dos cuerpos distintos.
        return Response.ok(response)
            .header("ETag", Etag.of(response.updatedAt()))
            .header("Cache-Control", "no-store")
            .header("Vary", "Authorization")
            .build();
    }

    /**
     * Asignar recursos SI es del despacho: es la operacion del viaje, no su registro. Por eso la
     * lista de roles no es la del alta ni la de la edicion.
     *
     * <p>No lleva {@code If-Match}: la protección acá es el estado. Solo se asigna desde
     * "pendiente de asignación", y la propia operación abandona ese estado, así que un segundo
     * intento sobre una versión vieja se rechaza por el estado antes de tocar nada.
     *
     * <p><b>Precondición de esta lista de roles:</b> tiene que ser un subconjunto de la del
     * detalle. El cuerpo del conflicto nombra el código y el estado de OTRO viaje, y hoy eso no
     * filtra nada porque cualquiera que pueda asignar ya puede leer ese viaje entero por la
     * puerta de adelante. Un rol que solo asigne convertiría ese 409 en un canal de lectura.
     */
    @POST
    @Path("/{id}/assignment")
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed({"admin", "general_manager", "operations_manager", "dispatcher"})
    public Response assignServiceResources(
        @PathParam("id") String id,
        @Valid @NotNull ServiceAssignResourcesRequest serviceAssignResourcesRequest
    ) {
        ServiceDetailResponse response = assignServiceResourcesService.assignServiceResources(
            serviceResourceMapper.toAssignServiceResourcesCommand(
                serviceResourceMapper.toServiceId(id), serviceAssignResourcesRequest)
        );
        // Mismo cuerpo que el detalle, asi que arrastra sus mismas condiciones: depende de QUIEN
        // pregunta (al despacho le faltan los importes) y no se guarda en ninguna cache.
        return Response.ok(response)
            .header("ETag", Etag.of(response.updatedAt()))
            .header("Cache-Control", "no-store")
            .header("Vary", "Authorization")
            .build();
    }

    /**
     * Sumar refuerzos SI es del despacho, por lo mismo que asignar: es la operacion del viaje, no
     * su registro.
     *
     * <p>No lleva {@code If-Match}, igual que la asignacion, <b>pero por una razon DISTINTA y por
     * eso se escribe</b>: aquella se auto-protege porque ABANDONA el estado que la habilita, y esta
     * se queda en "en ruta", asi que un segundo envio del mismo cuerpo encuentra el endpoint
     * todavia habilitado. Lo que rechaza el reintento aca es OPS-003: el mismo recurso sobre el
     * mismo viaje rebota duro y {@code force} no lo abre. La idempotencia frente al doble-click la
     * da el duplicado, no el estado.
     *
     * <p><b>Precondicion de esta lista de roles:</b> misma que la asignacion, y subconjunto de la
     * del detalle por el mismo motivo — el cuerpo del conflicto nombra el codigo y el estado de
     * OTRO viaje, y un rol que solo pudiera reforzar convertiria ese 409 en un canal de lectura.
     */
    @POST
    @Path("/{id}/resources")
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed({"admin", "general_manager", "operations_manager", "dispatcher"})
    public Response addServiceResources(
        @PathParam("id") String id,
        @Valid @NotNull ServiceAddResourcesRequest serviceAddResourcesRequest
    ) {
        ServiceDetailResponse response = addServiceResourcesService.addServiceResources(
            serviceResourceMapper.toAddServiceResourcesCommand(
                serviceResourceMapper.toServiceId(id), serviceAddResourcesRequest)
        );
        // Mismo cuerpo que el detalle, asi que arrastra sus mismas condiciones: depende de QUIEN
        // pregunta (al despacho le faltan los importes) y no se guarda en ninguna cache.
        return Response.ok(response)
            .header("ETag", Etag.of(response.updatedAt()))
            .header("Cache-Control", "no-store")
            .header("Vary", "Authorization")
            .build();
    }

    /**
     * Transiciones de estado del viaje: iniciar, finalizar, cancelar, eliminar y reabrir.
     *
     * <p>El despacho entra —opera el viaje— pero el veto de las transiciones destructivas y de la reapertura se
     * decide adentro, contra el target pedido: no se puede expresar con {@code @RolesAllowed},
     * que solo sabe de la puerta. {@code sales} no participa: registra y edita el servicio, pero
     * la operacion del viaje es del despacho y la gerencia.
     *
     * <p>El {@code If-Match} se declara siempre y es obligatorio al cancelar, al eliminar y al reabrir.
     * Esa condicionalidad tampoco se puede declarar: depende de un campo del cuerpo.
     */
    @POST
    @Path("/{id}/status")
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed({"admin", "general_manager", "operations_manager", "dispatcher"})
    public Response changeServiceStatus(
        @PathParam("id") String id,
        @HeaderParam("If-Match") String ifMatch,
        @Valid @NotNull ServiceStatusChangeRequest serviceStatusChangeRequest
    ) {
        ServiceDetailResponse response = changeServiceStatusService.changeServiceStatus(
            serviceResourceMapper.toChangeServiceStatusCommand(
                serviceResourceMapper.toServiceId(id), ifMatch, serviceStatusChangeRequest)
        );
        return Response.ok(response)
            .header("ETag", Etag.of(response.updatedAt()))
            .header("Cache-Control", "no-store")
            .header("Vary", "Authorization")
            .build();
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
        // El cuerpo es el mismo detalle que sirven el GET y el PUT, con importes adentro: arrastra
        // sus mismas condiciones. Un 201 no lo guarda ninguna cache compartida por defecto, pero
        // dejarlo como el unico cuerpo sin marcar del recurso es justo la asimetria por la que
        // estas reglas se pudren.
        return Response.created(URI.create("services/" + response.id()))
            .header("ETag", Etag.of(response.updatedAt()))
            .header("Cache-Control", "no-store")
            .header("Vary", "Authorization")
            .entity(response)
            .build();
    }
}
