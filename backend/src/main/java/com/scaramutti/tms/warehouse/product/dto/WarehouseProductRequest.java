package com.scaramutti.tms.warehouse.product.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.Map;

public record WarehouseProductRequest(

    @Schema(description = "Nombre del producto. Único COMPUESTO con marca y número de parte (Δ-2, RN-WH10).",
            example = "Filtro de aceite XYZ", minLength = 3, maxLength = 200)
    @NotBlank
    @Size(min = 3, max = 200)
    String name,

    @Schema(description = "ID de la categoría (obligatoria)", example = "7")
    @NotNull
    Integer categoryId,

    @Schema(description = "ID de la unidad de medida (obligatoria; inmutable tras crear)", example = "1")
    @NotNull
    Integer unitOfMeasureId,

    @Schema(description = "Marca (opcional)", example = "Bosch", maxLength = 100, nullable = true)
    @Size(max = 100)
    String brand,

    @Schema(description = "Número de parte (opcional)", example = "F026407123", maxLength = 100, nullable = true)
    @Size(max = 100)
    String partNumber,

    @Schema(description = "Características flexibles clave-valor (JSONB). Objeto libre; null → {}.",
            example = "{\"rosca\": \"3/4-16\"}")
    Map<String, String> attributes,

    @Schema(description = "Umbral de reposición: stock < minStock → badge \"Bajo\" (RN-WH11). Default 0.",
            example = "4", minimum = "0")
    @DecimalMin("0")
    BigDecimal minStock,

    @Schema(description = "Observaciones (opcional)", nullable = true)
    String observations,

    @Schema(description = "Se IGNORA en el POST (el producto siempre nace activo).", nullable = true)
    Boolean isActive
) {}
