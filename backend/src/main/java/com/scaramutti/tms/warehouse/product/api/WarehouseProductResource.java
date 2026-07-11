package com.scaramutti.tms.warehouse.product.api;

import com.scaramutti.tms.shared.dto.PageResponse;
import com.scaramutti.tms.warehouse.product.WarehouseProductEtag;
import com.scaramutti.tms.warehouse.product.dto.WarehouseProductRequest;
import com.scaramutti.tms.warehouse.product.dto.WarehouseProductResponse;
import com.scaramutti.tms.warehouse.product.dto.WarehouseProductStockResponse;
import com.scaramutti.tms.warehouse.product.dto.WarehouseProductUpdateRequest;
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
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
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

    /**
     * Header {@code ETag} con el {@code updatedAt} del recurso (mismo formato que
     * {@code WarehouseProductEtag.verify}): el frontend debe usarlo para el futuro
     * PUT con {@code If-Match} (optimistic locking).
     */
    @GET
    @Path("/{id}")
    @RolesAllowed({"admin", "general_manager", "operations_manager", "finance_manager", "warehouse_keeper"})
    public Response getProduct(@PathParam("id") Integer id) {
        WarehouseProductResponse product = warehouseProductService.getById(id);
        return Response.ok(product)
            .header("ETag", WarehouseProductEtag.of(product.updatedAt()))
            .build();
    }

    /**
     * Edición de catálogo. {@code If-Match} obligatorio (optimistic locking): el
     * ETag a enviar es el HEADER del GET o del PUT anterior, NO el {@code updatedAt}
     * del body. Devuelve el producto actualizado + el nuevo {@code ETag}.
     */
    @PUT
    @Path("/{id}")
    @RolesAllowed({"admin", "general_manager", "operations_manager", "finance_manager", "warehouse_keeper"})
    public Response updateProduct(
        @PathParam("id") Integer id,
        @HeaderParam("If-Match") String ifMatch,
        @Valid @NotNull WarehouseProductUpdateRequest warehouseProductUpdateRequest
    ) {
        WarehouseProductResponse product = warehouseProductService.updateProduct(
            id, ifMatch,
            warehouseProductResourceMapper.toUpdateWarehouseProductCommand(warehouseProductUpdateRequest)
        );
        return Response.ok(product)
            .header("ETag", WarehouseProductEtag.of(product.updatedAt()))
            .build();
    }

    /**
     * Stock disponible en vivo (form de retiro): la validación AUTORITATIVA
     * sigue siendo la del POST/PUT del retiro en transacción (409 WH-001).
     */
    @GET
    @Path("/{id}/stock")
    @RolesAllowed({"admin", "general_manager", "operations_manager", "finance_manager", "warehouse_keeper"})
    public WarehouseProductStockResponse getProductStock(@PathParam("id") Integer id) {
        return warehouseProductService.getStock(id);
    }
}
