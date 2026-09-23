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
        "La ficha de conductor no corresponde con el cargo elegido"),
    /**
     * El minimo del motivo se mide en el SERVICIO y no en el borde, y no es un descuido: con el
     * minimo en la anotacion del cuerpo, un motivo corto saldria como error de validacion generico
     * y este codigo no podria emitirse nunca. Ademas el motivo es opcional cuando el documento no
     * cambia, asi que una regla del borde lo exigiria tambien donde no hace falta.
     */
    DOCUMENT_CHANGE_REASON_REQUIRED ("WRK-009", 400, "Bad Request",
        "Cambiar el numero de documento exige un motivo de al menos 10 caracteres"),
    // WRK-010 no esta y no es un olvido: es el de desactivarse a uno mismo, y nace con el
    // endpoint de cambio de estado. Un hueco en un catalogo de errores normalmente es una guarda
    // que se cayo, asi que conviene que este dicho.
    ROLE_CANNOT_LOGIN_WITH_USER ("WRK-011", 400, "Bad Request",
        "Ese cargo no inicia sesion y el trabajador tiene usuario del sistema"),
    /**
     * Detalle CONSTANTE, sin cargo ni nivel, por el mismo motivo que el codigo de rango.
     *
     * <p>Cubre las DOS filas que una edicion mueve: el cargo del trabajador y el de su cuenta del
     * sistema. Mirar solo la primera dejaba pasar el cuerpo que reenvia el cargo del trabajador
     * sin cambios mientras la cascada movia el de la cuenta, que es la que da los permisos.
     */
    SELF_ROLE_CHANGE         ("WRK-012", 403, "Forbidden",
        "No puedes cambiar tu propio cargo"),
    /**
     * Conflicto TRANSITORIO, no un error del servidor: otra operacion toco la misma fila y el
     * motor aborto esta. Cubre los tres casos de "no pude tomar la fila" (espera agotada, abrazo
     * mortal y fallo de serializacion), no solo el primero. Quien lo recibe reintenta.
     */
    WORKER_LOCKED            ("WRK-013", 409, "Conflict",
        "El trabajador esta siendo modificado por otra operacion");

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
