package com.scaramutti.tms.shared.exception;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * RFC 7807 Problem Details.
 * Estructura unificada para todos los errores del API.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Problem(
    String type,
    String title,
    Integer status,
    String detail,
    String instance,
    String code,
    String traceId,
    List<FieldError> errors
) {

    /** Prefijo URN para identificadores de tipo de error (RFC 3986). */
    private static final String TYPE_PREFIX = "urn:tms:error:";

    public record FieldError(String field, String message, String code) {}

    public static Problem of(int status, String title, String detail, String code, String instance) {
        return new Problem(typeFromCode(code), title, status, detail, instance, code, newTraceId(), null);
    }

    public static Problem withErrors(int status, String title, String detail, String code, String instance, List<FieldError> errors) {
        return new Problem(typeFromCode(code), title, status, detail, instance, code, newTraceId(), errors);
    }

    private static String typeFromCode(String code) {
        return TYPE_PREFIX + code.toLowerCase().replace('_', '-');
    }

    private static String newTraceId() {
        return java.util.UUID.randomUUID().toString();
    }
}
