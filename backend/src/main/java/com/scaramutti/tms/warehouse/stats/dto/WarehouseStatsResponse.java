package com.scaramutti.tms.warehouse.stats.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * KPIs del strip de Existencias (GET /warehouse/stats). Contadores del mes
 * calendario EN CURSO en America/Lima; solo registros ACTIVOS. Nada se persiste:
 * se derivan en el momento de VIEWs/tablas (mismo criterio read-only del kardex).
 *
 * <p>{@code entriesThisMonth}/{@code withdrawalsThisMonth} cuentan por fecha de
 * REGISTRO ({@code created_at} de la factura, {@code withdrawn_at} del retiro), no
 * por la fecha de negocio de la factura ({@code invoice_date}): es la actividad
 * del mes en curso. El reporte {@code BY_SUPPLIER} si usa {@code invoice_date}
 * (asimetria deliberada, ver contrato ops 27-28).
 */
public record WarehouseStatsResponse(
    @Schema(description = "Productos activos", example = "42")
    int activeProducts,

    @Schema(description = "Productos activos con stock < minStock (RN-WH11)", example = "3")
    int lowStockCount,

    @Schema(description = "Facturas ACTIVAS registradas este mes (createdAt)", example = "7")
    int entriesThisMonth,

    @Schema(description = "Retiros ACTIVOS registrados este mes (withdrawnAt)", example = "12")
    int withdrawalsThisMonth
) {}
