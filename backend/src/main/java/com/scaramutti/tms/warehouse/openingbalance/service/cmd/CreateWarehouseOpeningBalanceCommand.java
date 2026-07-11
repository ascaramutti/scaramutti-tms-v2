package com.scaramutti.tms.warehouse.openingbalance.service.cmd;

import java.math.BigDecimal;

/**
 * {@code observations} ya viaja trim()-eado y cadena vacía → null (el
 * ResourceMapper normaliza). {@code quantity} nunca es null (validado
 * {@code @NotNull} en el DTO) y puede ser 0.
 */
public record CreateWarehouseOpeningBalanceCommand(
    Integer productId,
    BigDecimal quantity,
    String observations
) {}
