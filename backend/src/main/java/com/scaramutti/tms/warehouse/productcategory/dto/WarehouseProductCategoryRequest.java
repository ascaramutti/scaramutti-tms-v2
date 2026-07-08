package com.scaramutti.tms.warehouse.productcategory.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

public record WarehouseProductCategoryRequest(

    @Schema(description = "Nombre de la categoría", example = "Filtros", minLength = 3, maxLength = 100)
    @NotBlank
    @Size(min = 3, max = 100)
    String name,

    @Schema(description = "Descripción opcional", nullable = true)
    String description
) {}
