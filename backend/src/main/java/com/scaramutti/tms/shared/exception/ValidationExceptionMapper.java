package com.scaramutti.tms.shared.exception;

import jakarta.validation.ConstraintViolationException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import java.util.List;

@Provider
public class ValidationExceptionMapper implements ExceptionMapper<ConstraintViolationException> {

    @Context
    UriInfo uriInfo;

    @Override
    public Response toResponse(ConstraintViolationException ex) {
        List<Problem.FieldError> errors = ex.getConstraintViolations().stream()
            .map(v -> {
                // Path tipico de validacion de parametros: "methodName.argN.fieldName.subField"
                // Queremos quedarnos con "fieldName.subField" - saltamos metodo y nombre del arg.
                String path = v.getPropertyPath().toString();
                String[] parts = path.split("\\.");
                String field = parts.length > 2 ? String.join(".", java.util.Arrays.copyOfRange(parts, 2, parts.length)) : path;
                return new Problem.FieldError(field, v.getMessage(), null);
            })
            .toList();

        ApiError err = CommonError.VALIDATION_FAILED;
        Problem problem = Problem.withErrors(
            err.status(), err.title(), err.detail(), err.code(),
            uriInfo != null ? uriInfo.getPath() : null,
            errors
        );
        return Response.status(err.status())
            .type("application/problem+json")
            .entity(problem)
            .build();
    }
}
