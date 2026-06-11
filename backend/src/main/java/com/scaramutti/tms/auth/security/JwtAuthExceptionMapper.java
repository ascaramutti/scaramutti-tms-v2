package com.scaramutti.tms.auth.security;

import com.scaramutti.tms.auth.AuthError;
import com.scaramutti.tms.shared.exception.ApiError;
import com.scaramutti.tms.shared.exception.Problem;
import io.quarkus.security.AuthenticationFailedException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/**
 * Atrapa fallas de autenticacion JWT lanzadas por el filtro de seguridad de Quarkus
 * y las convierte en Problem (RFC 7807) con el code AUTH-XXX apropiado.
 *
 * Diferencia entre:
 *  - TOKEN_EXPIRED (AUTH-007): el JWT venia pero ya caduco
 *  - TOKEN_INVALID (AUTH-008): el JWT esta mal formado, firma incorrecta, etc.
 *
 * El caso "no se envio Authorization header" lo maneja Quarkus directamente
 * (responde 401 sin pasar por aca) - se mantiene comportamiento default.
 */
@Provider
public class JwtAuthExceptionMapper implements ExceptionMapper<AuthenticationFailedException> {

    @Context
    UriInfo uriInfo;

    @Override
    public Response toResponse(AuthenticationFailedException ex) {
        ApiError error = JwtErrorClassifier.classify(ex, AuthError.TOKEN_EXPIRED, AuthError.TOKEN_INVALID);
        Problem problem = Problem.of(
            error.status(),
            error.title(),
            error.detail(),
            error.code(),
            uriInfo != null ? uriInfo.getPath() : null
        );
        return Response.status(error.status())
            .type("application/problem+json")
            .entity(problem)
            .build();
    }
}
