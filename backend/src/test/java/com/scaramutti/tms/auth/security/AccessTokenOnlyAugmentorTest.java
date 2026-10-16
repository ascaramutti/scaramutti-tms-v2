package com.scaramutti.tms.auth.security;

import io.quarkus.security.AuthenticationFailedException;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.runtime.QuarkusPrincipal;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit de la regla de la credencial: solo pasan la identidad anónima y la del token de acceso.
 * Se arma la identidad a mano, sin levantar la aplicación.
 */
class AccessTokenOnlyAugmentorTest {

    private final AccessTokenOnlyAugmentor augmentor = new AccessTokenOnlyAugmentor();

    private static SecurityIdentity jwtIdentity(Object typeClaim) {
        JsonWebToken token = mock(JsonWebToken.class);
        when(token.getName()).thenReturn("admin");
        when(token.getClaim("typ")).thenReturn(typeClaim);
        return QuarkusSecurityIdentity.builder().setPrincipal(token).build();
    }

    private SecurityIdentity augment(SecurityIdentity identity) {
        return augmentor.augment(identity, null).await().indefinitely();
    }

    /** Lleva un token que no es de acceso: sin la guarda del anónimo, lo rechazaría. */
    @Test
    void anonymousIdentity_passesUntouched() {
        JsonWebToken token = mock(JsonWebToken.class);
        when(token.getClaim("typ")).thenReturn("refresh");
        SecurityIdentity anonymous = QuarkusSecurityIdentity.builder().setAnonymous(true).setPrincipal(token).build();

        assertSame(anonymous, augment(anonymous));
    }

    /** Falla cerrado: una identidad que no viene de un JWT no esquiva la regla. */
    @Test
    void identityNotBuiltFromAJwt_failsAuthentication() {
        assertRejected(QuarkusSecurityIdentity.builder().setPrincipal(new QuarkusPrincipal("someone")).build());
    }

    @Test
    void accessToken_passesUntouched() {
        SecurityIdentity access = jwtIdentity("access");

        assertSame(access, augment(access));
    }

    @Test
    void refreshToken_failsAuthentication() {
        assertRejected(jwtIdentity("refresh"));
    }

    @Test
    void tokenWithoutTypeClaim_failsAuthentication() {
        assertRejected(jwtIdentity(null));
    }

    @Test
    void tokenWithTheTypeInAnotherCase_failsAuthentication() {
        assertRejected(jwtIdentity("ACCESS"));
    }

    @Test
    void tokenWithSomethingMoreThanTheType_failsAuthentication() {
        assertRejected(jwtIdentity("accessx"));
    }

    /** Con "expir" en el mensaje, el clasificador respondería token vencido en vez de inválido. */
    @Test
    void rejectionMessage_isNotReadAsAnExpiredToken() {
        assertFalse(AccessTokenOnlyAugmentor.NOT_AN_ACCESS_TOKEN.toLowerCase().contains("expir"));
    }

    /** Se mira el mensaje que de verdad se lanza, no solo la constante. */
    private void assertRejected(SecurityIdentity identity) {
        RuntimeException failure = assertThrows(RuntimeException.class, () -> augment(identity));
        assertInstanceOf(AuthenticationFailedException.class, failure);
        assertEquals(AccessTokenOnlyAugmentor.NOT_AN_ACCESS_TOKEN, failure.getMessage());
    }
}
