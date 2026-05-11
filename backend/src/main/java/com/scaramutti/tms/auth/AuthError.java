package com.scaramutti.tms.auth;

import com.scaramutti.tms.shared.exception.ApiError;

/**
 * Catalogo de errores del modulo Auth con codigos trazables (AUTH-XXX).
 * Centraliza status/code/title/detail para evitar magic strings en services y resources.
 */
public enum AuthError implements ApiError {

    INVALID_CREDENTIALS    ("AUTH-001", 401, "Invalid credentials",
        "Usuario o contraseña incorrectos"),
    USER_INACTIVE          ("AUTH-002", 401, "User inactive",
        "El usuario está desactivado"),
    REFRESH_TOKEN_INVALID  ("AUTH-003", 401, "Invalid refresh token",
        "Refresh token inválido o revocado"),
    WRONG_CURRENT_PASSWORD ("AUTH-004", 400, "Wrong current password",
        "La contraseña actual proporcionada es incorrecta"),
    USER_NOT_FOUND         ("AUTH-005", 404, "User not found",
        "Usuario no encontrado"),
    TOKEN_MISSING          ("AUTH-006", 401, "Token missing",
        "Falta el header Authorization con un token Bearer"),
    TOKEN_EXPIRED          ("AUTH-007", 401, "Token expired",
        "El token de acceso expiró. Use /auth/refresh para renovarlo."),
    TOKEN_INVALID          ("AUTH-008", 401, "Token invalid",
        "El token de acceso es inválido o está mal formado"),
    REFRESH_TOKEN_EXPIRED  ("AUTH-009", 401, "Refresh token expired",
        "El refresh token expiró. Volver a hacer login.");

    private final String code;
    private final int status;
    private final String title;
    private final String detail;

    AuthError(String code, int status, String title, String detail) {
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
