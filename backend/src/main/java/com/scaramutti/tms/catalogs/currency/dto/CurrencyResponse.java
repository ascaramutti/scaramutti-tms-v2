package com.scaramutti.tms.catalogs.currency.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

public record CurrencyResponse(
    @Schema(description = "ID interno", example = "1")
    Integer id,

    @Schema(description = "Código ISO 4217", example = "PEN", pattern = "^[A-Z]{3}$")
    String code,

    @Schema(description = "Símbolo monetario", example = "S/", maxLength = 5)
    String symbol,

    @Schema(description = "Nombre completo (opcional)", example = "Sol Peruano", nullable = true)
    String name,

    @Schema(description = "Indica si la moneda está activa", example = "true")
    Boolean isActive
) {}
