package com.scaramutti.tms.cargotypes.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;

/**
 * Request body para POST /cargo-types.
 *
 * Validaciones:
 *  - name: requerido, 1..100 chars (Bean Validation @NotBlank + @Size).
 *    El mapper hace trim+uppercase antes de pasar al service.
 *  - description: opcional, sin maxLength (la BD es TEXT). Si llega a ser
 *    problema (payloads gigantes), agregar @Size en un PR posterior.
 *  - standardWeight: requerido, >= 0, hasta NUMERIC(10,2) (8 enteros + 2 decimales).
 *  - standardLength/Width/Height: opcionales, >= 0, mismo precision/scale.
 *
 * `@Digits(integer=8, fraction=2)` previene overflow del NUMERIC(10,2) de Postgres:
 * sin esta validacion un payload con weight=1e10 devolveria 500 (SQL overflow).
 */
public record CargoTypeRequest(

    @Schema(description = "Nombre del tipo de carga", example = "EXCAVADORA 336",
            minLength = 1, maxLength = 100)
    @NotBlank
    @Size(min = 1, max = 100)
    String name,

    @Schema(description = "Descripcion opcional", nullable = true)
    String description,

    @Schema(description = "Peso estandar (kg o ton segun convencion del negocio)", example = "10.50")
    @NotNull
    @DecimalMin(value = "0", inclusive = true)
    @Digits(integer = 8, fraction = 2)
    BigDecimal standardWeight,

    @Schema(description = "Largo estandar (opcional)", example = "12.00", nullable = true)
    @DecimalMin(value = "0", inclusive = true)
    @Digits(integer = 8, fraction = 2)
    BigDecimal standardLength,

    @Schema(description = "Ancho estandar (opcional)", example = "2.50", nullable = true)
    @DecimalMin(value = "0", inclusive = true)
    @Digits(integer = 8, fraction = 2)
    BigDecimal standardWidth,

    @Schema(description = "Alto estandar (opcional)", example = "3.00", nullable = true)
    @DecimalMin(value = "0", inclusive = true)
    @Digits(integer = 8, fraction = 2)
    BigDecimal standardHeight
) {}
