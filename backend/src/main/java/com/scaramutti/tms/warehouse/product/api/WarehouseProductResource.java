package com.scaramutti.tms.warehouse.product.api;

import com.scaramutti.tms.warehouse.product.dto.WarehouseProductRequest;
import com.scaramutti.tms.warehouse.product.dto.WarehouseProductResponse;
import com.scaramutti.tms.warehouse.product.mapper.WarehouseProductResourceMapper;
import com.scaramutti.tms.warehouse.product.service.WarehouseProductService;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.jboss.resteasy.reactive.ResponseStatus;

@Path("/warehouse/products")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class WarehouseProductResource {

    @Inject WarehouseProductService warehouseProductService;
    @Inject WarehouseProductResourceMapper warehouseProductResourceMapper;

    @POST
    @RolesAllowed({"admin", "general_manager", "operations_manager", "finance_manager", "warehouse_keeper"})
    @ResponseStatus(201)
    public WarehouseProductResponse createProduct(@Valid @NotNull WarehouseProductRequest warehouseProductRequest) {
        return warehouseProductService.createProduct(
            warehouseProductResourceMapper.toCreateWarehouseProductCommand(warehouseProductRequest)
        );
    }
}
