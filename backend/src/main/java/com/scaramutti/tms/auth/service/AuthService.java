package com.scaramutti.tms.auth.service;

import com.scaramutti.tms.auth.AuthError;
import com.scaramutti.tms.auth.dto.LoginResponse;
import com.scaramutti.tms.auth.dto.SessionUserResponse;
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
import jakarta.json.JsonNumber;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.jboss.logging.Logger;

import java.math.BigInteger;

@ApplicationScoped
public class AuthService {

    private static final Logger LOG = Logger.getLogger(AuthService.class);

    @Inject UserRepository userRepository;
    @Inject PasswordService passwordService;
    @Inject TokenService tokenService;
    @Inject JWTParser jwtParser;
    @Inject AuthServiceMapper authServiceMapper;
    @Inject OwnAccountRowLock ownAccountRowLock;

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

        if (!passwordService.matches(loginCommand.password(), user.passwordHash)) {
            LOG.warnf("Login fallido: password incorrecta [username=%s]", user.username);
            throw AuthError.INVALID_CREDENTIALS.toException();
        }

        if (!isUsable(user)) {
            LOG.warnf("Login fallido: cuenta no utilizable [username=%s, id=%d]", user.username, user.id);
            throw AuthError.USER_INACTIVE.toException();
        }

        LOG.infof("Login exitoso [username=%s, id=%d, role=%s]", user.username, user.id, user.worker.role.name);
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
            .filter(AuthService::isUsable)
            .orElseThrow(AuthError.USER_INACTIVE::toException);

        if (!isCurrentSessionVersion(parsedJsonWebToken.getClaim(TokenService.CLAIM_SESSION_VERSION), user)) {
            throw AuthError.REFRESH_TOKEN_INVALID.toException();
        }

        return buildLoginResponse(user);
    }

    /**
     * Por igualdad y no por "mayor o igual": un refresh con una version que la cuenta todavia no
     * alcanzo no es de ninguna sesion emitida. Sin el claim (emitido antes de que existiera; el nulo el
     * parser no lo distingue de la falta) cuenta como 0: renueva hasta el primer cambio. Los propios
     * llegan como valores JSON, asi que un numero es un {@link JsonNumber}; cualquier otra cosa, o uno
     * con decimales, no es una version.
     */
    static boolean isCurrentSessionVersion(Object claim, User user) {
        if (claim == null) {
            return user.sessionVersion == 0;
        }
        return claim instanceof JsonNumber version && version.isIntegral()
            && version.bigIntegerValue().equals(BigInteger.valueOf(user.sessionVersion));
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

    /**
     * Las dos BCrypt corren sin la fila tomada, asi una tanda de pedidos no la retiene. La actual se
     * verifica contra el hash leido sin tomarla (esa lectura espera sin tope, como el ingreso); si no
     * verifica no hay nada que escribir y se responde sin tomarla. Si verifica, la fila se toma y se
     * decide contra la confirmada: si su hash ya no es el verificado, otra escritura lo cambio en el
     * medio y responde AUTH-004 en vez de pisarla.
     */
    @Transactional
    public LoginResponse changePassword(ChangePasswordCommand changePasswordCommand) {
        Integer userId = changePasswordCommand.userId();
        String verifiedHash = userRepository.findPasswordHashByUserId(userId).orElse(null);
        if (verifiedHash == null || !passwordService.matches(changePasswordCommand.currentPassword(), verifiedHash)) {
            requireUsable(userRepository.findByIdOptional(userId).orElseThrow(AuthError.USER_NOT_FOUND::toException));
            throw AuthError.WRONG_CURRENT_PASSWORD.toException();
        }
        String newHash = passwordService.hash(changePasswordCommand.newPassword());

        User user = ownAccountRowLock.lockThenFind(userId)
            .orElseThrow(AuthError.USER_NOT_FOUND::toException);
        requireUsable(user);
        if (!user.passwordHash.equals(verifiedHash)) {
            throw AuthError.WRONG_CURRENT_PASSWORD.toException();
        }

        // Subir la version corta los refresh emitidos antes; la sesion de la respuesta lleva la nueva.
        user.passwordHash = newHash;
        user.sessionVersion = user.sessionVersion + 1;
        userRepository.persist(user);
        ownAccountRowLock.flushTranslatingConflicts(user.id);
        LOG.infof("Password actualizada [username=%s, id=%d]", user.username, user.id);
        return buildLoginResponse(user);
    }

    @Transactional
    public SessionUserResponse findUserById(Integer userId) {
        User user = userRepository.findByIdOptional(userId)
            .orElseThrow(AuthError.USER_NOT_FOUND::toException);
        requireUsable(user);
        return authServiceMapper.toSessionUserResponse(user);
    }

    /**
     * Cuenta utilizable: activa, con su trabajador activo y un cargo que inicia sesion. Un solo
     * predicado para las cuatro operaciones de la sesion, asi no divergen; las tres causas dan el
     * mismo error, cuyo detail ya muestra el ingreso.
     */
    private static boolean isUsable(User user) {
        return user.isActive && user.worker.isActive && user.worker.role.canLogin;
    }

    private static void requireUsable(User user) {
        if (!isUsable(user)) {
            throw AuthError.USER_INACTIVE.toException();
        }
    }

    private LoginResponse buildLoginResponse(User user) {
        AccessToken accessToken = tokenService.createAccessToken(user);
        String refreshToken = tokenService.createRefreshToken(user);
        return authServiceMapper.toLoginResponse(user, accessToken, refreshToken);
    }
}
