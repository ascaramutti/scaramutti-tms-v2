package com.scaramutti.tms.auth.dto;

import com.scaramutti.tms.auth.validation.FitsBcrypt;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

public record ChangePasswordRequest(
    @Schema(description = "Contrasena actual (para validar identidad)")
    @NotBlank @Size(min = 8, message = "debe tener al menos 8 caracteres") @FitsBcrypt
    String currentPassword,

    @Schema(description = "Nueva contrasena: hasta 72 bytes UTF-8")
    @NotBlank @Size(min = 8, message = "debe tener al menos 8 caracteres") @FitsBcrypt
    String newPassword
) {}
