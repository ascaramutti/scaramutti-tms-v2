package com.scaramutti.tms.warehouse.model;

/**
 * Tipo de cambio registrado en {@code almacen.audit_logs} (columna + CHECK, V002).
 * {@code CREATED} queda disponible para el futuro; A9 escribe {@code FIELD_EDIT} (una
 * fila por campo cambiado en la edicion) y {@code CANCELLED} (una fila por anulacion).
 */
public enum AuditChangeType {
    CREATED,
    FIELD_EDIT,
    CANCELLED
}
