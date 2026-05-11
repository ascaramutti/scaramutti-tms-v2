package com.scaramutti.tms.auth.service;

import com.scaramutti.tms.auth.AuthError;
import com.scaramutti.tms.auth.dto.LoginResponse;
import com.scaramutti.tms.auth.dto.UserResponse;
import com.scaramutti.tms.auth.mapper.AuthServiceMapper;
import com.scaramutti.tms.auth.model.AccessToken;
import com.scaramutti.tms.auth.model.TokenType;
import com.scaramutti.tms.auth.security.JwtErrorClassifier;
import com.scaramutti.tms.auth.service.cmd.ChangePasswordCommand;
import com.scaramutti.tms.auth.service.cmd.LoginCommand;
import com.scaramutti.tms.auth.service.cmd.RefreshCommand;
import com.scaramutti.tms.shared.entity.User;
import com.scaramutti.tms.shared.exception.ApiError;
import com.scaramutti.tms.shared.repository.UserRepository;
import io.smallrye.jwt.auth.principal.JWTParser;
import io.smallrye.jwt.auth.principal.ParseException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.jboss.logging.Logger;


@ApplicationScoped
public class AuthService {

    private static final Logger LOG = Logger.getLogger(AuthService.class);

    @Inject UserRepository userRepository;
    @Inject PasswordService passwordService;
    @Inject TokenService tokenService;
    @Inject JWTParser jwtParser;
    @Inject AuthServiceMapper authServiceMapper;

    @Transactional
    public LoginResponse login(LoginCommand loginCommand) {
        // Buscamos el user PRIMERO; si no existe, igualamos el tiempo de respuesta
        // con un BCrypt dummy para que un atacante no pueda enumerar usernames midiendo latencia.
        User user = userRepository.findByUsername(loginCommand.username()).orElse(null);
        if (user == null) {
            passwordService.runDummyVerify();
            LOG.warnf("Login fallido: usuario no existe [username=%s]", loginCommand.username());
            throw AuthError.INVALID_CREDENTIALS.toException();
        }

        try {
            verifyPasswordOrThrow(user, loginCommand.password(), AuthError.INVALID_CREDENTIALS);
        } catch (RuntimeException e) {
            LOG.warnf("Login fallido: password incorrecta [username=%s]", user.username);
            throw e;
        }

        if (!user.isActive) {
            LOG.warnf("Login fallido: usuario inactivo [username=%s, id=%d]", user.username, user.id);
            throw AuthError.USER_INACTIVE.toException();
        }

        LOG.infof("Login exitoso [username=%s, id=%d, role=%s]", user.username, user.id, user.role.name);
        return buildLoginResponse(user);
    }

    @Transactional
    public LoginResponse refresh(RefreshCommand refreshCommand) {
        JsonWebToken parsedJsonWebToken = parseRefreshTokenOrThrow(refreshCommand.refreshToken());

        if (!TokenType.REFRESH.claimValue().equals(parsedJsonWebToken.getClaim(TokenService.CLAIM_TYPE))) {
            throw AuthError.REFRESH_TOKEN_INVALID.toException();
        }

        Integer userId;
        try {
            userId = Integer.valueOf(parsedJsonWebToken.getSubject());
        } catch (NumberFormatException e) {
            throw AuthError.REFRESH_TOKEN_INVALID.toException();
        }

        User user = userRepository.findByIdOptional(userId)
            .filter(u -> u.isActive)
            .orElseThrow(AuthError.USER_INACTIVE::toException);

        return buildLoginResponse(user);
    }

    private JsonWebToken parseRefreshTokenOrThrow(String token) {
        try {
            return jwtParser.parse(token);
        } catch (ParseException e) {
            ApiError error = JwtErrorClassifier.classify(
                e, AuthError.REFRESH_TOKEN_EXPIRED, AuthError.REFRESH_TOKEN_INVALID
            );
            throw error.toException();
        }
    }

    @Transactional
    public void changePassword(ChangePasswordCommand changePasswordCommand) {
        User user = userRepository.findByIdOptional(changePasswordCommand.userId())
            .orElseThrow(AuthError.USER_NOT_FOUND::toException);

        verifyPasswordOrThrow(user, changePasswordCommand.currentPassword(), AuthError.WRONG_CURRENT_PASSWORD);

        user.passwordHash = passwordService.hash(changePasswordCommand.newPassword());
        userRepository.persist(user);
        LOG.infof("Password actualizada [username=%s, id=%d]", user.username, user.id);
    }

    @Transactional
    public UserResponse findUserById(Integer userId) {
        User user = userRepository.findByIdOptional(userId)
            .orElseThrow(AuthError.USER_NOT_FOUND::toException);
        return authServiceMapper.toUserResponse(user);
    }

    private void verifyPasswordOrThrow(User user, String plainPassword, ApiError errorIfMismatch) {
        if (!passwordService.matches(plainPassword, user.passwordHash)) {
            throw errorIfMismatch.toException();
        }
    }

    private LoginResponse buildLoginResponse(User user) {
        AccessToken accessToken = tokenService.createAccessToken(user);
        String refreshToken = tokenService.createRefreshToken(user);
        return authServiceMapper.toLoginResponse(user, accessToken, refreshToken);
    }
}
