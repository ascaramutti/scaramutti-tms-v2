package com.scaramutti.tms.auth.security;

import org.eclipse.microprofile.jwt.JsonWebToken;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit de la consulta de roles del contexto de seguridad. Lo que se fija acá es la promesa que
 * encabeza el método: DENIEGA POR DEFECTO. Es la rama que decide, entre otras cosas, quién ve
 * los precios de un viaje, así que un token sin roles tiene que dar "no", nunca "sí".
 *
 * Se instancia a mano con el token simulado, sin levantar la aplicación: el método es una
 * función del contenido del token.
 */
class CurrentUserTest {

    private static final Set<String> PRICE_ROLES = Set.of("admin", "sales");

    private static CurrentUser withGroups(Set<String> groups) {
        JsonWebToken token = mock(JsonWebToken.class);
        when(token.getGroups()).thenReturn(groups);
        CurrentUser currentUser = new CurrentUser();
        currentUser.jsonWebToken = token;
        return currentUser;
    }

    @Test
    void hasAnyRole_withoutToken_deniesInsteadOfGranting() {
        CurrentUser currentUser = new CurrentUser();
        currentUser.jsonWebToken = null;

        assertFalse(currentUser.hasAnyRole(PRICE_ROLES));
    }

    @Test
    void hasAnyRole_withoutGroupsClaim_denies() {
        assertFalse(withGroups(null).hasAnyRole(PRICE_ROLES));
    }

    @Test
    void hasAnyRole_withEmptyGroups_denies() {
        assertFalse(withGroups(Set.of()).hasAnyRole(PRICE_ROLES));
    }

    /** Un rol que nadie agregó a la lista NO hereda el permiso. */
    @Test
    void hasAnyRole_withUnlistedRole_denies() {
        assertFalse(withGroups(Set.of("dispatcher")).hasAnyRole(PRICE_ROLES));
    }

    /** El rol se compara literal: otra grafía no es el mismo rol. */
    @Test
    void hasAnyRole_isCaseSensitive() {
        assertFalse(withGroups(Set.of("ADMIN")).hasAnyRole(PRICE_ROLES));
    }

    @Test
    void hasAnyRole_withListedRole_grants() {
        assertTrue(withGroups(Set.of("admin")).hasAnyRole(PRICE_ROLES));
    }

    @Test
    void hasAnyRole_withSeveralRolesAndOneListed_grants() {
        assertTrue(withGroups(Set.of("dispatcher", "sales")).hasAnyRole(PRICE_ROLES));
    }

    /** Un elemento nulo en el claim no puede tumbar el chequeo: se ignora. */
    @Test
    void hasAnyRole_withNullElementInGroups_doesNotBlowUp() {
        Set<String> groupsWithNull = new HashSet<>(Arrays.asList("admin", null));

        assertTrue(withGroups(groupsWithNull).hasAnyRole(PRICE_ROLES));
        assertFalse(withGroups(new HashSet<>(Arrays.asList((String) null))).hasAnyRole(PRICE_ROLES));
    }
}
