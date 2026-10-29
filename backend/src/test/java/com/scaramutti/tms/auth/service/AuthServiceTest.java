package com.scaramutti.tms.auth.service;

import com.scaramutti.tms.auth.AuthError;
import com.scaramutti.tms.auth.dto.LoginResponse;
import com.scaramutti.tms.auth.dto.SessionUserResponse;
import com.scaramutti.tms.auth.mapper.AuthServiceMapper;
import com.scaramutti.tms.auth.model.AccessToken;
import com.scaramutti.tms.auth.service.cmd.ChangePasswordCommand;
import com.scaramutti.tms.auth.service.cmd.LoginCommand;
import com.scaramutti.tms.auth.service.cmd.RefreshCommand;
import com.scaramutti.tms.shared.entity.Role;
import com.scaramutti.tms.shared.entity.User;
import com.scaramutti.tms.shared.entity.Worker;
import com.scaramutti.tms.shared.exception.ApiException;
import com.scaramutti.tms.shared.repository.UserRepository;
import io.smallrye.jwt.auth.principal.JWTParser;
import io.smallrye.jwt.auth.principal.ParseException;
import jakarta.json.Json;
import jakarta.json.JsonValue;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests para AuthService con mocks de UserRepository, PasswordService,
 * TokenService, JWTParser y AuthServiceMapper. Aisla la logica del service
 * sin levantar Quarkus ni tocar BD.
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock UserRepository userRepository;
    @Mock PasswordService passwordService;
    @Mock TokenService tokenService;
    @Mock JWTParser jwtParser;
    @Mock AuthServiceMapper authServiceMapper;
    @Mock OwnAccountRowLock ownAccountRowLock;

    @InjectMocks AuthService authService;

    private User user;
    private SessionUserResponse sessionUserResponse;
    private AccessToken accessToken;
    private LoginResponse loginResponse;

    @BeforeEach
    void setUp() {
        Worker worker = new Worker();
        worker.id = 10;
        worker.firstName = "Valeria";
        worker.assignSurnames("Torres", null);
        Role workerRole = new Role();
        workerRole.name = "sales";
        workerRole.description = "Ejecutivo de Ventas";
        worker.role = workerRole;

        user = new User();
        user.id = 100;
        user.username = "sales";
        user.passwordHash = "hashed-password";
        user.worker = worker;
        user.isActive = true;

        sessionUserResponse = new SessionUserResponse(100, "sales", "Valeria Torres", "Ejecutivo de Ventas", "sales", true, 10);
        accessToken = new AccessToken("access.jwt.token", Instant.now().plusSeconds(3600), 3600L);
        loginResponse = new LoginResponse(
            "access.jwt.token", "refresh.jwt.token",
            OffsetDateTime.now().plusSeconds(3600), 3600L, sessionUserResponse
        );
    }

    // ============== login ====================================================

    @Test
    void login_withValidCredentials_returnsLoginResponse() {
        when(userRepository.findByUsername("sales")).thenReturn(Optional.of(user));
        when(passwordService.matches("plain-password", "hashed-password")).thenReturn(true);
        when(tokenService.createAccessToken(user)).thenReturn(accessToken);
        when(tokenService.createRefreshToken(user)).thenReturn("refresh.jwt.token");
        when(authServiceMapper.toLoginResponse(user, accessToken, "refresh.jwt.token")).thenReturn(loginResponse);

        LoginResponse result = authService.login(new LoginCommand("sales", "plain-password"));

        assertSame(loginResponse, result);
        verify(passwordService, never()).runDummyVerify();
    }

    @Test
    void login_withNonexistentUser_throwsInvalidCredentialsAndRunsDummyVerify() {
        when(userRepository.findByUsername("ghost")).thenReturn(Optional.empty());

        ApiException ex = assertThrows(ApiException.class,
            () -> authService.login(new LoginCommand("ghost", "any-password")));

        assertEquals(AuthError.INVALID_CREDENTIALS.code(), ex.code());
        // Timing protection: dummy verify se ejecuta cuando el user no existe
        verify(passwordService, times(1)).runDummyVerify();
        verify(passwordService, never()).matches(anyString(), anyString());
        verify(tokenService, never()).createAccessToken(eq(user));
    }

    @Test
    void login_withWrongPassword_throwsInvalidCredentials() {
        when(userRepository.findByUsername("sales")).thenReturn(Optional.of(user));
        when(passwordService.matches("wrong-password", "hashed-password")).thenReturn(false);

        ApiException ex = assertThrows(ApiException.class,
            () -> authService.login(new LoginCommand("sales", "wrong-password")));

        assertEquals(AuthError.INVALID_CREDENTIALS.code(), ex.code());
        verify(tokenService, never()).createAccessToken(eq(user));
    }

    @Test
    void login_withInactiveUser_throwsUserInactive() {
        user.isActive = false;
        when(userRepository.findByUsername("sales")).thenReturn(Optional.of(user));
        when(passwordService.matches("plain-password", "hashed-password")).thenReturn(true);

        ApiException ex = assertThrows(ApiException.class,
            () -> authService.login(new LoginCommand("sales", "plain-password")));

        assertEquals(AuthError.USER_INACTIVE.code(), ex.code());
        verify(tokenService, never()).createAccessToken(eq(user));
    }

    @Test
    void login_withTheWorkerInactive_throwsUserInactive() {
        user.worker.isActive = false;
        when(userRepository.findByUsername("sales")).thenReturn(Optional.of(user));
        when(passwordService.matches("plain-password", "hashed-password")).thenReturn(true);

        ApiException ex = assertThrows(ApiException.class,
            () -> authService.login(new LoginCommand("sales", "plain-password")));

        assertEquals(AuthError.USER_INACTIVE.code(), ex.code());
        verify(tokenService, never()).createAccessToken(eq(user));
    }

    // ============== refresh ==================================================

    @Test
    void refresh_withValidRefreshToken_returnsLoginResponse() throws ParseException {
        JsonWebToken parsedToken = mockParsedToken("refresh");
        when(parsedToken.getSubject()).thenReturn("100");
        when(jwtParser.parse("valid.refresh.token")).thenReturn(parsedToken);
        when(userRepository.findByIdOptional(100)).thenReturn(Optional.of(user));
        when(tokenService.createAccessToken(user)).thenReturn(accessToken);
        when(tokenService.createRefreshToken(user)).thenReturn("new.refresh.token");
        when(authServiceMapper.toLoginResponse(user, accessToken, "new.refresh.token")).thenReturn(loginResponse);

        LoginResponse result = authService.refresh(new RefreshCommand("valid.refresh.token"));

        assertSame(loginResponse, result);
    }

    /** Una version que no es la de la cuenta: AUTH-003, sin emitir nada. */
    @Test
    void refresh_withAnotherSessionVersion_throwsRefreshTokenInvalid() throws ParseException {
        user.sessionVersion = 2;
        JsonWebToken parsedToken = mockParsedToken("refresh");
        when(parsedToken.getSubject()).thenReturn("100");
        when(parsedToken.getClaim(TokenService.CLAIM_SESSION_VERSION)).thenReturn(Json.createValue(1));
        when(jwtParser.parse("old.refresh.token")).thenReturn(parsedToken);
        when(userRepository.findByIdOptional(100)).thenReturn(Optional.of(user));

        ApiException ex = assertThrows(ApiException.class,
            () -> authService.refresh(new RefreshCommand("old.refresh.token")));

        assertEquals(AuthError.REFRESH_TOKEN_INVALID.code(), ex.code());
        verify(tokenService, never()).createRefreshToken(eq(user));
    }

    @Test
    void refresh_withMalformedToken_throwsRefreshTokenInvalid() throws ParseException {
        when(jwtParser.parse("bad.token")).thenThrow(new ParseException("Invalid signature"));

        ApiException ex = assertThrows(ApiException.class,
            () -> authService.refresh(new RefreshCommand("bad.token")));

        assertEquals(AuthError.REFRESH_TOKEN_INVALID.code(), ex.code());
    }

    @Test
    void refresh_withExpiredToken_throwsRefreshTokenExpired() throws ParseException {
        when(jwtParser.parse("expired.token"))
            .thenThrow(new ParseException("Token has expired"));

        ApiException ex = assertThrows(ApiException.class,
            () -> authService.refresh(new RefreshCommand("expired.token")));

        assertEquals(AuthError.REFRESH_TOKEN_EXPIRED.code(), ex.code());
    }

    @Test
    void refresh_withAccessTokenType_throwsRefreshTokenInvalid() throws ParseException {
        JsonWebToken parsedToken = mockParsedToken("access");
        when(jwtParser.parse("access.token")).thenReturn(parsedToken);

        ApiException ex = assertThrows(ApiException.class,
            () -> authService.refresh(new RefreshCommand("access.token")));

        assertEquals(AuthError.REFRESH_TOKEN_INVALID.code(), ex.code());
        verify(userRepository, never()).findByIdOptional(100);
    }

    @Test
    void refresh_withMalformedSubject_throwsRefreshTokenInvalid() throws ParseException {
        JsonWebToken parsedToken = mockParsedToken("refresh");
        when(parsedToken.getSubject()).thenReturn("not-a-number");
        when(jwtParser.parse("token.with.bad.subject")).thenReturn(parsedToken);

        ApiException ex = assertThrows(ApiException.class,
            () -> authService.refresh(new RefreshCommand("token.with.bad.subject")));

        assertEquals(AuthError.REFRESH_TOKEN_INVALID.code(), ex.code());
    }

    @Test
    void refresh_withNonexistentUserId_throwsUserInactive() throws ParseException {
        JsonWebToken parsedToken = mockParsedToken("refresh");
        when(parsedToken.getSubject()).thenReturn("999");
        when(jwtParser.parse("valid.token")).thenReturn(parsedToken);
        when(userRepository.findByIdOptional(999)).thenReturn(Optional.empty());

        ApiException ex = assertThrows(ApiException.class,
            () -> authService.refresh(new RefreshCommand("valid.token")));

        // En refresh, si el user no existe se trata como inactivo (no tiene sentido revivir sesion)
        assertEquals(AuthError.USER_INACTIVE.code(), ex.code());
    }

    @Test
    void refresh_withInactiveUser_throwsUserInactive() throws ParseException {
        user.isActive = false;
        JsonWebToken parsedToken = mockParsedToken("refresh");
        when(parsedToken.getSubject()).thenReturn("100");
        when(jwtParser.parse("valid.token")).thenReturn(parsedToken);
        when(userRepository.findByIdOptional(100)).thenReturn(Optional.of(user));

        ApiException ex = assertThrows(ApiException.class,
            () -> authService.refresh(new RefreshCommand("valid.token")));

        assertEquals(AuthError.USER_INACTIVE.code(), ex.code());
    }

    @Test
    void refresh_withARoleThatDoesNotLogIn_throwsUserInactive() throws ParseException {
        user.worker.role.canLogin = false;
        JsonWebToken parsedToken = mockParsedToken("refresh");
        when(parsedToken.getSubject()).thenReturn("100");
        when(jwtParser.parse("valid.token")).thenReturn(parsedToken);
        when(userRepository.findByIdOptional(100)).thenReturn(Optional.of(user));

        ApiException ex = assertThrows(ApiException.class,
            () -> authService.refresh(new RefreshCommand("valid.token")));

        assertEquals(AuthError.USER_INACTIVE.code(), ex.code());
        verify(tokenService, never()).createRefreshToken(eq(user));
    }

    // ============== changePassword ===========================================

    @Test
    void changePassword_withCorrectCurrent_persistsTheNewHash_raisesTheVersion_andReturnsTheNewSession() {
        user.sessionVersion = 4;
        when(userRepository.findPasswordHashByUserId(100)).thenReturn(Optional.of("hashed-password"));
        when(passwordService.matches("current-plain", "hashed-password")).thenReturn(true);
        when(passwordService.hash("new-plain")).thenReturn("new-hashed");
        when(ownAccountRowLock.lockThenFind(100)).thenReturn(Optional.of(user));
        when(tokenService.createAccessToken(user)).thenReturn(accessToken);
        when(tokenService.createRefreshToken(user)).thenReturn("new.refresh.token");
        when(authServiceMapper.toLoginResponse(user, accessToken, "new.refresh.token")).thenReturn(loginResponse);

        LoginResponse result = authService.changePassword(new ChangePasswordCommand(100, "current-plain", "new-plain"));

        assertSame(loginResponse, result);
        assertEquals("new-hashed", user.passwordHash);
        assertEquals(5, user.sessionVersion);
        verify(userRepository).persist(user);
        verify(ownAccountRowLock).flushTranslatingConflicts(100);
        // Las dos BCrypt antes de tomar la fila: con la fila tomada, una tanda de cambios la retiene.
        InOrder order = inOrder(passwordService, ownAccountRowLock);
        order.verify(passwordService).matches("current-plain", "hashed-password");
        order.verify(passwordService).hash("new-plain");
        order.verify(ownAccountRowLock).lockThenFind(100);
    }

    @Test
    void changePassword_withNonexistentUser_throwsUserNotFound() {
        when(userRepository.findPasswordHashByUserId(999)).thenReturn(Optional.empty());
        when(userRepository.findByIdOptional(999)).thenReturn(Optional.empty());

        ApiException ex = assertThrows(ApiException.class,
            () -> authService.changePassword(new ChangePasswordCommand(999, "any", "newpass")));

        assertEquals(AuthError.USER_NOT_FOUND.code(), ex.code());
        verify(ownAccountRowLock, never()).lockThenFind(999);
    }

    /** Con la actual equivocada no hay nada que escribir: se responde sin tomar la fila ni hashear. */
    @Test
    void changePassword_withWrongCurrent_throwsWithoutTakingTheRow() {
        when(userRepository.findPasswordHashByUserId(100)).thenReturn(Optional.of("hashed-password"));
        when(passwordService.matches("wrong-current", "hashed-password")).thenReturn(false);
        when(userRepository.findByIdOptional(100)).thenReturn(Optional.of(user));

        ApiException ex = assertThrows(ApiException.class,
            () -> authService.changePassword(new ChangePasswordCommand(100, "wrong-current", "newpass")));

        assertEquals(AuthError.WRONG_CURRENT_PASSWORD.code(), ex.code());
        verify(ownAccountRowLock, never()).lockThenFind(100);
        verify(passwordService, never()).hash(anyString());
        verify(userRepository, never()).persist(user);
        assertEquals(0, user.sessionVersion);
    }

    /** La vigencia decide antes que la actual, tambien cuando la actual esta mal. */
    @Test
    void changePassword_withAnUnusableAccount_andTheWrongCurrent_throwsUserInactive() {
        user.worker.role.canLogin = false;
        when(userRepository.findPasswordHashByUserId(100)).thenReturn(Optional.of("hashed-password"));
        when(passwordService.matches("wrong-current", "hashed-password")).thenReturn(false);
        when(userRepository.findByIdOptional(100)).thenReturn(Optional.of(user));

        ApiException ex = assertThrows(ApiException.class,
            () -> authService.changePassword(new ChangePasswordCommand(100, "wrong-current", "newpass")));

        assertEquals(AuthError.USER_INACTIVE.code(), ex.code());
        verify(userRepository, never()).persist(user);
    }

    /** Con la actual correcta, la vigencia se mira sobre la fila tomada, antes de escribir. */
    @Test
    void changePassword_withAnUnusableAccount_andTheRightCurrent_throwsUserInactive_withoutWriting() {
        user.worker.role.canLogin = false;
        when(userRepository.findPasswordHashByUserId(100)).thenReturn(Optional.of("hashed-password"));
        when(passwordService.matches("current-plain", "hashed-password")).thenReturn(true);
        when(passwordService.hash("newpass")).thenReturn("new-hashed");
        when(ownAccountRowLock.lockThenFind(100)).thenReturn(Optional.of(user));

        ApiException ex = assertThrows(ApiException.class,
            () -> authService.changePassword(new ChangePasswordCommand(100, "current-plain", "newpass")));

        assertEquals(AuthError.USER_INACTIVE.code(), ex.code());
        assertEquals("hashed-password", user.passwordHash);
        verify(userRepository, never()).persist(user);
    }

    /** El hash de la fila tomada ya no es el verificado: otra escritura lo cambio en el medio. */
    @Test
    void changePassword_whenTheHashChangedBeforeTakingTheRow_throwsWrongCurrent_withoutWriting() {
        user.passwordHash = "hash-de-otra-escritura";
        when(userRepository.findPasswordHashByUserId(100)).thenReturn(Optional.of("hashed-password"));
        when(passwordService.matches("current-plain", "hashed-password")).thenReturn(true);
        when(passwordService.hash("newpass")).thenReturn("new-hashed");
        when(ownAccountRowLock.lockThenFind(100)).thenReturn(Optional.of(user));

        ApiException ex = assertThrows(ApiException.class,
            () -> authService.changePassword(new ChangePasswordCommand(100, "current-plain", "newpass")));

        assertEquals(AuthError.WRONG_CURRENT_PASSWORD.code(), ex.code());
        assertEquals("hash-de-otra-escritura", user.passwordHash);
        verify(userRepository, never()).persist(user);
    }

    // ============== la version de las sesiones ================================

    /** Sin el claim, el refresh es de antes de la version: vale solo mientras la cuenta siga en 0. */
    @Test
    void isCurrentSessionVersion_withoutTheClaim_isTheInitialVersion() {
        assertTrue(AuthService.isCurrentSessionVersion(null, user));
        user.sessionVersion = 1;
        assertFalse(AuthService.isCurrentSessionVersion(null, user));
    }

    /** Por igualdad: ni una anterior ni una que la cuenta todavia no alcanzo. */
    @Test
    void isCurrentSessionVersion_comparesForEquality() {
        user.sessionVersion = 3;
        assertTrue(AuthService.isCurrentSessionVersion(Json.createValue(3), user));
        assertFalse(AuthService.isCurrentSessionVersion(Json.createValue(2), user));
        assertFalse(AuthService.isCurrentSessionVersion(Json.createValue(4), user));
    }

    /** Un texto, o un numero con decimales aunque valga lo mismo, no es una version. */
    @Test
    void isCurrentSessionVersion_withAClaimThatIsNotAnInteger_isNotCurrent() {
        assertFalse(AuthService.isCurrentSessionVersion(Json.createValue("0"), user));
        assertFalse(AuthService.isCurrentSessionVersion(Json.createValue(new BigDecimal("0.0")), user));
        assertFalse(AuthService.isCurrentSessionVersion(JsonValue.TRUE, user));
    }

    // ============== findUserById =============================================

    @Test
    void findUserById_withExistingUser_returnsTheSessionUser() {
        when(userRepository.findByIdOptional(100)).thenReturn(Optional.of(user));
        when(authServiceMapper.toSessionUserResponse(user)).thenReturn(sessionUserResponse);

        SessionUserResponse result = authService.findUserById(100);

        assertSame(sessionUserResponse, result);
    }

    @Test
    void findUserById_withNonexistentUser_throwsUserNotFound() {
        when(userRepository.findByIdOptional(999)).thenReturn(Optional.empty());

        ApiException ex = assertThrows(ApiException.class,
            () -> authService.findUserById(999));

        assertEquals(AuthError.USER_NOT_FOUND.code(), ex.code());
    }

    @Test
    void findUserById_withAnUnusableAccount_throwsUserInactive() {
        user.isActive = false;
        when(userRepository.findByIdOptional(100)).thenReturn(Optional.of(user));

        ApiException ex = assertThrows(ApiException.class,
            () -> authService.findUserById(100));

        assertEquals(AuthError.USER_INACTIVE.code(), ex.code());
        verify(authServiceMapper, never()).toSessionUserResponse(user);
    }

    // ============== helpers ==================================================

    private JsonWebToken mockParsedToken(String typeClaim) {
        JsonWebToken token = org.mockito.Mockito.mock(JsonWebToken.class);
        when(token.getClaim(TokenService.CLAIM_TYPE)).thenReturn(typeClaim);
        return token;
    }
}
