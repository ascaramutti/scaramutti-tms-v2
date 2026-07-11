package com.scaramutti.tms.warehouse.kardex.model;

/**
 * Tipo de movimiento del kardex, espejo de la columna {@code movement_type}
 * de la VIEW {@code almacen.stock_movements} (R__almacen_stock_views.sql).
 *
 * <p>Los valores están en ESPAÑOL a propósito: son los literales del DDL
 * congelado de la VIEW ({@code 'APERTURA'}, {@code 'ENTRADA'}, {@code 'SALIDA'}),
 * no una traducción del enum — {@link #name()} debe calzar 1:1 con el texto
 * que devuelve la columna nativa para que {@link #valueOf(String)} funcione
 * sin una tabla de mapeo intermedia.
 */
public enum WarehouseKardexMovementType {

    /** Corte inicial de inventario (opening_balances). Signo +. */
    APERTURA,

    /** Ítem de una factura de compra ACTIVA (purchase_invoice_items). Signo +. */
    ENTRADA,

    /** Retiro ACTIVO (withdrawals). Signo -. */
    SALIDA
}
