package com.scaramutti.tms.warehouse.purchaseinvoice.dto;

import com.scaramutti.tms.warehouse.product.dto.WarehouseProductSummary;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;

/**
 * Línea de una entrada en el response. {@code subtotal} = {@code quantity * unitPrice},
 * derivado en código (no persistido).
 */
public record WarehouseInvoiceItemResponse(
    @Schema(example = "1") Integer id,
    WarehouseProductSummary product,
    @Schema(example = "10") BigDecimal quantity,
    @Schema(example = "45.00") BigDecimal unitPrice,
    @Schema(example = "450.00") BigDecimal subtotal
) {}
