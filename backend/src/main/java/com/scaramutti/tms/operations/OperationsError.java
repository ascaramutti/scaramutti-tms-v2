package com.scaramutti.tms.operations;

import com.scaramutti.tms.shared.exception.ApiError;

/**
 * Catalogo de errores del modulo Operaciones con codigos trazables (OPS-XXX). Los codigos se
 * agregan a medida que cada endpoint los necesita, tomando el numero que el contrato le reservo
 * (por eso el primero en implementarse no es OPS-001). Un codigo ya usado NO se recodifica:
 * cada caso nuevo toma el siguiente libre.
 */
public enum OperationsError implements ApiError {

    /** El viaje pedido no existe. Espejo de los 404 de cotizaciones y almacen. */
    SERVICE_NOT_FOUND("OPS-005", 404, "Resource not found",
        "El servicio indicado no existe"),

    /**
     * Alta repetida en cuestion de segundos (doble-click o reintento del navegador): mismo
     * usuario, mismo cliente y misma ruta dentro de la ventana configurada. NO es una
     * restriccion de unicidad — dos viajes iguales separados en el tiempo son legitimos —,
     * y por eso lleva codigo propio: el cliente distingue este 409 benigno de un conflicto real.
     */
    DUPLICATE_SERVICE_DETECTED("OPS-007", 409, "Conflict",
        "Se detectó un servicio idéntico creado hace menos de 30 segundos"),
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
