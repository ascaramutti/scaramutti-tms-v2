package com.scaramutti.tms.users.model;

/**
 * Que clase de cambio registra una fila del rastro de las cuentas: el dominio que la columna cierra
 * con su restriccion, guardado por su nombre.
 */
public enum UserAuditChangeType {
    CREATED,
    DEACTIVATED,
    REACTIVATED,
    PASSWORD_RESET,
    PASSWORD_CHANGED
}
