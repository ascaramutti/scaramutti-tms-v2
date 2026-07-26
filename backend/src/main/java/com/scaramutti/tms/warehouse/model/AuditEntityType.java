package com.scaramutti.tms.warehouse.model;

/**
 * Tipo de entidad auditada en {@code almacen.audit_logs} (columna + CHECK, V002).
 * Enum de dominio (convencion: enums de dominio en {@code <module>/model/}). Las
 * entradas (facturas) usan {@code PURCHASE_INVOICE}; los retiros usan {@code WITHDRAWAL};
 * {@code PRODUCT}/{@code OPENING_BALANCE} quedan disponibles si hiciera falta auditarlos.
 */
public enum AuditEntityType {
    PURCHASE_INVOICE,
    WITHDRAWAL,
    PRODUCT,
    OPENING_BALANCE
}
