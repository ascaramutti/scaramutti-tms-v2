package com.scaramutti.tms.workers;

import com.scaramutti.tms.shared.exception.ApiError;

/**
 * Catalogo de errores del modulo Workers con codigos trazables (WRK-XXX).
 * Centraliza status/code/title/detail para evitar magic strings en services y resources.
 */
public enum WorkersError implements ApiError {

    NOT_FOUND                ("WRK-001", 404, "Not Found",
        "Trabajador no encontrado"),
    DUPLICATE_DOCUMENT       ("WRK-002", 409, "Conflict",
        "Ya existe un trabajador con ese numero de documento"),
    DOCUMENT_TYPE_INVALID    ("WRK-003", 400, "Bad Request",
        "El tipo de documento no existe o no esta vigente"),
    DOCUMENT_NUMBER_INVALID  ("WRK-004", 400, "Bad Request",
        "El numero de documento no cumple el formato del tipo elegido"),
    ROLE_INVALID             ("WRK-005", 400, "Bad Request",
        "El cargo no existe o no esta vigente"),
    /**
     * El detalle es una CONSTANTE sin rol, sin nivel y sin id, ni del actor ni del objetivo: el
     * cuerpo del rechazo no distingue POR QUE el cargo quedo fuera de alcance. Que este a la
     * par o por encima se deduce igual del status, y eso no es secreto: el catalogo de cargos
     * publica el nivel de cada uno a estos mismos cuatro roles.
     */
    ROLE_OUT_OF_RANK         ("WRK-006", 403, "Forbidden",
        "No puedes dar de alta ni modificar trabajadores de ese cargo"),
    DUPLICATE_LICENSE        ("WRK-007", 409, "Conflict",
        "Ya existe una ficha de conductor con ese numero de licencia"),
    DRIVER_PROFILE_MISMATCH  ("WRK-008", 400, "Bad Request",
        "La ficha de conductor no corresponde con el cargo elegido");

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
