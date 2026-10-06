package com.scaramutti.tms.workers.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Tipo de documento de {@code public.document_types}. Sirve al catalogo y al detalle de un
 * trabajador: el mismo objeto en los dos lados para que no puedan divergir.
 */
public record DocumentTypeResponse(
    @Schema(example = "1") Integer id,
    @Schema(example = "DNI") String code,
    @Schema(example = "DNI") String name,
    @Schema(description = "Largo maximo del numero", example = "8") Integer maxLength,
    @Schema(description = "Patron que debe cumplir el numero; nulo si el tipo no define uno",
        example = "^\\d{8}$", nullable = true) String validationPattern
) {}
