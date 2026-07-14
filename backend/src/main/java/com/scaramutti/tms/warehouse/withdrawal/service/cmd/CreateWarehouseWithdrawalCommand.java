package com.scaramutti.tms.warehouse.withdrawal.service.cmd;

import java.math.BigDecimal;

/**
 * Alta de un retiro, ya normalizada por la capa REST ({@code observations} con "" → null).
 * La unidad destino es a lo sumo una de las tres (las otras null).
 */
public record CreateWarehouseWithdrawalCommand(
    Integer productId,
    BigDecimal quantity,
    Integer receivedByWorkerId,
    Integer tractorId,
    Integer trailerId,
    Integer escortVehicleId,
    String observations
) {}
