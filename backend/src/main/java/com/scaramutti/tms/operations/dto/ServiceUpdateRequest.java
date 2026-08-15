package com.scaramutti.tms.operations.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * Edicion de un servicio de transporte. El cliente, el ambito del viaje y el tipo de carga NO
 * viajan: son inmutables despues del alta (si se equivocaron, el viaje se crea de nuevo; si nunca
 * debio existir, se elimina).
 *
 * <p>Las fechas reales de inicio y fin son las unicas con semantica PARCIAL: los demas campos
 * opcionales (dimensiones y observaciones) se asignan tal cual llegan, con lo cual mandarlos en
 * null los vacia, mientras que en las fechas reales null significa "sin cambio" y no "borrar".
 * Ausente y null se tratan igual porque una fecha real no se borra: distinguirlos solo serviria
 * para rechazar el null, y un formulario que serializa el objeto entero manda null en los campos
 * que todavia no aplican.
 */
public record ServiceUpdateRequest(

    @Schema(description = "Puede ser pasada: el registro retroactivo es legitimo (la interfaz avisa, el servidor no bloquea)")
    @NotNull LocalDate tentativeDate,

    @NotBlank @Size(max = 255) String origin,

    @NotBlank @Size(max = 255) String destination,

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

    @Schema(nullable = true) @Size(max = 500) String observations,

    @Schema(nullable = true, description = "Correccion del inicio real; solo si el servicio YA lo tiene (la condicion es la fecha, no el estado). Ausente o en null = sin cambio. La fecha la fija la transicion a \"en ruta\"; en un viaje que todavia no arranco, este campo responde 400")
    OffsetDateTime startDateTime,

    @Schema(nullable = true, description = "Correccion del fin real; solo si el servicio YA lo tiene, y posterior o igual al inicio. Ausente o en null = sin cambio. La fecha la fija la transicion a \"completado\"; en un viaje que todavia no cerro, este campo responde 400")
    OffsetDateTime endDateTime,

    @Schema(description = "Por que se edita; queda en la bitacora y en cada linea de la auditoria")
    @NotBlank @Size(min = MIN_JUSTIFICATION_LENGTH, max = 500) String justification
) {

    /**
     * Minimo de la justificacion. Vive aca, que es la superficie del contrato, y el mapper lo
     * reusa para volver a medirlo sobre el texto ya recortado: si el numero estuviera en dos
     * lugares, las dos capas de la misma regla pueden separarse en silencio.
     */
    public static final int MIN_JUSTIFICATION_LENGTH = 10;
}
