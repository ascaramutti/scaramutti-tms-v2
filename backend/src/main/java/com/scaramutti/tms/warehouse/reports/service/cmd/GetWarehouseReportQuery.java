package com.scaramutti.tms.warehouse.reports.service.cmd;

import com.scaramutti.tms.warehouse.reports.model.WarehouseReportCut;

import java.time.LocalDate;

/**
 * Parametros del reporte de almacen, agrupados desde la capa REST (mismo criterio
 * que {@code GetWarehouseKardexQuery}). {@code dateFrom}/{@code dateTo} son
 * inclusivos en America/Lima; la validacion {@code dateFrom > dateTo} (COM-001)
 * la hace el service, no el Resource (cross-field).
 */
public record GetWarehouseReportQuery(
    WarehouseReportCut cut,
    LocalDate dateFrom,
    LocalDate dateTo
) {}
