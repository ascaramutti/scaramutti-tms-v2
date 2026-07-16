package com.scaramutti.tms.warehouse.stats.api;

import com.scaramutti.tms.warehouse.stats.dto.WarehouseStatsResponse;
import com.scaramutti.tms.warehouse.stats.service.WarehouseStatsService;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * Strip de KPIs de Existencias (GET /warehouse/stats). Contadores del mes
 * calendario en curso (America/Lima), solo ACTIVOS. Sin params, sin ETag: es
 * una lectura agregada en vivo.
 */
@Path("/warehouse/stats")
@Produces(MediaType.APPLICATION_JSON)
public class WarehouseStatsResource {

    @Inject WarehouseStatsService warehouseStatsService;

    @GET
    @RolesAllowed({"admin", "general_manager", "operations_manager", "finance_manager", "warehouse_keeper"})
    public WarehouseStatsResponse getStats() {
        return warehouseStatsService.getStats();
    }
}
