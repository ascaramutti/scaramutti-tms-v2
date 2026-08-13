package com.scaramutti.tms.operations.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Asignacion de los recursos principales de un viaje. El conductor y el tracto son obligatorios
 * y la carreta es opcional: hay carga que no la lleva.
 *
 * <p>El estado nuevo no viaja en el cuerpo: pasar a "pendiente de inicio" es EFECTO de asignar,
 * no algo que se pida. Por eso este endpoint no es una transicion de estado y no comparte su
 * camino ni sus codigos.
 */
public record ServiceAssignResourcesRequest(

    @NotNull @Positive Integer driverId,

    @NotNull @Positive Integer tractorId,

    @Schema(nullable = true, description = "Opcional: hay carga que no lleva carreta")
    @Positive Integer trailerId,

    @Schema(nullable = true, description = "Nota libre para la bitácora; sin mínimo, a diferencia de la justificación de la edición")
    @Size(max = 500) String note,

    @Schema(description = "Asigna pese al conflicto OPS-002 y lo deja registrado; ausente equivale a false")
    Boolean force
) {}
