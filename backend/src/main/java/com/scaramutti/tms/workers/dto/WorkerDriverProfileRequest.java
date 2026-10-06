package com.scaramutti.tms.workers.dto;

import com.scaramutti.tms.sharedcatalogs.model.FleetResourceStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * La ficha de conductor que viaja dentro del cuerpo de un trabajador.
 *
 * <p>Que la ficha CORRESPONDA con el cargo es regla de negocio y sale con su propio codigo;
 * lo de aca es la forma del objeto, que se valida antes y sale como error de campo.
 */
public record WorkerDriverProfileRequest(
    @NotBlank @Size(max = 20)
    @Schema(example = "Q12345678") String licenseNumber,

    @Size(max = 20)
    @Schema(nullable = true, example = "A-IIIc") String licenseCategory,

    @Schema(description = "Ausente en el alta: disponible", nullable = true)
    FleetResourceStatus status
) {}
