package com.scaramutti.tms.quotations.dto.embedded;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Vista resumida del termino de pago para embeber en {@code QuotationResponse}.
 * Subset intencional de {@code PaymentTermResponse}: id + name + days.
 *
 * <p>NO incluye {@code isActive}: no aporta en el contexto de cotizacion.
 */
public record QuotationPaymentTermSummary(

    @Schema(description = "ID interno", example = "1")
    Integer id,

    @Schema(description = "Nombre del termino de pago", example = "30 dias")
    String name,

    @Schema(description = "Dias de plazo (0 = contado)", example = "30")
    Integer days
) {}
