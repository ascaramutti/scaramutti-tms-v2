package com.scaramutti.tms.catalogs.condition.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Condición del catálogo tal como la devuelve {@code GET /quotation-conditions}. Expone
 * {@code isActive} (como el resto de *Response de catálogo) porque el endpoint es filtrable
 * y puede devolver inactivas (ADR-010); el wizard pide {@code ?isActive=true} (RN-07).
 */
public record ConditionResponse(
    @Schema(description = "ID interno", example = "3")
    Integer id,

    @Schema(description = "Texto de la condición (cara al cliente, sale en el PDF)",
            example = "En caso el cliente cuente con seguro de carga, deberá incluir como beneficiario a Transportes Scaramutti S.A.C. ...")
    String text,

    @Schema(description = "Orden de impresión en el PDF (ascendente)", example = "2", minimum = "1")
    Integer displayOrder,

    @Schema(description = "Indica si la condición está vigente en el catálogo", example = "true")
    Boolean isActive
) {}
