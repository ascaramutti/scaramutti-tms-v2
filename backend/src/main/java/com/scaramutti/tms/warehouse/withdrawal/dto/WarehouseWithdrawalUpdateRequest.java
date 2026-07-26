package com.scaramutti.tms.warehouse.withdrawal.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;

/**
 * Body del PUT /warehouse/withdrawals/{id}. SIN {@code productId}: el producto es INMUTABLE
 * (RN-WH4, producto equivocado = anular y crear otro). La unidad de flota se REEMPLAZA: a lo
 * sumo una, los tres campos {@code null} la quitan. {@code reason} (>= 10) va a
 * {@code almacen.audit_logs}.
 */
public record WarehouseWithdrawalUpdateRequest(
    @Schema(example = "7") @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 10, fraction = 2) BigDecimal quantity,
    @Schema(example = "8") @NotNull Integer receivedByWorkerId,
    @Schema(example = "5", nullable = true) Integer tractorId,
    @Schema(nullable = true) Integer trailerId,
    @Schema(nullable = true) Integer escortVehicleId,
    @Schema(nullable = true) String observations,
    @Schema(example = "Se pidieron 10 filtros pero el mantenimiento uso 7; se devuelven 3")
    @NotBlank @Size(min = 10, max = 500) String reason
) {}
