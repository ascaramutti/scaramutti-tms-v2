package com.scaramutti.tms.warehouse.withdrawal.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;

/**
 * Body del POST /warehouse/withdrawals (RN-WH2): {@code receivedByWorkerId} SIEMPRE
 * obligatorio; unidad destino OPCIONAL y a lo sumo UNA ({@code tractorId} | {@code trailerId}
 * | {@code escortVehicleId}, subtipos disyuntos; más de una la rechaza el service con 400
 * WH-005). {@code withdrawnAt} lo asigna el server.
 */
public record WarehouseWithdrawalRequest(
    @Schema(example = "12") @NotNull Integer productId,
    @Schema(example = "2") @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 10, fraction = 2) BigDecimal quantity,
    @Schema(example = "8") @NotNull Integer receivedByWorkerId,
    @Schema(example = "5", nullable = true) Integer tractorId,
    @Schema(nullable = true) Integer trailerId,
    @Schema(nullable = true) Integer escortVehicleId,
    @Schema(nullable = true) String observations
) {}
