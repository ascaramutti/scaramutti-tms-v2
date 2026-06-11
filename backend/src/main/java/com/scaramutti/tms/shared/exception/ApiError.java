package com.scaramutti.tms.shared.exception;

/**
 * Contrato comun para catalogos de errores. Los modulos definen un enum
 * (ej: AuthError, CommonError) que implementa esta interfaz, ganando metodos
 * toException() sin duplicar boilerplate.
 */
public interface ApiError {

    String code();
    int status();
    String title();
    String detail();

    default ApiException toException() {
        return new ApiException(status(), code(), title(), detail());
    }

    default ApiException toException(String customDetail) {
        return new ApiException(status(), code(), title(), customDetail);
    }
}
