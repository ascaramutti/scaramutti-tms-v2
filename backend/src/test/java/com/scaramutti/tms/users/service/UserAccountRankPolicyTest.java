package com.scaramutti.tms.users.service;

import com.scaramutti.tms.auth.security.CurrentUser;
import com.scaramutti.tms.shared.entity.Role;
import com.scaramutti.tms.shared.entity.User;
import com.scaramutti.tms.shared.entity.Worker;
import com.scaramutti.tms.shared.exception.ApiException;
import com.scaramutti.tms.shared.repository.UserRepository;
import com.scaramutti.tms.users.api.UserAccountResource;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.annotation.Annotation;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * La regla del rango de las cuentas sin base: la matriz de niveles entera y quien escribe releido,
 * que por HTTP no se distingue del corte de antes de tomar la fila (los dos leen lo mismo).
 */
@ExtendWith(MockitoExtension.class)
class UserAccountRankPolicyTest {

    @Mock CurrentUser currentUser;
    @Mock UserRepository userRepository;
    @InjectMocks UserAccountRankPolicy userAccountRankPolicy;

    private static Role role(String name, int level) {
        Role role = new Role();
        role.name = name;
        role.level = (short) level;
        return role;
    }

    private User actorIs(String roleName, int level) {
        User actor = new User();
        actor.id = 7;
        actor.isActive = true;
        actor.worker = new Worker();
        actor.worker.id = 99;
        actor.worker.isActive = true;
        actor.worker.role = role(roleName, level);
        when(currentUser.requireId()).thenReturn(7);
        when(userRepository.findByIdOptional(7)).thenReturn(Optional.of(actor));
        return actor;
    }

    @Test
    void theWriteRoles_areExactlyTheRolesAllowedOfEveryWriteEndpoint() {
        Set<Class<? extends Annotation>> writeVerbs = Set.of(POST.class, PUT.class, PATCH.class, DELETE.class);
        int checked = 0;
        for (var method : UserAccountResource.class.getDeclaredMethods()) {
            if (writeVerbs.stream().noneMatch(method::isAnnotationPresent)) {
                continue;
            }
            RolesAllowed allowed = method.getAnnotation(RolesAllowed.class);
            assertNotNull(allowed, method.getName() + " escribe sin @RolesAllowed");
            assertEquals(UserAccountRankPolicy.WRITE_ROLES, Set.of(allowed.value()), method.getName());
            checked++;
        }
        assertEquals(3, checked, "desactivar, reactivar y restablecer");
    }

    @ParameterizedTest(name = "nivel {0} sobre nivel {1} -> permitido {2}")
    @CsvSource({
        "3, 2, true",
        "3, 3, false",
        "2, 3, false",
        "2, 1, true",
        "2, 2, false",
        "3, 4, false",
    })
    void assertCanActOn_allowsOnlyStrictlyLowerLevels(int actorLevel, int accountLevel, boolean allowed) {
        actorIs("general_manager", actorLevel);

        if (allowed) {
            assertDoesNotThrow(() -> userAccountRankPolicy.assertCanActOn(role("cuenta", accountLevel)));
        } else {
            ApiException thrown = assertThrows(ApiException.class,
                () -> userAccountRankPolicy.assertCanActOn(role("cuenta", accountLevel)));
            assertEquals("USR-008", thrown.code());
        }
    }

    @Test
    void assertCanActOn_admin_passesOverItsOwnLevelAndAbove() {
        actorIs("admin", 4);

        assertDoesNotThrow(() -> userAccountRankPolicy.assertCanActOn(role("admin", 4)));
        assertDoesNotThrow(() -> userAccountRankPolicy.assertCanActOn(role("cuenta", 9)));
    }

    /** La exencion es del cargo de quien escribe, no de un nivel alto: otro cargo de nivel 4 no la tiene. */
    @Test
    void assertCanActOn_theExemptionIsTheRoleName_notItsLevel() {
        actorIs("general_manager", 4);

        ApiException thrown = assertThrows(ApiException.class,
            () -> userAccountRankPolicy.assertCanActOn(role("admin", 4)));
        assertEquals("USR-008", thrown.code());
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"cuenta apagada", "trabajador de baja", "cargo que no escribe"})
    void assertCanActOn_withTheActorNoLongerEnabled_isForbidden_evenWithTheExemptRole(String why) {
        User actor = actorIs("admin", 4);
        Consumer<User> disable = switch (why) {
            case "cuenta apagada" -> user -> user.isActive = false;
            case "trabajador de baja" -> user -> user.worker.isActive = false;
            default -> user -> user.worker.role = role("sales", 4);
        };
        disable.accept(actor);

        ApiException thrown = assertThrows(ApiException.class,
            () -> userAccountRankPolicy.assertCanActOn(role("cuenta", 1)));
        assertEquals("COM-003", thrown.code());
    }

    @Test
    void assertCanActOn_withoutTheActorAccount_isForbidden() {
        when(currentUser.requireId()).thenReturn(7);
        when(userRepository.findByIdOptional(7)).thenReturn(Optional.empty());

        ApiException thrown = assertThrows(ApiException.class,
            () -> userAccountRankPolicy.assertCanActOn(role("cuenta", 1)));
        assertEquals("COM-003", thrown.code());
    }

    @Test
    void rejectDisabledActorBeforeLocking_asksWithTheWriteRoles() {
        when(currentUser.requireId()).thenReturn(7);
        when(userRepository.isEnabledToWrite(eq(7), eq(UserAccountRankPolicy.WRITE_ROLES))).thenReturn(false);

        ApiException thrown = assertThrows(ApiException.class,
            () -> userAccountRankPolicy.rejectDisabledActorBeforeLocking());
        assertEquals("COM-003", thrown.code());
    }

    @Test
    void assertIsNotOwnAccount_comparesTheAccountWithTheSessionSubject() {
        when(currentUser.requireId()).thenReturn(7);

        ApiException thrown = assertThrows(ApiException.class, () -> userAccountRankPolicy.assertIsNotOwnAccount(7));
        assertEquals("USR-007", thrown.code());
        assertDoesNotThrow(() -> userAccountRankPolicy.assertIsNotOwnAccount(8));
    }
}
