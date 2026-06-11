package com.scaramutti.tms.cargotypes;

import com.scaramutti.tms.shared.exception.ApiError;

/**
 * Catalogo de errores del modulo CargoTypes con codigos trazables (CGT-XXX).
 * Patron identico a ClientsError. Por ahora solo 1 codigo: name UNIQUE.
 * (No hay equivalente al CLI-001 sobre RUC — cargo types solo tienen un campo unico).
 */
public enum CargoTypesError implements ApiError {

    DUPLICATE_NAME("CGT-001", 409, "Conflict",
        "Ya existe un tipo de carga con el nombre indicado");

    private final String code;
    private final int status;
    private final String title;
    private final String detail;

    CargoTypesError(String code, int status, String title, String detail) {
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
