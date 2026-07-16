package com.scaramutti.tms.warehouse.reports.dto;

import com.scaramutti.tms.warehouse.reports.model.WarehouseReportCut;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Reporte agregado de almacen por corte (GET /warehouse/reports). Devuelve las
 * filas del corte pedido mas los totales por moneda (RN-WH7, sin conversion).
 * {@code rows} nunca es null: lista vacia si no hubo movimientos en el rango.
 */
public record WarehouseReportResponse(
    @Schema(description = "Corte aplicado")
    WarehouseReportCut cut,

    @Schema(description = "Desde (inclusive, America/Lima)", example = "2026-07-01")
    LocalDate dateFrom,

    @Schema(description = "Hasta (inclusive, America/Lima)", example = "2026-07-31")
    LocalDate dateTo,

    @Schema(description = "Filas agregadas. BY_PERIOD cronologico ASC; el resto por monto total DESC")
    List<WarehouseReportRowResponse> rows,

    @Schema(description = "Total en PEN (suma de las filas)", example = "125.00")
    BigDecimal totalPEN,

    @Schema(description = "Total en USD (suma de las filas)", example = "0")
    BigDecimal totalUSD,

    @Schema(description = "Total de la columna count (suma de las filas)", example = "5")
    BigDecimal totalCount
) {}
