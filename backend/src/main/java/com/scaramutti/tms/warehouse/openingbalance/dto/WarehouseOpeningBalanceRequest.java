package com.scaramutti.tms.warehouse.openingbalance.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;

public record WarehouseOpeningBalanceRequest(

    @Schema(description = "ID del producto", example = "1")
    @NotNull
    Integer productId,

    @Schema(description = "Cantidad inicial. Puede ser 0 (deja constancia del conteo)",
            example = "100", minimum = "0")
    @NotNull
    @DecimalMin("0")
    BigDecimal quantity,

    @Schema(description = "Observaciones (opcional)", nullable = true)
    String observations
) {}
