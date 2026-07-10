package com.scaramutti.tms.warehouse.product.api;

import com.scaramutti.tms.shared.dto.PageResponse;
import com.scaramutti.tms.warehouse.product.dto.WarehouseProductRequest;
import com.scaramutti.tms.warehouse.product.dto.WarehouseProductResponse;
import com.scaramutti.tms.warehouse.product.mapper.WarehouseProductResourceMapper;
import com.scaramutti.tms.warehouse.product.service.WarehouseProductService;
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
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.jboss.resteasy.reactive.ResponseStatus;

@Path("/warehouse/products")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class WarehouseProductResource {

    @Inject WarehouseProductService warehouseProductService;
    @Inject WarehouseProductResourceMapper warehouseProductResourceMapper;

    @GET
    @RolesAllowed({"admin", "general_manager", "operations_manager", "finance_manager", "warehouse_keeper"})
    public PageResponse<WarehouseProductResponse> listProducts(
        @QueryParam("q")          @Size(min = 3, max = 200)             String q,
        @QueryParam("categoryId")                                       Integer categoryId,
        @QueryParam("isActive")                                         Boolean isActive,
        @QueryParam("lowOnly")    @DefaultValue("false")                boolean lowOnly,
        @QueryParam("page")       @DefaultValue("0")  @Min(0)           int page,
        @QueryParam("size")       @DefaultValue("20") @Min(1) @Max(100) int size
    ) {
        return warehouseProductService.listProducts(
            warehouseProductResourceMapper.toListWarehouseProductsQuery(q, categoryId, isActive, lowOnly, page, size)
        );
    }

    @POST
    @RolesAllowed({"admin", "general_manager", "operations_manager", "finance_manager", "warehouse_keeper"})
    @ResponseStatus(201)
    public WarehouseProductResponse createProduct(@Valid @NotNull WarehouseProductRequest warehouseProductRequest) {
        return warehouseProductService.createProduct(
            warehouseProductResourceMapper.toCreateWarehouseProductCommand(warehouseProductRequest)
        );
    }
}
