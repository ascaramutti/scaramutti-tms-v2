package com.scaramutti.tms.warehouse;

import com.scaramutti.tms.shared.exception.ApiError;

/**
 * Catalogo de errores del modulo Almacen con codigos trazables (WH-XXX).
 * Vacio en A1 (solo DB); los codigos se agregan a medida que cada endpoint
 * (A2+) los necesita, segun almacen/10_CONTRATO_ALMACEN.md.
 */
public enum WarehouseError implements ApiError {
    ;

    private final String code;
    private final int status;
    private final String title;
    private final String detail;

    WarehouseError(String code, int status, String title, String detail) {
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
