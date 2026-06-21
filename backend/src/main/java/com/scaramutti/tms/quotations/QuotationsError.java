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
 *
 *  - QUO-003: cotizacion no encontrada por id. Lanzado por GET /quotations/{id}
 *    cuando el id del path no existe en BD. El caller pasa el id con
 *    {@code toException("La cotizacion con id " + id + " no existe")} para
 *    que el frontend muestre el detail directo sin armar el mensaje.
 *
 *  - QUO-004: intento de modificar un campo inmutable al editar (PUT /quotations/{id}).
 *    El {@code quotationType} y el {@code clientId} no pueden cambiar (la cotizacion
 *    pertenece a un cliente y su tipo define las reglas de items). El caller pasa el
 *    detalle especifico con {@code toException(...)}.
 *
 *  - QUO-005: transicion de estado no permitida (PATCH /quotations/{id}/status). La
 *    maquina de estados ({@code QuotationStatusMachine}, ADR-004) rechaza la transicion
 *    origen→destino. El caller pasa el detalle nombrando origen y destino concretos con
 *    {@code toException("No se puede pasar de REJECTED a SENT")}.
 *
 *  - QUO-006: intento de editar una cotizacion en estado terminal (PUT /quotations/{id}).
 *    Una cotizacion {@code ACCEPTED}/{@code REJECTED}/{@code EXPIRED} es inmutable. El
 *    caller pasa el detalle con el estado concreto via {@code toException(...)}.
 *
 *  - QUO-007: la seleccion de condiciones generales (POST/PUT /quotations) incluye una
 *    condicion DESACTIVADA (la escritura exige que todas las seleccionadas esten activas, ADR-010). El caller pasa el detalle nombrando la
 *    condicion via {@code toException(...)}. Duplicados / ids inexistentes en la seleccion
 *    NO usan este codigo: son 400 {@code COM-001} (validacion del request).
 */
public enum QuotationsError implements ApiError {

    DUPLICATE_CODE     ("QUO-001", 409, "Conflict",
        "El codigo de cotizacion ya esta en uso, reintente"),
    DUPLICATE_DETECTED ("QUO-002", 409, "Conflict",
        "Se detecto una cotizacion identica creada hace menos de 30 segundos"),
    NOT_FOUND          ("QUO-003", 404, "Not Found",
        "La cotizacion no existe"),
    IMMUTABLE_FIELD    ("QUO-004", 400, "Bad Request",
        "Un campo inmutable de la cotizacion no puede modificarse"),
    INVALID_TRANSITION ("QUO-005", 409, "Conflict",
        "La transicion de estado no esta permitida"),
    TERMINAL_NOT_EDITABLE ("QUO-006", 409, "Conflict",
        "No se puede editar una cotizacion en estado terminal"),
    INACTIVE_CONDITION_SELECTED ("QUO-007", 409, "Conflict",
        "La seleccion incluye una condicion desactivada");

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
