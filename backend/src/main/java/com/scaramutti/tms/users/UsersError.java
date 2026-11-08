package com.scaramutti.tms.users;

import com.scaramutti.tms.shared.exception.ApiError;

/**
 * Catalogo de errores del modulo Usuarios con codigos trazables (USR-XXX). Cada codigo entra con el
 * PR que lo emite.
 */
public enum UsersError implements ApiError {

    NOT_FOUND         ("USR-001", 404, "Not Found",
        "Cuenta no encontrada"),
    WORKER_INACTIVE   ("USR-005", 409, "Conflict",
        "El trabajador está dado de baja"),
    ROLE_CANNOT_LOGIN ("USR-006", 400, "Bad Request",
        "El cargo del trabajador no inicia sesión"),
    OWN_ACCOUNT       ("USR-007", 403, "Forbidden",
        "No puedes hacer esto sobre tu propia cuenta"),
    OUT_OF_RANK       ("USR-008", 403, "Forbidden",
        "No puedes gestionar cuentas de ese cargo"),
    ACCOUNT_BUSY      ("USR-009", 409, "Conflict",
        "La cuenta está siendo modificada por otra operación");

    private final String code;
    private final int status;
    private final String title;
    private final String detail;

    UsersError(String code, int status, String title, String detail) {
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
