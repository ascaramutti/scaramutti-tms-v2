package com.scaramutti.tms.warehouse.reports.api;

import com.scaramutti.tms.warehouse.reports.dto.WarehouseReportResponse;
import com.scaramutti.tms.warehouse.reports.mapper.WarehouseReportResourceMapper;
import com.scaramutti.tms.warehouse.reports.model.WarehouseReportCut;
import com.scaramutti.tms.warehouse.reports.service.WarehouseReportService;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import java.time.LocalDate;

/**
 * Reporte agregado de almacen por corte (GET /warehouse/reports). Los 4 params
 * llegan tipados; {@code cut} que no matchee el enum produce 404 (RESTEasy).
 * La validacion cross-field {@code dateFrom > dateTo} (400 COM-001) la hace el
 * service, no aca (mismo criterio del resto del modulo).
 */
@Path("/warehouse/reports")
@Produces(MediaType.APPLICATION_JSON)
public class WarehouseReportResource {

    @Inject WarehouseReportService warehouseReportService;
    @Inject WarehouseReportResourceMapper warehouseReportResourceMapper;

    @GET
    @RolesAllowed({"admin", "general_manager", "operations_manager", "finance_manager", "warehouse_keeper"})
    public WarehouseReportResponse getReport(
        @QueryParam("cut")      @NotNull WarehouseReportCut cut,
        @QueryParam("dateFrom") @NotNull LocalDate dateFrom,
        @QueryParam("dateTo")   @NotNull LocalDate dateTo
    ) {
        return warehouseReportService.getReport(
            warehouseReportResourceMapper.toGetWarehouseReportQuery(cut, dateFrom, dateTo)
        );
    }
}
