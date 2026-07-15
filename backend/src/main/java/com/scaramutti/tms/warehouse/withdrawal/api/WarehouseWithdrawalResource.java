package com.scaramutti.tms.warehouse.withdrawal.api;

import com.scaramutti.tms.shared.dto.PageResponse;
import com.scaramutti.tms.shared.util.Etag;
import com.scaramutti.tms.warehouse.model.WarehouseRecordStatus;
import com.scaramutti.tms.warehouse.withdrawal.dto.WarehouseWithdrawalRequest;
import com.scaramutti.tms.warehouse.withdrawal.dto.WarehouseWithdrawalResponse;
import com.scaramutti.tms.warehouse.withdrawal.mapper.WarehouseWithdrawalResourceMapper;
import com.scaramutti.tms.warehouse.withdrawal.service.WarehouseWithdrawalService;
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
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.LocalDate;

@Path("/warehouse/withdrawals")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class WarehouseWithdrawalResource {

    @Inject WarehouseWithdrawalService warehouseWithdrawalService;
    @Inject WarehouseWithdrawalResourceMapper warehouseWithdrawalResourceMapper;

    @GET
    @RolesAllowed({"admin", "general_manager", "operations_manager", "finance_manager", "warehouse_keeper"})
    public PageResponse<WarehouseWithdrawalResponse> listWithdrawals(
        @QueryParam("productId")          Integer productId,
        @QueryParam("receivedByWorkerId") Integer receivedByWorkerId,
        @QueryParam("tractorId")          Integer tractorId,
        @QueryParam("trailerId")          Integer trailerId,
        @QueryParam("escortVehicleId")    Integer escortVehicleId,
        @QueryParam("status")             WarehouseRecordStatus status,
        @QueryParam("dateFrom")           LocalDate dateFrom,
        @QueryParam("dateTo")             LocalDate dateTo,
        @QueryParam("page") @DefaultValue("0")  @Min(0)           int page,
        @QueryParam("size") @DefaultValue("20") @Min(1) @Max(100) int size
    ) {
        return warehouseWithdrawalService.listWithdrawals(
            warehouseWithdrawalResourceMapper.toListWarehouseWithdrawalsQuery(
                productId, receivedByWorkerId, tractorId, trailerId, escortVehicleId,
                status, dateFrom, dateTo, page, size)
        );
    }

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

    @GET
    @Path("/{id}")
    @RolesAllowed({"admin", "general_manager", "operations_manager", "finance_manager", "warehouse_keeper"})
    public Response getWithdrawal(@PathParam("id") Integer id) {
        WarehouseWithdrawalResponse response = warehouseWithdrawalService.getWithdrawal(id);
        return Response.ok(response).header("ETag", Etag.of(response.updatedAt())).build();
    }
}
