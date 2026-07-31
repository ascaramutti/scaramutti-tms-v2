package com.scaramutti.tms.operations.dto;

import com.scaramutti.tms.operations.model.TripScope;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Alta de un servicio de transporte. El codigo, el estado inicial y la primera linea de la
 * bitacora los pone el backend: no viajan en el body.
 */
public record ServiceCreateRequest(

    @NotNull Integer clientId,

    @NotNull TripScope tripScope,

    @Schema(description = "Puede ser pasada: el registro retroactivo es legitimo (la interfaz avisa, el servidor no bloquea)")
    @NotNull LocalDate tentativeDate,

    @NotBlank @Size(max = 255) String origin,

    @NotBlank @Size(max = 255) String destination,

    @NotNull Integer cargoTypeId,

    @Schema(description = "Peso de la carga en kilogramos")
    @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 8, fraction = 2)
    BigDecimal weightKg,

    @Schema(nullable = true, description = "Largo en metros")
    @DecimalMin(value = "0", inclusive = false) @Digits(integer = 8, fraction = 2)
    BigDecimal lengthM,

    @Schema(nullable = true, description = "Ancho en metros")
    @DecimalMin(value = "0", inclusive = false) @Digits(integer = 8, fraction = 2)
    BigDecimal widthM,

    @Schema(nullable = true, description = "Alto en metros")
    @DecimalMin(value = "0", inclusive = false) @Digits(integer = 8, fraction = 2)
    BigDecimal heightM,

    @Schema(description = "Mayor que cero: un viaje siempre tiene precio")
    @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 10, fraction = 2)
    BigDecimal price,

    @NotNull Integer currencyId,

    @Schema(nullable = true) @Size(max = 500) String observations
) {}
