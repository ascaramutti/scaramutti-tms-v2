package com.scaramutti.tms.operations.service;

import com.scaramutti.tms.auth.security.CurrentUser;
import com.scaramutti.tms.operations.model.ServiceStatusTransition;
import com.scaramutti.tms.shared.exception.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * El veto por transicion, medido donde SI se puede medir.
 *
 * <p>La mitad que exige pertenecer a los roles operativos es inalcanzable por HTTP: la anotacion
 * del endpoint garantiza que el token traiga uno de los cuatro, asi que ningun test de integracion
 * la distingue — borrarla los deja a todos en verde. Y esa mitad es justamente la que evita que el
 * veto sea FAIL-OPEN: {@code hasAnyRole} devuelve false sin claim de roles, y en una lista negativa
 * ese false significa "pasa".
 *
 * <p>Con el componente aislado y el usuario simulado, el caso se vuelve alcanzable.
 */
class ServiceStatusChangeAuthorizationTest {

    private CurrentUser currentUser;
    private ServiceStatusChangeAuthorization authorization;

    @BeforeEach
    void setUp() {
        currentUser = mock(CurrentUser.class);
        authorization = new ServiceStatusChangeAuthorization();
        authorization.currentUser = currentUser;
    }

    /**
     * El caso que ningun test de integracion puede montar: un principal SIN claim de roles. Si
     * llegara —un token de servicio, o el dia que el acceso se resuelva por configuracion en vez
     * de por anotacion— el veto solo lo frena si ademas se exige pertenecer a los operativos.
     */
    @ParameterizedTest
    @EnumSource(ServiceStatusTransition.class)
    void requireCanRequest_withoutAnyRoleClaim_isDenied(ServiceStatusTransition transition) {
        when(currentUser.hasAnyRole(org.mockito.ArgumentMatchers.any())).thenReturn(false);

        ApiException rejected = assertThrows(ApiException.class,
            () -> authorization.requireCanRequest(transition));

        assertEquals(403, rejected.status(),
            "sin claim de roles el veto se abre solo: es fail-open y falla hacia el lado malo");
    }

    /** Y con un rol operativo y sin veto, pasa: la mitad nueva no cerro lo que tenia que dejar. */
    @Test
    void requireCanRequest_withAnOperatingRoleAndNoVeto_isAllowed() {
        when(currentUser.hasAnyRole(ServiceStatusChangeAuthorizationRolesFixture.OPERATING))
            .thenReturn(true);

        assertDoesNotThrow(() -> authorization.requireCanRequest(ServiceStatusTransition.IN_PROGRESS),
            "un rol operativo sin veto tiene que poder pedir la transicion");
    }

    /** El veto gana aunque el rol operativo este presente: es lista negativa, no suma de permisos. */
    @Test
    void requireCanRequest_withAVetoedRole_isDeniedEvenWhenItAlsoOperates() {
        when(currentUser.hasAnyRole(ServiceStatusChangeAuthorizationRolesFixture.OPERATING))
            .thenReturn(true);
        when(currentUser.hasAnyRole(ServiceStatusTransition.CANCELLED.vetoedRoles()))
            .thenReturn(true);

        ApiException rejected = assertThrows(ApiException.class,
            () -> authorization.requireCanRequest(ServiceStatusTransition.CANCELLED));

        assertEquals(403, rejected.status());
    }

    /** Alias local para no depender del orden de los argumentos simulados. */
    private static final class ServiceStatusChangeAuthorizationRolesFixture {
        static final Set<String> OPERATING =
            com.scaramutti.tms.operations.model.ServiceStatusChangeAuthorizationRoles.OPERATING_ROLES;
    }
}
