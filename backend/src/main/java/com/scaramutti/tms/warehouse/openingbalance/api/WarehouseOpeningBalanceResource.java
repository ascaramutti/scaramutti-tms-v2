package com.scaramutti.tms.warehouse.openingbalance.api;

import com.scaramutti.tms.shared.dto.PageResponse;
import com.scaramutti.tms.warehouse.openingbalance.dto.WarehouseOpeningBalanceRequest;
import com.scaramutti.tms.warehouse.openingbalance.dto.WarehouseOpeningBalanceResponse;
import com.scaramutti.tms.warehouse.openingbalance.mapper.WarehouseOpeningBalanceResourceMapper;
import com.scaramutti.tms.warehouse.openingbalance.service.WarehouseOpeningBalanceService;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.jboss.resteasy.reactive.ResponseStatus;

@Path("/warehouse/opening-balances")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class WarehouseOpeningBalanceResource {

    @Inject WarehouseOpeningBalanceService warehouseOpeningBalanceService;
    @Inject WarehouseOpeningBalanceResourceMapper warehouseOpeningBalanceResourceMapper;

    @GET
    @RolesAllowed({"admin", "general_manager", "operations_manager", "finance_manager", "warehouse_keeper"})
    public PageResponse<WarehouseOpeningBalanceResponse> listOpeningBalances(
        @QueryParam("productId")                                       Integer productId,
        @QueryParam("page") @DefaultValue("0")  @Min(0)                 int page,
        @QueryParam("size") @DefaultValue("20") @Min(1) @Max(100)       int size
    ) {
        return warehouseOpeningBalanceService.listOpeningBalances(
            warehouseOpeningBalanceResourceMapper.toListWarehouseOpeningBalancesQuery(productId, page, size)
        );
    }

    /**
     * Registrar el corte inicial queda restringido a `admin`: fija la línea base del
     * kardex de un producto, es inmutable y no tiene anulación, así que un error solo
     * se corrige en base de datos. Consultarlo sigue abierto a los roles del módulo.
     */
    @POST
    @RolesAllowed("admin")
    @ResponseStatus(201)
    public WarehouseOpeningBalanceResponse createOpeningBalance(
        @Valid @NotNull WarehouseOpeningBalanceRequest warehouseOpeningBalanceRequest
    ) {
        return warehouseOpeningBalanceService.createOpeningBalance(
            warehouseOpeningBalanceResourceMapper.toCreateWarehouseOpeningBalanceCommand(warehouseOpeningBalanceRequest)
        );
    }
}
