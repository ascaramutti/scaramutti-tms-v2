package com.scaramutti.tms.workers.dto;

import com.scaramutti.tms.sharedcatalogs.model.FleetResourceStatus;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * La ficha de conductor de un trabajador: la fila de {@code public.drivers}, sin el nombre
 * ni el telefono, que en el detalle ya estan en el trabajador.
 *
 * <p>{@code id} es el que guardan las asignaciones de viajes de operaciones.
 */
public record WorkerDriverProfileResponse(
    @Schema(example = "5") Integer id,
    @Schema(example = "Q12345678") String licenseNumber,
    @Schema(example = "A-IIIc", nullable = true) String licenseCategory,
    FleetResourceStatus status,
    @Schema(description = "Una ficha se desactiva cuando el cargo deja de llevarla; nunca se borra",
        example = "true") Boolean isActive
) {}
