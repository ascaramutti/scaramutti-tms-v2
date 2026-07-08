package com.scaramutti.tms.warehouse.productcategory.api;

import com.scaramutti.tms.warehouse.productcategory.dto.WarehouseProductCategoryResponse;
import com.scaramutti.tms.warehouse.productcategory.mapper.WarehouseProductCategoryResourceMapper;
import com.scaramutti.tms.warehouse.productcategory.service.WarehouseProductCategoryService;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import java.util.List;

@Path("/warehouse/product-categories")
@Produces(MediaType.APPLICATION_JSON)
public class WarehouseProductCategoryResource {

    @Inject WarehouseProductCategoryService warehouseProductCategoryService;
    @Inject WarehouseProductCategoryResourceMapper warehouseProductCategoryResourceMapper;

    @GET
    @RolesAllowed({"admin", "general_manager", "operations_manager", "finance_manager", "warehouse_keeper"})
    public List<WarehouseProductCategoryResponse> listProductCategories(@QueryParam("isActive") Boolean isActive) {
        return warehouseProductCategoryService.listProductCategories(
            warehouseProductCategoryResourceMapper.toListWarehouseProductCategoriesQuery(isActive)
        );
    }
}
