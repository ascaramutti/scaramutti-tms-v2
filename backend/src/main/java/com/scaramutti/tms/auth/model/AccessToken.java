package com.scaramutti.tms.auth.model;

import java.time.Instant;

/**
 * Valor producido por TokenService al emitir un access token.
 * Contiene el JWT firmado, su expiracion absoluta y los segundos restantes
 * para que el cliente sepa cuando renovar.
 */
public record AccessToken(String token, Instant expiresAt, long expiresInSeconds) {}
