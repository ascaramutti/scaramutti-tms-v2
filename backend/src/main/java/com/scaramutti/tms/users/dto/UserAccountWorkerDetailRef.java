package com.scaramutti.tms.users.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * El trabajador de una cuenta, en el detalle: lo de {@link UserAccountWorkerRef} mas su nombre en
 * partes. Sin materno, {@code maternalLastName} viaja nulo, nunca vacio.
 */
public record UserAccountWorkerDetailRef(
    @Schema(example = "31") Integer id,
    @Schema(example = "Juan Pérez Quispe") String fullName,
    Boolean isActive,
    @Schema(example = "Juan") String firstName,
    @Schema(example = "Pérez") String paternalLastName,
    @Schema(example = "Quispe", nullable = true) String maternalLastName
) {}
