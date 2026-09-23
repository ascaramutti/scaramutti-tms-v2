package com.scaramutti.tms.workers.api;

import com.scaramutti.tms.shared.dto.WorkerResponse;
import com.scaramutti.tms.workers.dto.WorkerDetailResponse;
import com.scaramutti.tms.workers.dto.WorkerRequest;
import com.scaramutti.tms.workers.dto.WorkerUpdateRequest;
import io.quarkus.security.Authenticated;
import com.scaramutti.tms.workers.mapper.WorkerResourceMapper;
import com.scaramutti.tms.workers.service.WorkerService;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.jboss.resteasy.reactive.ResponseStatus;

import java.util.List;

/**
 * Trabajadores del catalogo compartido {@code public.workers}: el listado, el detalle, el alta
 * y la edicion. Path PLANO (no bajo /warehouse/*): es de {@code public} y lo
 * reutilizara Operaciones. Sin creacion al vuelo DESDE ALMACEN: los trabajadores y las
 * unidades de flota nunca se crean desde el combobox de un retiro, solo se buscan; el alta es
 * de esta pantalla y el encargado de almacen no la alcanza. Sin paginar (plantilla chica).
 */
/*
 * `@Authenticated` en la clase es REDUNDANTE mientras todos los metodos lleven
 * `@RolesAllowed`, que ya rechaza al anonimo con el mismo 401. Medido: sacarla no hace fallar
 * ninguna prueba, ni siquiera la que existe para medir la guarda del codigo sin la politica
 * por ruta debajo. Se queda igual, por dos motivos: es el molde de los recursos del proyecto,
 * y el dia que este recurso sume un metodo sin lista de roles, esa linea es lo unico que lo
 * cubre. La redundancia es la defensa, no un descuido.
 */
@Authenticated
@Path("/workers")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class WorkerResource {

    @Inject WorkerService workerService;
    @Inject WorkerResourceMapper workerResourceMapper;

    @GET
    @RolesAllowed({"admin", "general_manager", "operations_manager", "finance_manager", "warehouse_keeper"})
    public List<WorkerResponse> listWorkers(
        @QueryParam("q")        @Size(min = 3, max = 200) String q,
        @QueryParam("isActive")                          Boolean isActive
    ) {
        return workerService.listWorkers(
            workerResourceMapper.toListWorkersQuery(q, isActive)
        );
    }

    /**
     * El detalle de un trabajador. Lo leen CUATRO roles y no los cinco del listado, y el
     * discriminante es el NUMERO DE DOCUMENTO: es el unico dato de la ficha que ninguna otra
     * respuesta expone a los roles que quedan afuera. El telefono no sirve de motivo, medido:
     * el catalogo de conductores ya lo publica y lo leen tambien el despacho y ventas.
     *
     * <p>Sin regla de organigrama: la lectura es plana. Quien puede abrir esta pantalla ve
     * cualquier ficha, incluso la de alguien de cargo superior; el organigrama gobierna
     * quien ESCRIBE.
     */
    @GET
    @Path("/{id}")
    @RolesAllowed({"admin", "general_manager", "operations_manager", "finance_manager"})
    public WorkerDetailResponse getWorker(@PathParam("id") Integer id) {
        return workerService.getWorker(id);
    }

    /**
     * El alta de un trabajador. La escriben los CUATRO roles de mantenimiento, no los cinco
     * que leen el listado: el encargado de almacen busca trabajadores para un retiro, pero no
     * los da de alta.
     *
     * <p>Esa lista es el primer filtro y no el unico: quien pasa todavia tiene que estar por
     * ENCIMA en el organigrama del cargo que quiere crear, y eso lo decide el servicio, que es
     * donde vive el nivel de cada uno.
     *
     * <p>{@code @NotNull} ademas de {@code @Valid}: con {@code @Valid} solo, un cuerpo ausente
     * pasa como nulo y revienta mas adentro con un 500 sin cuerpo.
     */
    @POST
    @RolesAllowed({"admin", "general_manager", "operations_manager", "finance_manager"})
    @ResponseStatus(201)
    public WorkerDetailResponse createWorker(@Valid @NotNull WorkerRequest workerRequest) {
        return workerService.createWorker(
            workerResourceMapper.toCreateWorkerCommand(workerRequest)
        );
    }


    /**
     * La edicion de un trabajador. Misma lista de roles que el alta: los cuatro que mantienen el
     * padron.
     *
     * <p>Es un REEMPLAZO y no un parche: un campo opcional que no viene queda vacio. Quien busque
     * por que no hay comprobaciones de nulo antes de asignar, la respuesta esta en el contrato.
     *
     * <p>El id viene de la RUTA y el cuerpo no lo declara: es lo que impide editar a un trabajador
     * mandando el id de otro adentro del JSON.
     */
    @PUT
    @Path("/{id}")
    @RolesAllowed({"admin", "general_manager", "operations_manager", "finance_manager"})
    public WorkerDetailResponse updateWorker(
        @PathParam("id") Integer id,
        @Valid @NotNull WorkerUpdateRequest workerUpdateRequest
    ) {
        return workerService.updateWorker(
            workerResourceMapper.toUpdateWorkerCommand(id, workerUpdateRequest)
        );
    }

}
