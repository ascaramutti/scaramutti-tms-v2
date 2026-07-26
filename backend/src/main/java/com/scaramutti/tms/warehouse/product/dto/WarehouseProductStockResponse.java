package com.scaramutti.tms.warehouse.product.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;

/**
 * Lectura puntual del stock disponible (form de retiro, validación en vivo).
 * {@code stock}/{@code lowStock} vienen de la VIEW {@code almacen.product_stock}
 * (RN-WH1/WH11); {@code minStock} de la entity. La validación AUTORITATIVA del
 * retiro sigue siendo la del POST/PUT en transacción (409 WH-001).
 */
public record WarehouseProductStockResponse(
    @Schema(description = "ID del producto", example = "1")
    Integer productId,

    @Schema(description = "Stock actual, de la VIEW almacen.product_stock", example = "12")
    BigDecimal stock,

    @Schema(description = "Umbral de reposición", example = "4")
    BigDecimal minStock,

    @Schema(description = "Derivado: stock < minStock (estricto)", example = "false")
    boolean lowStock
) {}
