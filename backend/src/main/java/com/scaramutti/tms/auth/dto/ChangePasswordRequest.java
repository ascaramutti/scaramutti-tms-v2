package com.scaramutti.tms.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

public record ChangePasswordRequest(
    @Schema(description = "Contrasena actual (para validar identidad)")
    @NotBlank @Size(min = 8, max = 100)
    String currentPassword,

    @Schema(description = "Nueva contrasena (minimo 8 caracteres)")
    @NotBlank @Size(min = 8, max = 100)
    String newPassword
) {}
