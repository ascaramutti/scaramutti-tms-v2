package com.scaramutti.tms.auth.service;

import com.scaramutti.tms.auth.model.AccessToken;
import com.scaramutti.tms.auth.model.TokenType;
import com.scaramutti.tms.shared.entity.User;
import io.smallrye.jwt.build.Jwt;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Instant;
import java.util.Set;

/**
 * Emite y maneja access tokens (JWT firmados con RS256) y refresh tokens.
 *
 * Refresh tokens son strings opacos no estandar - se firman como JWT tambien
 * para portabilidad pero llevan un claim `typ=refresh` que los distingue.
 */
@ApplicationScoped
public class TokenService {

    /** Nombre del claim custom usado para distinguir tipos de token. */
    public static final String CLAIM_TYPE = "typ";

    /** Nombre del claim que lleva el nombre completo del usuario. */
    public static final String CLAIM_FULL_NAME = "fullName";

    /** Nombre del claim que lleva el cargo del usuario. */
    public static final String CLAIM_POSITION = "position";

    @ConfigProperty(name = "smallrye.jwt.new-token.lifespan")
    long accessTokenLifespanSeconds;

    @ConfigProperty(name = "app.jwt.refresh-token-lifespan-seconds")
    long refreshTokenLifespanSeconds;

    public AccessToken createAccessToken(User user) {
        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(accessTokenLifespanSeconds);

        String token = Jwt.subject(user.id.toString())
            .upn(user.username)
            .groups(Set.of(user.role.name))
            .claim(CLAIM_FULL_NAME, user.worker.fullName())
            .claim(CLAIM_POSITION, user.worker.position)
            .claim(CLAIM_TYPE, TokenType.ACCESS.claimValue())
            .issuedAt(now)
            .expiresAt(expiresAt)
            .sign();

        return new AccessToken(token, expiresAt, accessTokenLifespanSeconds);
    }

    public String createRefreshToken(User user) {
        Instant now = Instant.now();
        return Jwt.subject(user.id.toString())
            .upn(user.username)
            .claim(CLAIM_TYPE, TokenType.REFRESH.claimValue())
            .issuedAt(now)
            .expiresAt(now.plusSeconds(refreshTokenLifespanSeconds))
            .sign();
    }
}
