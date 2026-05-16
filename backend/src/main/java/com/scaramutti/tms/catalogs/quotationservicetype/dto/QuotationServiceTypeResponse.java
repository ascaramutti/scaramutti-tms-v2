package com.scaramutti.tms.catalogs.quotationservicetype.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

public record QuotationServiceTypeResponse(
    @Schema(description = "ID interno", example = "1")
    Integer id,

    @Schema(description = "Código corto en mayúsculas. Debe empezar con S/A/C/I según la categoría.",
            example = "SCB", pattern = "^[A-Z]{2,10}$")
    String code,

    @Schema(description = "Nombre legible del servicio", example = "Servicio de transporte en Cama Baja", maxLength = 100)
    String name,

    @Schema(description = "Categoría derivada del prefijo del code (S→SERVICIO, A→ALQUILER, C→COMPLEMENTARIO, I→INTEGRAL)",
            example = "SERVICIO")
    String kind,

    @Schema(description = "Descripción opcional del servicio", nullable = true)
    String description,

    @Schema(description = "Indica si el servicio está activo", example = "true")
    Boolean isActive
) {}
