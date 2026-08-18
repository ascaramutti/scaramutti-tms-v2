package com.scaramutti.tms.auth.security;

import com.scaramutti.tms.auth.AuthError;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.util.Collection;
import java.util.Objects;

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
     * Indica si el usuario autenticado tiene alguno de los roles dados. Lo usan sobre todo las
     * reglas de negocio que cambian la RESPUESTA segun quien pregunta (por ejemplo, que precios ve
     * cada rol), y para esas el acceso ya lo resolvio {@code @RolesAllowed} antes de llegar aca.
     *
     * <p><b>Pero no siempre es asi.</b> En {@code GET /services/report} este metodo decide ACCESO:
     * la regla es un VETO al despacho y la lista de roles no alcanza para expresarla, porque es un
     * O. Un despachante PURO rebota antes, en el {@code @RolesAllowed}; el que no rebota es el que
     * SUMA despacho y un rol con precios, y a ese lo frena unicamente el veto que se apoya en este
     * metodo. Borrarlo le publica la facturacion de la semana entera. Antes de dar por redundante
     * una llamada a este metodo, hay que mirar si lo que decide es la forma de la respuesta o el
     * acceso.
     *
     * <p>Deniega por defecto: sin token o sin claim de roles devuelve {@code false} en vez de
     * romper, para que la falta de datos nunca se lea como un si. Que un rol nuevo NO herede
     * permisos por accidente lo da la lista positiva de quien llama, no este metodo.
     */
    public boolean hasAnyRole(Collection<String> roles) {
        if (jsonWebToken == null || jsonWebToken.getGroups() == null) {
            return false;
        }
        return jsonWebToken.getGroups().stream().filter(Objects::nonNull).anyMatch(roles::contains);
    }

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
