package com.scaramutti.tms.workers.api;

import com.scaramutti.tms.shared.dto.WorkerResponse;
import com.scaramutti.tms.workers.dto.WorkerDetailResponse;
import io.quarkus.security.Authenticated;
import com.scaramutti.tms.workers.mapper.WorkerResourceMapper;
import com.scaramutti.tms.workers.service.WorkerService;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.constraints.Size;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import java.util.List;

/**
 * Listado de trabajadores (GET /workers), catalogo compartido {@code public.workers}
 * (de lectura: el alta y la edicion llegan con las historias siguientes del modulo). Path
 * PLANO (no bajo /warehouse/*): es de {@code public} y lo
 * reutilizara Operaciones. Sin creacion al vuelo (RN-WH9: los trabajadores y las unidades
 * de flota nunca se crean desde almacen, solo se buscan). Sin paginar (plantilla chica).
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
}
