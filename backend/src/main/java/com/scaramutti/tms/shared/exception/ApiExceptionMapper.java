package com.scaramutti.tms.shared.exception;

import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

@Provider
public class ApiExceptionMapper implements ExceptionMapper<ApiException> {

    @Context
    UriInfo uriInfo;

    @Override
    public Response toResponse(ApiException ex) {
        // Siempre por la variante con extensiones: sin ellas el mapa viene vacio y el cuerpo sale
        // identico al de antes, asi que no hay dos caminos que puedan divergir con el tiempo.
        Problem problem = Problem.withExtensions(
            ex.status(),
            ex.title(),
            ex.getMessage(),
            ex.code(),
            uriInfo != null ? uriInfo.getPath() : null,
            ex.extensions()
        );
        return Response.status(ex.status())
            .type("application/problem+json")
            .entity(problem)
            .build();
    }
}
