package com.scaramutti.tms.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

public record RefreshRequest(
    @Schema(description = "JWT firmado emitido previamente por /auth/login, /auth/refresh o /auth/change-password.")
    @NotBlank
    @Size(min = 200, max = 4096)
    String refreshToken
) {}
