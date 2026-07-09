package com.scaramutti.tms.warehouse.product.dto;

import com.scaramutti.tms.auth.dto.UserResponse;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;

/**
 * {@code createdBy} se expone como {@link UserResponse} (mismo criterio que
 * QuotationResponse: reusa el DTO canonico de usuario del proyecto).
 * {@code stock}/{@code lowStock} se derivan en codigo (nunca persistidos,
 * RN-WH1): un producto recien creado nace con {@code stock = 0} (RN-WH6).
 */
public record WarehouseProductResponse(
    @Schema(description = "ID interno", example = "1")
    Integer id,

    @Schema(description = "SKU (autogenerado si no se envió)", example = "PRO-0001", maxLength = 30)
    String code,

    @Schema(description = "Nombre del producto", example = "Filtro de aceite XYZ", maxLength = 200)
    String name,

    @Schema(description = "Categoría del producto")
    CategoryRef category,

    @Schema(description = "Unidad de medida del producto")
    UnitOfMeasureRef unitOfMeasure,

    @Schema(description = "Marca", example = "Bosch", maxLength = 100, nullable = true)
    String brand,

    @Schema(description = "Número de parte", example = "F026407123", maxLength = 100, nullable = true)
    String partNumber,

    @Schema(description = "Objeto JSON libre clave-valor (JSONB). Nunca null (default {}).")
    Map<String, String> attributes,

    @Schema(description = "Umbral de reposición", example = "4", minimum = "0")
    BigDecimal minStock,

    @Schema(description = "Observaciones", nullable = true)
    String observations,

    @Schema(description = "Indica si el producto está activo", example = "true")
    Boolean isActive,

    @Schema(description = "Stock actual (LEÍDO de la VIEW almacen.product_stock, RN-WH1; nace 0)", example = "0")
    BigDecimal stock,

    @Schema(description = "Derivado: stock < minStock (estricto). NO es columna.", example = "false")
    Boolean lowStock,

    @Schema(description = "Usuario que creó el producto")
    UserResponse createdBy,

    @Schema(description = "Fecha de creación")
    OffsetDateTime createdAt,

    @Schema(description = "Fecha de última actualización")
    OffsetDateTime updatedAt
) {

    @Schema(description = "Referencia a la categoría")
    public record CategoryRef(
        @Schema(example = "7") Integer id,
        @Schema(example = "Filtros") String name
    ) {}

    @Schema(description = "Referencia a la unidad de medida")
    public record UnitOfMeasureRef(
        @Schema(example = "1") Integer id,
        @Schema(example = "UND") String code,
        @Schema(example = "Unidad") String name
    ) {}
}
