package com.scaramutti.tms.auth.service;

import com.scaramutti.tms.auth.AuthError;
import com.scaramutti.tms.auth.dto.LoginResponse;
import com.scaramutti.tms.auth.dto.UserResponse;
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
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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

    @InjectMocks AuthService authService;

    private User user;
    private UserResponse userResponse;
    private AccessToken accessToken;
    private LoginResponse loginResponse;

    @BeforeEach
    void setUp() {
        Role role = new Role();
        role.id = 1;
        role.name = "sales";

        Worker worker = new Worker();
        worker.id = 10;
        worker.firstName = "Luraidis";
        worker.lastName = "Campos";
        worker.position = "Ejecutiva de Ventas";

        user = new User();
        user.id = 100;
        user.username = "lcampos";
        user.passwordHash = "hashed-password";
        user.worker = worker;
        user.role = role;
        user.isActive = true;

        userResponse = new UserResponse(100, "lcampos", "Luraidis Campos", "Ejecutiva de Ventas", "sales", true);
        accessToken = new AccessToken("access.jwt.token", Instant.now().plusSeconds(3600), 3600L);
        loginResponse = new LoginResponse(
            "access.jwt.token", "refresh.jwt.token",
            OffsetDateTime.now().plusSeconds(3600), 3600L, userResponse
        );
    }

    // ============== login ====================================================

    @Test
    void login_withValidCredentials_returnsLoginResponse() {
        when(userRepository.findByUsername("lcampos")).thenReturn(Optional.of(user));
        when(passwordService.matches("plain-password", "hashed-password")).thenReturn(true);
        when(tokenService.createAccessToken(user)).thenReturn(accessToken);
        when(tokenService.createRefreshToken(user)).thenReturn("refresh.jwt.token");
        when(authServiceMapper.toLoginResponse(user, accessToken, "refresh.jwt.token")).thenReturn(loginResponse);

        LoginResponse result = authService.login(new LoginCommand("lcampos", "plain-password"));

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
        when(userRepository.findByUsername("lcampos")).thenReturn(Optional.of(user));
        when(passwordService.matches("wrong-password", "hashed-password")).thenReturn(false);

        ApiException ex = assertThrows(ApiException.class,
            () -> authService.login(new LoginCommand("lcampos", "wrong-password")));

        assertEquals(AuthError.INVALID_CREDENTIALS.code(), ex.code());
        verify(tokenService, never()).createAccessToken(eq(user));
    }

    @Test
    void login_withInactiveUser_throwsUserInactive() {
        user.isActive = false;
        when(userRepository.findByUsername("lcampos")).thenReturn(Optional.of(user));
        when(passwordService.matches("plain-password", "hashed-password")).thenReturn(true);

        ApiException ex = assertThrows(ApiException.class,
            () -> authService.login(new LoginCommand("lcampos", "plain-password")));

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

    // ============== changePassword ===========================================

    @Test
    void changePassword_withCorrectCurrent_persistsNewHash() {
        when(userRepository.findByIdOptional(100)).thenReturn(Optional.of(user));
        when(passwordService.matches("current-plain", "hashed-password")).thenReturn(true);
        when(passwordService.hash("new-plain")).thenReturn("new-hashed");

        authService.changePassword(new ChangePasswordCommand(100, "current-plain", "new-plain"));

        assertEquals("new-hashed", user.passwordHash);
        verify(userRepository).persist(user);
    }

    @Test
    void changePassword_withNonexistentUser_throwsUserNotFound() {
        when(userRepository.findByIdOptional(999)).thenReturn(Optional.empty());

        ApiException ex = assertThrows(ApiException.class,
            () -> authService.changePassword(new ChangePasswordCommand(999, "any", "newpass")));

        assertEquals(AuthError.USER_NOT_FOUND.code(), ex.code());
    }

    @Test
    void changePassword_withWrongCurrent_throwsAndDoesNotPersist() {
        when(userRepository.findByIdOptional(100)).thenReturn(Optional.of(user));
        when(passwordService.matches("wrong-current", "hashed-password")).thenReturn(false);

        ApiException ex = assertThrows(ApiException.class,
            () -> authService.changePassword(new ChangePasswordCommand(100, "wrong-current", "newpass")));

        assertEquals(AuthError.WRONG_CURRENT_PASSWORD.code(), ex.code());
        verify(passwordService, never()).hash(anyString());
        verify(userRepository, never()).persist(user);
    }

    // ============== findUserById =============================================

    @Test
    void findUserById_withExistingUser_returnsUserResponse() {
        when(userRepository.findByIdOptional(100)).thenReturn(Optional.of(user));
        when(authServiceMapper.toUserResponse(user)).thenReturn(userResponse);

        UserResponse result = authService.findUserById(100);

        assertSame(userResponse, result);
    }

    @Test
    void findUserById_withNonexistentUser_throwsUserNotFound() {
        when(userRepository.findByIdOptional(999)).thenReturn(Optional.empty());

        ApiException ex = assertThrows(ApiException.class,
            () -> authService.findUserById(999));

        assertEquals(AuthError.USER_NOT_FOUND.code(), ex.code());
    }

    // ============== helpers ==================================================

    private JsonWebToken mockParsedToken(String typeClaim) {
        JsonWebToken token = org.mockito.Mockito.mock(JsonWebToken.class);
        when(token.getClaim(TokenService.CLAIM_TYPE)).thenReturn(typeClaim);
        return token;
    }
}
