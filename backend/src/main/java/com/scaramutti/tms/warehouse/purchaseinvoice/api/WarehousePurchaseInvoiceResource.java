package com.scaramutti.tms.warehouse.purchaseinvoice.api;

import com.scaramutti.tms.shared.dto.PageResponse;
import com.scaramutti.tms.warehouse.model.WarehouseRecordStatus;
import com.scaramutti.tms.warehouse.purchaseinvoice.dto.WarehousePurchaseInvoiceRequest;
import com.scaramutti.tms.warehouse.purchaseinvoice.dto.WarehousePurchaseInvoiceResponse;
import com.scaramutti.tms.warehouse.purchaseinvoice.dto.WarehousePurchaseInvoiceSummary;
import com.scaramutti.tms.warehouse.purchaseinvoice.mapper.WarehousePurchaseInvoiceResourceMapper;
import com.scaramutti.tms.warehouse.purchaseinvoice.service.WarehousePurchaseInvoiceService;
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
import jakarta.ws.rs.core.Response;
import java.time.LocalDate;

@Path("/warehouse/purchase-invoices")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class WarehousePurchaseInvoiceResource {

    @Inject WarehousePurchaseInvoiceService warehousePurchaseInvoiceService;
    @Inject WarehousePurchaseInvoiceResourceMapper warehousePurchaseInvoiceResourceMapper;

    @GET
    @RolesAllowed({"admin", "general_manager", "operations_manager", "finance_manager", "warehouse_keeper"})
    public PageResponse<WarehousePurchaseInvoiceSummary> listPurchaseInvoices(
        @QueryParam("q")          @Size(min = 3, max = 200)             String q,
        @QueryParam("supplierId")                                       Integer supplierId,
        @QueryParam("status")                                           WarehouseRecordStatus status,
        @QueryParam("dateFrom")                                         LocalDate dateFrom,
        @QueryParam("dateTo")                                           LocalDate dateTo,
        @QueryParam("page") @DefaultValue("0")  @Min(0)                 int page,
        @QueryParam("size") @DefaultValue("20") @Min(1) @Max(100)       int size
    ) {
        return warehousePurchaseInvoiceService.listPurchaseInvoices(
            warehousePurchaseInvoiceResourceMapper.toListWarehousePurchaseInvoicesQuery(
                q, supplierId, status, dateFrom, dateTo, page, size)
        );
    }

    @POST
    @RolesAllowed({"admin", "general_manager", "operations_manager", "finance_manager", "warehouse_keeper"})
    public Response createPurchaseInvoice(
        @Valid @NotNull WarehousePurchaseInvoiceRequest warehousePurchaseInvoiceRequest
    ) {
        WarehousePurchaseInvoiceResponse response = warehousePurchaseInvoiceService.createPurchaseInvoice(
            warehousePurchaseInvoiceResourceMapper.toCreateWarehousePurchaseInvoiceCommand(warehousePurchaseInvoiceRequest)
        );
        // ETag = updatedAt (la "versión") entre comillas, formato opaco para el
        // If-Match del PUT/cancel (A9). Inline: único uso en A8; cuando A9 sume el
        // verify() del If-Match (2° uso) se extrae a un WarehousePurchaseInvoiceEtag.
        return Response.status(Response.Status.CREATED)
            .header("ETag", "\"" + response.updatedAt().toString() + "\"")
            .entity(response)
            .build();
    }
}
