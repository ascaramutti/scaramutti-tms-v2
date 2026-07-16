package com.scaramutti.tms.sharedcatalogs.worker.api;

import com.scaramutti.tms.shared.dto.WorkerResponse;
import com.scaramutti.tms.sharedcatalogs.worker.mapper.WorkerResourceMapper;
import com.scaramutti.tms.sharedcatalogs.worker.service.WorkerService;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.constraints.Size;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import java.util.List;

/**
 * Listado de trabajadores (GET /workers), catalogo compartido {@code public.workers}
 * (read-only desde v2). Path PLANO (no bajo /warehouse/*): es de {@code public} y lo
 * reutilizara Operaciones. Sin creacion al vuelo (RN-WH9: los trabajadores y las unidades
 * de flota nunca se crean desde almacen, solo se buscan). Sin paginar (plantilla chica).
 */
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
}
