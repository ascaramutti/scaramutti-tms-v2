package com.scaramutti.tms.warehouse.supplier.api;

import com.scaramutti.tms.shared.dto.PageResponse;
import com.scaramutti.tms.warehouse.supplier.dto.WarehouseSupplierResponse;
import com.scaramutti.tms.warehouse.supplier.mapper.WarehouseSupplierResourceMapper;
import com.scaramutti.tms.warehouse.supplier.service.WarehouseSupplierService;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

@Path("/warehouse/suppliers")
@Produces(MediaType.APPLICATION_JSON)
public class WarehouseSupplierResource {

    @Inject WarehouseSupplierService warehouseSupplierService;
    @Inject WarehouseSupplierResourceMapper warehouseSupplierResourceMapper;

    @GET
    @RolesAllowed({"admin", "general_manager", "operations_manager", "finance_manager", "warehouse_keeper"})
    public PageResponse<WarehouseSupplierResponse> listSuppliers(
        @QueryParam("q")        @Size(min = 3, max = 200)             String q,
        @QueryParam("isActive")                                       Boolean isActive,
        @QueryParam("page")     @DefaultValue("0")  @Min(0)           int page,
        @QueryParam("size")     @DefaultValue("20") @Min(1) @Max(100) int size
    ) {
        return warehouseSupplierService.listSuppliers(
            warehouseSupplierResourceMapper.toListWarehouseSuppliersQuery(q, isActive, page, size)
        );
    }
}
