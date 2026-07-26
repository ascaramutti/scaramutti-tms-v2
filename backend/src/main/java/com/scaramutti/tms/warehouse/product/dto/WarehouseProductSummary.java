package com.scaramutti.tms.warehouse.product.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Referencia mínima a un producto para embeber en otros recursos (aperturas,
 * ítems de factura, retiros). Compartido a propósito (no anidado por
 * recurso): el contrato lo diseña con la misma forma para sus 3 consumidores,
 * así que se crea compartido desde el 1er uso en vez de dejar copias que
 * puedan divergir.
 */
public record WarehouseProductSummary(
    @Schema(example = "1") Integer id,
    @Schema(example = "PRO-0012", maxLength = 30, nullable = true) String code,
    @Schema(example = "Filtro de aceite XYZ", maxLength = 200) String name,
    @Schema(description = "Código de la unidad de medida (para pintar cantidades)", example = "UND", maxLength = 10) String unitCode
) {}
