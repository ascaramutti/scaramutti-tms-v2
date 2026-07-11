package com.scaramutti.tms.warehouse.openingbalance.dto;

import com.scaramutti.tms.auth.dto.UserResponse;
import com.scaramutti.tms.warehouse.product.dto.WarehouseProductSummary;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record WarehouseOpeningBalanceResponse(
    @Schema(description = "ID interno", example = "1")
    Integer id,

    @Schema(description = "Producto al que corresponde la apertura")
    WarehouseProductSummary product,

    @Schema(description = "Cantidad inicial registrada", example = "100", minimum = "0")
    BigDecimal quantity,

    @Schema(description = "Observaciones", nullable = true)
    String observations,

    @Schema(description = "Usuario que registró la apertura")
    UserResponse registeredBy,

    @Schema(description = "Fecha de registro")
    OffsetDateTime registeredAt
) {}
