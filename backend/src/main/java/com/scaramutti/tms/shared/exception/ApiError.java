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

    /**
     * Error con miembros de extension del Problem (RFC 7807 §3.2): datos que solo tienen sentido
     * para este codigo y que viajan aplanados junto al resto del cuerpo.
     */
    default ApiException toException(String customDetail, java.util.Map<String, Object> extensions) {
        return new ApiException(status(), code(), title(), customDetail, extensions);
    }
}
