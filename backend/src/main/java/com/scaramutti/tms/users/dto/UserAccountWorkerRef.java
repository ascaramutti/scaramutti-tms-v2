package com.scaramutti.tms.users.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/** El trabajador de una cuenta: toda cuenta tiene uno, y un trabajador a lo sumo una cuenta. */
public record UserAccountWorkerRef(
    @Schema(example = "31") Integer id,
    @Schema(example = "Juan Pérez Quispe") String fullName,
    Boolean isActive
) {}
