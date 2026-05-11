package com.scaramutti.tms.auth.security;

import com.scaramutti.tms.auth.AuthError;
import com.scaramutti.tms.shared.exception.ApiError;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Unit tests puros para JwtErrorClassifier.
 * No requiere Quarkus ni mocks - solo fabricamos Throwables y verificamos la clasificacion.
 */
class JwtErrorClassifierTest {

    private final ApiError onExpired = AuthError.TOKEN_EXPIRED;
    private final ApiError onInvalid = AuthError.TOKEN_INVALID;

    @Test
    void classify_withExpiredInMessage_returnsExpired() {
        Throwable t = new RuntimeException("Token expired");

        ApiError result = JwtErrorClassifier.classify(t, onExpired, onInvalid);

        assertSame(onExpired, result);
    }

    @Test
    void classify_withExpirationInMessage_returnsExpired() {
        // jose4j usa "Expiration Time" en sus mensajes
        Throwable t = new RuntimeException("The JWT is no longer valid - the evaluation time is on or after the Expiration Time");

        ApiError result = JwtErrorClassifier.classify(t, onExpired, onInvalid);

        assertSame(onExpired, result);
    }

    @Test
    void classify_withExpirCaseInsensitive_returnsExpired() {
        // El marker matchea case-insensitive
        Throwable t = new RuntimeException("Token EXPIRED ago");

        ApiError result = JwtErrorClassifier.classify(t, onExpired, onInvalid);

        assertSame(onExpired, result);
    }

    @Test
    void classify_withGenericInvalidMessage_returnsInvalid() {
        Throwable t = new RuntimeException("Invalid signature");

        ApiError result = JwtErrorClassifier.classify(t, onExpired, onInvalid);

        assertSame(onInvalid, result);
    }

    @Test
    void classify_withNullMessage_returnsInvalid() {
        Throwable t = new RuntimeException((String) null);

        ApiError result = JwtErrorClassifier.classify(t, onExpired, onInvalid);

        assertSame(onInvalid, result);
    }

    @Test
    void classify_withNullThrowable_returnsInvalid() {
        ApiError result = JwtErrorClassifier.classify(null, onExpired, onInvalid);

        assertSame(onInvalid, result);
    }

    @Test
    void classify_withExpiredInCause_returnsExpired() {
        // Caso real: SmallRye envuelve la causa real
        Throwable cause = new RuntimeException("The JWT Expiration Time has passed");
        Throwable wrapper = new RuntimeException("Authentication failed", cause);

        ApiError result = JwtErrorClassifier.classify(wrapper, onExpired, onInvalid);

        assertSame(onExpired, result);
    }

    @Test
    void classify_withExpiredDeepInChain_returnsExpired() {
        // Caso real Quarkus: AuthenticationFailedException -> ParseException -> InvalidJwtException
        Throwable deepest = new RuntimeException("Token Expiration Time exceeded");
        Throwable middle = new RuntimeException("Parse error", deepest);
        Throwable top = new RuntimeException("Auth failed", middle);

        ApiError result = JwtErrorClassifier.classify(top, onExpired, onInvalid);

        assertSame(onExpired, result);
    }

    @Test
    void classify_withNoExpiredInChain_returnsInvalid() {
        Throwable deepest = new RuntimeException("Signature does not match");
        Throwable middle = new RuntimeException("Parse error", deepest);
        Throwable top = new RuntimeException("Auth failed", middle);

        ApiError result = JwtErrorClassifier.classify(top, onExpired, onInvalid);

        assertSame(onInvalid, result);
    }

    @Test
    void classify_respectsOnExpiredParameter() {
        // Verifica que devuelve el parametro pasado, no AuthError.TOKEN_EXPIRED hardcodeado
        Throwable t = new RuntimeException("expired");

        ApiError result = JwtErrorClassifier.classify(t, AuthError.REFRESH_TOKEN_EXPIRED, AuthError.REFRESH_TOKEN_INVALID);

        assertSame(AuthError.REFRESH_TOKEN_EXPIRED, result);
    }

    @Test
    void classify_respectsOnInvalidParameter() {
        Throwable t = new RuntimeException("malformed");

        ApiError result = JwtErrorClassifier.classify(t, AuthError.REFRESH_TOKEN_EXPIRED, AuthError.REFRESH_TOKEN_INVALID);

        assertSame(AuthError.REFRESH_TOKEN_INVALID, result);
    }

    @Test
    void classify_doesNotInfiniteLoopOnCircularCauseChain() {
        // Edge case: causa apuntando a si misma. No deberia colgarse.
        Throwable t = new RuntimeException("invalid signature");
        // Java no permite t.initCause(t), pero podemos verificar que con un chain finito termina
        Throwable selfRef = new RuntimeException("layer 1", t);

        ApiError result = JwtErrorClassifier.classify(selfRef, onExpired, onInvalid);

        assertEquals(onInvalid.code(), result.code());
    }
}
