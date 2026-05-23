package com.scaramutti.tms.clients;

import com.scaramutti.tms.shared.exception.ApiError;

/**
 * Catalogo de errores del modulo Clients con codigos trazables (CLI-XXX).
 * Centraliza status/code/title/detail para evitar magic strings en services y resources.
 */
public enum ClientsError implements ApiError {

    DUPLICATE_RUC  ("CLI-001", 409, "Conflict",
        "Ya existe un cliente con el RUC indicado"),
    DUPLICATE_NAME ("CLI-002", 409, "Conflict",
        "Ya existe un cliente con el nombre indicado"),
    NOT_FOUND      ("CLI-003", 404, "Not Found",
        "Cliente no encontrado");

    private final String code;
    private final int status;
    private final String title;
    private final String detail;

    ClientsError(String code, int status, String title, String detail) {
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
