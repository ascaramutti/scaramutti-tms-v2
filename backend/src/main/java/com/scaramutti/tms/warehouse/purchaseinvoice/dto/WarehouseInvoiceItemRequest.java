package com.scaramutti.tms.warehouse.purchaseinvoice.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;

/**
 * Línea de una entrada: producto + cantidad + precio unitario (en la moneda de la
 * factura). El producto se referencia por id (crear-al-vuelo = POST /warehouse/products
 * previo, decisión D-3). {@code unitPrice} puede ser 0 (bonificación).
 *
 * <p>{@code @Digits(fraction=2)}: las columnas son {@code NUMERIC(12,2)}, así que un
 * input con más de 2 decimales lo redondearía la BD en silencio (y el response del
 * POST devolvería el valor sin redondear); se rechaza con 400 en el borde.
 */
public record WarehouseInvoiceItemRequest(
    @Schema(example = "12") @NotNull Integer productId,
    @Schema(example = "10") @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 10, fraction = 2) BigDecimal quantity,
    @Schema(example = "45.00") @NotNull @DecimalMin("0") @Digits(integer = 10, fraction = 2) BigDecimal unitPrice
) {}
