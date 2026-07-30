package com.scaramutti.tms.operations;

import com.scaramutti.tms.shared.exception.ApiError;

/**
 * Catalogo de errores del modulo Operaciones con codigos trazables (OPS-XXX).
 * Vacio en O1 (solo DB); los codigos se agregan a medida que cada endpoint
 * (O3+) los necesita, segun v1-operations/06_CONTRATO_OPERACIONES.md. Un codigo
 * ya usado no se recodifica: cada caso nuevo toma el siguiente libre.
 */
public enum OperationsError implements ApiError {
    ;

    private final String code;
    private final int status;
    private final String title;
    private final String detail;

    OperationsError(String code, int status, String title, String detail) {
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
