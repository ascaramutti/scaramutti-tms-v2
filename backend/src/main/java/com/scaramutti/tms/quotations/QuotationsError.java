package com.scaramutti.tms.quotations;

import com.scaramutti.tms.shared.exception.ApiError;

/**
 * Catalogo de errores del modulo Quotations con codigos trazables (QUO-XXX).
 *
 *  - QUO-001: race condition extrema sobre el UNIQUE constraint del `code`.
 *    En teoria no ocurre por el advisory lock por anio dentro de la tx,
 *    pero capturamos la ConstraintViolationException como defense-in-depth.
 *
 *  - QUO-002: duplicate detected (anti doble-click backend-side). Se valida
 *    si llega un POST con mismo clientId + mismo set de serviceTypeIds +
 *    mismo created_by dentro de los ultimos 30 segundos.
 */
public enum QuotationsError implements ApiError {

    DUPLICATE_CODE     ("QUO-001", 409, "Conflict",
        "El codigo de cotizacion ya esta en uso, reintente"),
    DUPLICATE_DETECTED ("QUO-002", 409, "Conflict",
        "Se detecto una cotizacion identica creada hace menos de 30 segundos");

    private final String code;
    private final int status;
    private final String title;
    private final String detail;

    QuotationsError(String code, int status, String title, String detail) {
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
