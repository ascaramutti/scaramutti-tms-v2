package com.scaramutti.tms.operations.dto.embedded;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.time.LocalDate;

/**
 * El ciclo operativo vigente (RN-OP14), tal como lo IMPRIME la pantalla.
 *
 * <p>{@code end} es el MARTES en que cierra, inclusive. NO es el borde con el que se consulta la
 * base, que es el miercoles siguiente y exclusivo: son dos fechas distintas separadas por un dia, y
 * publicar la segunda donde va la primera corre la etiqueta una semana.
 */
public record ServiceWeekCycleSummary(

    @Schema(description = "Miércoles en que abrió el ciclo, en hora de Lima", example = "2026-08-19")
    LocalDate start,

    @Schema(description = "Martes en que cierra el ciclo, INCLUSIVE, en hora de Lima", example = "2026-08-25")
    LocalDate end
) {}
