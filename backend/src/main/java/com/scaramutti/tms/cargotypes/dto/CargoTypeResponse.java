package com.scaramutti.tms.cargotypes.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;

/**
 * DTO de salida de CargoType. NO incluye `createdAt` (el contrato OpenAPI
 * `CargoTypeResponse` no lo expone, aunque la entity SI lo persiste).
 *
 * Campos `standard*` representan medidas/peso por defecto del tipo de carga;
 * el item de cotizacion puede overridear con sus valores propios.
 */
public record CargoTypeResponse(

    @Schema(description = "ID interno", example = "1")
    Integer id,

    @Schema(description = "Nombre del tipo de carga (en mayusculas, unico)", example = "EXCAVADORA 336")
    String name,

    @Schema(description = "Descripcion opcional", nullable = true)
    String description,

    @Schema(description = "Peso estandar (toneladas o kg, segun convencion del negocio)", example = "10.50")
    BigDecimal standardWeight,

    @Schema(description = "Largo estandar", nullable = true, example = "12.00")
    BigDecimal standardLength,

    @Schema(description = "Ancho estandar", nullable = true, example = "2.50")
    BigDecimal standardWidth,

    @Schema(description = "Alto estandar", nullable = true, example = "3.00")
    BigDecimal standardHeight,

    @Schema(description = "Indica si el tipo de carga esta activo", example = "true")
    Boolean isActive
) {}
