package com.scaramutti.tms.warehouse.unitofmeasure.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

public record WarehouseUnitOfMeasureResponse(
    @Schema(description = "ID interno", example = "1")
    Integer id,

    @Schema(description = "Código de la unidad", example = "GAL", maxLength = 10)
    String code,

    @Schema(description = "Nombre de la unidad", example = "Galón", maxLength = 50)
    String name,

    @Schema(description = "Indica si la unidad está activa", example = "true")
    Boolean isActive
) {}
