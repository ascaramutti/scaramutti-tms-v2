package com.scaramutti.tms.quotations.dto.embedded;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Vista resumida del tipo de servicio para embeber en items de
 * {@code QuotationResponse}. Subset intencional de
 * {@code QuotationServiceTypeResponse}: id + code + name + kind.
 *
 * <p>NO incluye {@code description} ni {@code isActive}: no aportan en el
 * contexto de cotizacion. El {@code kind} (SERVICIO/ALQUILER/COMPLEMENTARIO/INTEGRAL)
 * es relevante para el frontend porque define como se renderiza el item
 * (ej. items kind=INTEGRAL agrupan hijos visualmente).
 */
public record QuotationServiceTypeSummary(

    @Schema(description = "ID interno", example = "1")
    Integer id,

    @Schema(description = "Codigo corto", example = "SCB")
    String code,

    @Schema(description = "Nombre legible", example = "Servicio de transporte en Cama Baja")
    String name,

    @Schema(description = "Categoria del servicio (SERVICIO/ALQUILER/COMPLEMENTARIO/INTEGRAL)",
            example = "SERVICIO")
    String kind
) {}
