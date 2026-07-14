package com.scaramutti.tms.warehouse.withdrawal.api;

import com.scaramutti.tms.shared.util.Etag;
import com.scaramutti.tms.warehouse.withdrawal.dto.WarehouseWithdrawalRequest;
import com.scaramutti.tms.warehouse.withdrawal.dto.WarehouseWithdrawalResponse;
import com.scaramutti.tms.warehouse.withdrawal.mapper.WarehouseWithdrawalResourceMapper;
import com.scaramutti.tms.warehouse.withdrawal.service.WarehouseWithdrawalService;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/warehouse/withdrawals")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class WarehouseWithdrawalResource {

    @Inject WarehouseWithdrawalService warehouseWithdrawalService;
    @Inject WarehouseWithdrawalResourceMapper warehouseWithdrawalResourceMapper;

    @POST
    @RolesAllowed({"admin", "general_manager", "operations_manager", "finance_manager", "warehouse_keeper"})
    public Response createWithdrawal(
        @Valid @NotNull WarehouseWithdrawalRequest warehouseWithdrawalRequest
    ) {
        WarehouseWithdrawalResponse response = warehouseWithdrawalService.createWithdrawal(
            warehouseWithdrawalResourceMapper.toCreateWarehouseWithdrawalCommand(warehouseWithdrawalRequest)
        );
        return Response.status(Response.Status.CREATED)
            .header("ETag", Etag.of(response.updatedAt()))
            .entity(response)
            .build();
    }
}
