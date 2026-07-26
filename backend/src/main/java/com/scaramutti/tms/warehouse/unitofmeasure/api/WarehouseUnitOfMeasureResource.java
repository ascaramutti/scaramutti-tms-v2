package com.scaramutti.tms.warehouse.unitofmeasure.api;

import com.scaramutti.tms.warehouse.unitofmeasure.dto.WarehouseUnitOfMeasureResponse;
import com.scaramutti.tms.warehouse.unitofmeasure.mapper.WarehouseUnitOfMeasureResourceMapper;
import com.scaramutti.tms.warehouse.unitofmeasure.service.WarehouseUnitOfMeasureService;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import java.util.List;

/** Lista CERRADA (RN-WH9): SIN POST, no se crea al vuelo. */
@Path("/warehouse/units-of-measure")
@Produces(MediaType.APPLICATION_JSON)
public class WarehouseUnitOfMeasureResource {

    @Inject WarehouseUnitOfMeasureService warehouseUnitOfMeasureService;
    @Inject WarehouseUnitOfMeasureResourceMapper warehouseUnitOfMeasureResourceMapper;

    @GET
    @RolesAllowed({"admin", "general_manager", "operations_manager", "finance_manager", "warehouse_keeper"})
    public List<WarehouseUnitOfMeasureResponse> listUnitsOfMeasure(@QueryParam("isActive") Boolean isActive) {
        return warehouseUnitOfMeasureService.listUnitsOfMeasure(
            warehouseUnitOfMeasureResourceMapper.toListWarehouseUnitsOfMeasureQuery(isActive)
        );
    }
}
