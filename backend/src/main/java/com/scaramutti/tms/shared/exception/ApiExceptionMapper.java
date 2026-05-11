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
        Problem problem = Problem.of(
            ex.status(),
            ex.title(),
            ex.getMessage(),
            ex.code(),
            uriInfo != null ? uriInfo.getPath() : null
        );
        return Response.status(ex.status())
            .type("application/problem+json")
            .entity(problem)
            .build();
    }
}
