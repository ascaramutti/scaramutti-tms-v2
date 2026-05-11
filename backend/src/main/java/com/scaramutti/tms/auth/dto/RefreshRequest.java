package com.scaramutti.tms.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

public record RefreshRequest(
    @Schema(description = "JWT firmado emitido previamente por /auth/login o /auth/refresh.")
    @NotBlank
    @Size(min = 200, max = 4096)
    String refreshToken
) {}
