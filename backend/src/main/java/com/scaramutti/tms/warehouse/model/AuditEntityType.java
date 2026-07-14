package com.scaramutti.tms.warehouse.model;

/**
 * Tipo de entidad auditada en {@code almacen.audit_logs} (columna + CHECK, V002).
 * Enum de dominio (convencion: enums de dominio en {@code <module>/model/}). A9
 * estrena {@code PURCHASE_INVOICE}; los demas valores los usaran los retiros
 * (A10/A11) y, si hiciera falta, productos/apertura.
 */
public enum AuditEntityType {
    PURCHASE_INVOICE,
    WITHDRAWAL,
    PRODUCT,
    OPENING_BALANCE
}
