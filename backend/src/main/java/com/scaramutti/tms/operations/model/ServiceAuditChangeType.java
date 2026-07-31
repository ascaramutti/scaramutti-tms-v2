package com.scaramutti.tms.operations.model;

/**
 * Tipo de cambio registrado en la auditoria del servicio
 * ({@code operaciones.service_audit_logs}). La auditoria es propia del modulo y no comparte
 * tabla con la de almacen: el shape es distinto (cuelga de un solo tipo de entidad y guarda
 * la justificacion de la edicion).
 */
public enum ServiceAuditChangeType {

    /** Alta del servicio. */
    CREATED,

    /** Asignacion de recursos. */
    ASSIGNMENT,

    /** Cambio de estado. */
    STATUS_CHANGE,

    /** Edicion de un campo, con su valor anterior y el nuevo. */
    FIELD_EDIT,

    /**
     * Historico del sistema anterior: el backend nuevo NUNCA lo escribe, pero el CHECK de la
     * BD lo admite porque la migracion de datos conserva esas filas literales.
     */
    ADMIN_UPDATE
}
