package com.scaramutti.tms.auth.security;

import com.scaramutti.tms.auth.AuthError;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.jwt.JsonWebToken;

/**
 * Provider del contexto de seguridad del request actual.
 * Expone datos del JWT autenticado de forma tipada (sin que cada Resource
 * tenga que parsear el subject a mano).
 *
 * Lo consumen los Resources de los modulos que necesitan saber quien es
 * el usuario autenticado del request en curso.
 */
@ApplicationScoped
public class CurrentUser {

    @Inject JsonWebToken jsonWebToken;

    /**
     * Devuelve el ID del usuario autenticado. Lanza si no hay token valido.
     *
     * Nota: TOKEN_MISSING es defensa en profundidad - en practica el filtro de
     * seguridad de Quarkus rechaza antes los requests sin Authorization header.
     * Este chequeo solo se alcanza si alguien remueve el filtro o lo bypasea.
     */
    public Integer requireId() {
        if (jsonWebToken == null || jsonWebToken.getSubject() == null) {
            throw AuthError.TOKEN_MISSING.toException();
        }
        try {
            return Integer.valueOf(jsonWebToken.getSubject());
        } catch (NumberFormatException e) {
            // subject existe pero no es un numero - token mal formado/corrupto
            throw AuthError.TOKEN_INVALID.toException();
        }
    }
}
