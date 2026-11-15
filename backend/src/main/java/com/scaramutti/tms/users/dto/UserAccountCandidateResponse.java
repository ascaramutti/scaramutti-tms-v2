package com.scaramutti.tms.users.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

public record UserAccountCandidateResponse(
    @Schema(example = "31") Integer workerId,
    @Schema(example = "Juan Pérez Quispe") String fullName,
    @Schema(example = "Juan") String firstName,
    @Schema(example = "Pérez") String paternalLastName,
    @Schema(example = "Quispe", nullable = true) String maternalLastName,
    UserAccountRoleResponse role,
    @Schema(example = "jperez") String proposedUsername
) {}
