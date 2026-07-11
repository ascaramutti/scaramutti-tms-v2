package com.scaramutti.tms.warehouse.openingbalance.service.cmd;

/**
 * Query interna del listado de aperturas (GET /warehouse/opening-balances).
 * {@code productId}: null = todos los productos.
 */
public record ListWarehouseOpeningBalancesQuery(
    Integer productId, int page, int size
) {}
