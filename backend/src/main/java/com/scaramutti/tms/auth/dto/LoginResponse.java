package com.scaramutti.tms.auth.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.time.OffsetDateTime;

public record LoginResponse(
    @Schema(description = "Access token (JWT) para incluir en header Authorization: Bearer ...")
    String token,

    @Schema(description = "Refresh token para renovar el access token sin re-login")
    String refreshToken,

    @Schema(description = "Fecha/hora absoluta de expiracion del access token (UTC)")
    OffsetDateTime expiresAt,

    @Schema(description = "Segundos restantes hasta expiracion del access token", example = "3600")
    Long expiresIn,

    UserResponse user
) {}
