package com.scaramutti.tms.warehouse.productcategory.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

public record WarehouseProductCategoryResponse(
    @Schema(description = "ID interno", example = "1")
    Integer id,

    @Schema(description = "Nombre de la categoría", example = "Filtros", maxLength = 100)
    String name,

    @Schema(description = "Descripción opcional", nullable = true)
    String description,

    @Schema(description = "Indica si la categoría está activa", example = "true")
    Boolean isActive
) {}
