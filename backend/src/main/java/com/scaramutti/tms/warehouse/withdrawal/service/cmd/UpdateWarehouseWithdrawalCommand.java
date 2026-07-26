package com.scaramutti.tms.warehouse.withdrawal.service.cmd;

import java.math.BigDecimal;

/**
 * Edicion de un retiro, ya normalizada por la capa REST. Lleva el {@code withdrawalId} del
 * path y el {@code ifMatch} del header. SIN {@code productId} (inmutable). La unidad de flota
 * se reemplaza (a lo sumo uno de los tres ids no null; los tres null la quitan).
 */
public record UpdateWarehouseWithdrawalCommand(
    Integer withdrawalId,
    String ifMatch,
    BigDecimal quantity,
    Integer receivedByWorkerId,
    Integer tractorId,
    Integer trailerId,
    Integer escortVehicleId,
    String observations,
    String reason
) {}
