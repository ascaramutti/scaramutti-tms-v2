package com.scaramutti.tms.quotations.dto.embedded;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Vista resumida de la moneda para embeber en {@code QuotationResponse}.
 * Subset intencional de {@code CurrencyResponse}: solo id + code (ISO) + symbol.
 *
 * <p>NO incluye {@code name} ni {@code isActive}: no aportan en el contexto
 * de cotizacion. El frontend muestra la moneda usando {@code code} y {@code symbol}.
 */
public record QuotationCurrencySummary(

    @Schema(description = "ID interno", example = "1")
    Integer id,

    @Schema(description = "Codigo ISO 4217", example = "PEN")
    String code,

    @Schema(description = "Simbolo monetario", example = "S/")
    String symbol
) {}
