package com.scaramutti.tms.auth.security;

import com.scaramutti.tms.shared.exception.ApiError;

/**
 * Clasifica errores de parseo/validacion de JWT en "expirado" vs "invalido".
 * Centraliza la deteccion para que JwtAuthExceptionMapper y AuthService.refresh
 * compartan la misma logica.
 *
 * Detecta el caso "expirado" buscando el marker en el mensaje de la excepcion
 * raiz (smallrye-jwt + jose4j usan "expired" en sus mensajes).
 */
public final class JwtErrorClassifier {

    /**
     * Fragmento que SmallRye/jose4j incluyen en el mensaje cuando el token caduco.
     * Cubre "expired", "expiration" y variantes (el mensaje real de jose4j es del
     * estilo "The JWT is no longer valid - the evaluation time ... is on or after
     * the Expiration Time").
     */
    private static final String EXPIRED_MARKER = "expir";

    private JwtErrorClassifier() {}

    /**
     * Devuelve {@code onExpired} si el throwable indica token caducado,
     * o {@code onInvalid} en cualquier otro caso (mal formado, firma incorrecta, etc.).
     */
    public static ApiError classify(Throwable parseFailure, ApiError onExpired, ApiError onInvalid) {
        if (containsExpiredMarker(parseFailure)) {
            return onExpired;
        }
        return onInvalid;
    }

    /**
     * Recorre TODA la cadena de causas buscando el marker "expir".
     * Necesario porque Quarkus envuelve la excepcion JWT real en varios niveles
     * (AuthenticationFailedException → ParseException → InvalidJwtException).
     */
    private static boolean containsExpiredMarker(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && message.toLowerCase().contains(EXPIRED_MARKER)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
