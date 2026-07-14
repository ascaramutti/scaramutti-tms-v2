package com.scaramutti.tms.warehouse.model;

/**
 * Tipo de cambio registrado en {@code almacen.audit_logs} (columna + CHECK, V002).
 * {@code CREATED} queda disponible para el futuro; la edicion escribe {@code FIELD_EDIT}
 * (una fila por campo cambiado) y la anulacion escribe {@code CANCELLED} (una fila).
 */
public enum AuditChangeType {
    CREATED,
    FIELD_EDIT,
    CANCELLED
}
