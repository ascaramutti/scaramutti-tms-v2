package com.scaramutti.tms.workers;

import com.scaramutti.tms.shared.exception.ApiError;

/**
 * Catalogo de errores del modulo Workers con codigos trazables (WRK-XXX).
 * Centraliza status/code/title/detail para evitar magic strings en services y resources.
 */
public enum WorkersError implements ApiError {

    NOT_FOUND ("WRK-001", 404, "Not Found",
        "Trabajador no encontrado");

    private final String code;
    private final int status;
    private final String title;
    private final String detail;

    WorkersError(String code, int status, String title, String detail) {
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
