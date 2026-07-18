package com.scaramutti.tms.warehouse.model;

/**
 * Estado de los registros anulables del módulo Almacén: entradas (facturas) y
 * retiros. Enum de dominio (convención: los enums de dominio van
 * en {@code <module>/model/}). {@code CANCELLED} = anulado con motivo/quién/cuándo
 * (CHECK de consistencia en BD, V002); no mueve stock/kardex/reportes pero queda
 * visible en los listados.
 *
 * <p>La entity guarda el valor como String (evita invertir la dependencia
 * {@code shared → warehouse}); este enum tipa el filtro de los listados y el campo
 * de los responses, tanto en entradas como en retiros.
 */
public enum WarehouseRecordStatus {
    ACTIVE,
    CANCELLED
}
