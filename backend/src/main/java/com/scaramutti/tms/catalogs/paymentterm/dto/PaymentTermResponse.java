package com.scaramutti.tms.catalogs.paymentterm.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

public record PaymentTermResponse(
    @Schema(description = "ID interno", example = "1")
    Integer id,

    @Schema(description = "Nombre del término de pago", example = "30 días", maxLength = 100)
    String name,

    @Schema(description = "Cantidad de días de plazo (0 = pago inmediato)", example = "30", minimum = "0")
    Integer days,

    @Schema(description = "Indica si el término de pago está activo", example = "true")
    Boolean isActive
) {}
