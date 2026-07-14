package com.scaramutti.tms.warehouse.purchaseinvoice.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Moneda embebida en el detalle de una entrada (RN-WH7: la factura fija su moneda,
 * sin conversión). El listado solo trae el {@code currencyCode}.
 */
public record WarehouseInvoiceCurrencyRef(
    @Schema(example = "2") Integer id,
    @Schema(example = "PEN") String code,
    @Schema(example = "S/") String symbol
) {}
