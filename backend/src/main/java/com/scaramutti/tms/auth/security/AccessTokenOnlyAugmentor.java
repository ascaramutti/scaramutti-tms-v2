package com.scaramutti.tms.auth.security;

import com.scaramutti.tms.auth.model.TokenType;
import com.scaramutti.tms.auth.service.TokenService;
import io.quarkus.security.AuthenticationFailedException;
import io.quarkus.security.identity.AuthenticationRequestContext;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.SecurityIdentityAugmentor;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.jwt.JsonWebToken;

/**
 * Solo el token de acceso es credencial del API. El de renovación está firmado con la misma
 * clave y pasaba como sesión válida en toda ruta sin rol; su lugar es el cuerpo de
 * {@code /auth/refresh}. Va en la identidad y no en un filtro para cubrir también las rutas que
 * vengan después.
 */
@ApplicationScoped
public class AccessTokenOnlyAugmentor implements SecurityIdentityAugmentor {

    /** Sin "expir": el clasificador lo leería como token vencido y respondería AUTH-007. */
    static final String NOT_AN_ACCESS_TOKEN = "El token no es de acceso";

    @Override
    public Uni<SecurityIdentity> augment(SecurityIdentity identity, AuthenticationRequestContext context) {
        if (identity.isAnonymous()) {
            return Uni.createFrom().item(identity);
        }
        // Falla cerrado: una identidad que no viene de un JWT de acceso se rechaza, así un
        // mecanismo de autenticación que se sume después no entra sin pasar por esta regla.
        if (identity.getPrincipal() instanceof JsonWebToken jwt
                && TokenType.ACCESS.claimValue().equals(tokenTypeOf(jwt))) {
            return Uni.createFrom().item(identity);
        }
        return Uni.createFrom().failure(new AuthenticationFailedException(NOT_AN_ACCESS_TOKEN));
    }

    /** Una marca de texto llega como texto: el lector del token solo convierte a JSON lo que no lo es. */
    private static String tokenTypeOf(JsonWebToken jwt) {
        Object claim = jwt.getClaim(TokenService.CLAIM_TYPE);
        return claim instanceof String text ? text : null;
    }
}
