package com.scaramutti.tms.shared.exception;

import io.quarkus.security.ForbiddenException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/**
 * Mapea io.quarkus.security.ForbiddenException (disparada por @RolesAllowed
 * cuando el rol del JWT no matchea) a Problem Details RFC 7807 con codigo COM-003.
 * Sin este mapper, Quarkus devuelve 403 sin body estructurado y rompe el contrato
 * del Problem.
 *
 * Nota: NO mapear jakarta.ws.rs.ForbiddenException — esa la usan APIs JAX-RS para
 * casos generales (raro en este proyecto), y la security extension de Quarkus
 * tira su propia clase io.quarkus.security.ForbiddenException.
 */
@Provider
public class ForbiddenExceptionMapper implements ExceptionMapper<ForbiddenException> {

    @Context
    UriInfo uriInfo;

    @Override
    public Response toResponse(ForbiddenException forbiddenException) {
        ApiError err = CommonError.FORBIDDEN;
        Problem problem = Problem.of(
            err.status(),
            err.title(),
            err.detail(),
            err.code(),
            uriInfo != null ? uriInfo.getPath() : null
        );
        return Response.status(err.status())
            .type("application/problem+json")
            .entity(problem)
            .build();
    }
}
