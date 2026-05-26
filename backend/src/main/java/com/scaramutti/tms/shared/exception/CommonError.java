package com.scaramutti.tms.shared.exception;

/**
 * Errores comunes (transversales) usados por handlers compartidos.
 * Errores especificos de un modulo viven en su propio enum (ej: AuthError).
 */
public enum CommonError implements ApiError {

    VALIDATION_FAILED("COM-001", 400, "Validation failed",
        "La solicitud contiene errores de validación"),
    FORBIDDEN        ("COM-003", 403, "Forbidden",
        "No tiene permisos para acceder a este recurso"),
    INTERNAL_ERROR   ("COM-500", 500, "Internal Server Error",
        "Error interno del servidor");

    private final String code;
    private final int status;
    private final String title;
    private final String detail;

    CommonError(String code, int status, String title, String detail) {
        this.code = code;
        this.status = status;
        this.title = title;
        this.detail = detail;
    }

    @Override public String code()   { return code; }
    @Override public int    status() { return status; }
    @Override public String title()  { return title; }
    @Override public String detail() { return detail; }
}
