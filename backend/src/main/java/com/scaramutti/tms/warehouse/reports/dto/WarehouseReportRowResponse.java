package com.scaramutti.tms.warehouse.reports.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;

/**
 * Fila agregada de un reporte de almacen. RN-WH7 (bi-moneda SIN conversion):
 * {@code amountPEN} y {@code amountUSD} acumulan por separado segun la moneda
 * registrada (catalogo activo = PEN + USD); nunca se convierte una en la otra.
 *
 * <p>El backend compone {@code label}/{@code detail} en es-PE (el frontend no
 * arma texto de negocio, mismo criterio que el kardex).
 */
public record WarehouseReportRowResponse(
    @Schema(description = "Etiqueta del grupo (ej: 'Tracto ABC123', 'Semana del 22/06', producto, proveedor)", example = "Tracto ABC123")
    String label,

    @Schema(description = "Dato secundario: unidad de medida en BY_PRODUCT, fecha ISO del lunes en BY_PERIOD; null en los demas", example = "2026-06-22")
    String detail,

    @Schema(description = "Nro de movimientos, o unidades retiradas en BY_PRODUCT", example = "5")
    BigDecimal count,

    @Schema(description = "Valorizacion de referencia en PEN (salidas: ultimo precio de compra del producto)", example = "125.00")
    BigDecimal amountPEN,

    @Schema(description = "Valorizacion de referencia en USD", example = "0")
    BigDecimal amountUSD
) {}
