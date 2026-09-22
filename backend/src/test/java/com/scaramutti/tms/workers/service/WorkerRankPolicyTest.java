package com.scaramutti.tms.workers.service;

import com.scaramutti.tms.auth.security.CurrentUser;
import com.scaramutti.tms.shared.entity.Role;
import com.scaramutti.tms.shared.entity.User;
import com.scaramutti.tms.shared.exception.ApiException;
import com.scaramutti.tms.shared.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests de la regla del organigrama.
 *
 * <p>Van aca y no solo por HTTP porque la matriz de niveles se ejercita entera sin sembrar
 * ocho usuarios en una base compartida. Los cuatro casos fijos de la historia se miden TAMBIEN
 * por recurso, donde el nivel viene de la base de verdad.
 */
@ExtendWith(MockitoExtension.class)
class WorkerRankPolicyTest {

    @Mock CurrentUser currentUser;
    @Mock UserRepository userRepository;
    @InjectMocks WorkerRankPolicy workerRankPolicy;

    private Role role(String name, int level) {
        Role role = new Role();
        role.name = name;
        role.level = (short) level;
        return role;
    }

    private void actorIs(String roleName, int level) {
        User actor = new User();
        actor.id = 7;
        actor.isActive = true;
        actor.role = role(roleName, level);
        when(currentUser.requireId()).thenReturn(7);
        when(userRepository.findByIdOptional(7)).thenReturn(Optional.of(actor));
    }

    @ParameterizedTest(name = "actor nivel {0} sobre cargo nivel {1} -> permitido {2}")
    @CsvSource({
        "3, 2, true",
        "3, 3, false",
        "2, 3, false",
        "2, 1, true",
        "2, 2, false",
    })
    void assertCanActOn_allowsOnlyStrictlyLowerLevels(int actorLevel, int targetLevel, boolean allowed) {
        actorIs("general_manager", actorLevel);

        if (allowed) {
            assertDoesNotThrow(() -> workerRankPolicy.assertCanActOn(role("target", targetLevel)));
        } else {
            ApiException thrown = assertThrows(ApiException.class,
                () -> workerRankPolicy.assertCanActOn(role("target", targetLevel)));
            assertEquals("WRK-006", thrown.code());
        }
    }

    /**
     * El administrador actua sobre cualquier cargo, incluido uno de su mismo nivel.
     *
     * <p>El objetivo se llama DISTINTO que el actor a proposito: si los dos se llamaran
     * "admin", mirar el nombre del objetivo en vez del nombre del actor daria el mismo
     * resultado y la clase entera no notaria el cambio.
     */
    @Test
    void assertCanActOn_admin_passesEvenOverItsOwnLevel() {
        actorIs("admin", 4);

        assertDoesNotThrow(() -> workerRankPolicy.assertCanActOn(role("general_manager", 4)));
    }

    /** Y el objetivo llamado "admin" NO exime a quien no lo es. */
    @Test
    void assertCanActOn_aTargetNamedAdmin_doesNotExemptTheActor() {
        actorIs("general_manager", 3);

        ApiException thrown = assertThrows(ApiException.class,
            () -> workerRankPolicy.assertCanActOn(role("admin", 4)));
        assertEquals("WRK-006", thrown.code());
    }

    /**
     * El nivel sale de la FILA del usuario y no de los grupos del token: el token no lo lleva,
     * y su grupo puede estar viejo porque cambiar el cargo de alguien le cambia el rol sin
     * reemitirle la sesion.
     */
    @Test
    void theActorRole_isReadFromTheRepository_neverFromTheToken() {
        actorIs("finance_manager", 2);

        workerRankPolicy.assertCanActOn(role("driver", 1));

        verify(userRepository).findByIdOptional(7);
    }

    /**
     * Una sesion cuyo sujeto no resuelve a ningun usuario no es un caso de negocio: no hay
     * actor. Sale por el 403 comun y NO por un codigo del modulo, que ademas obligaria a este
     * paquete a importar el catalogo de errores de autenticacion.
     */
    @Test
    void assertCanActOn_whenTheActorUserDoesNotExist_denies() {
        when(currentUser.requireId()).thenReturn(7);
        when(userRepository.findByIdOptional(7)).thenReturn(Optional.empty());

        ApiException thrown = assertThrows(ApiException.class,
            () -> workerRankPolicy.assertCanActOn(role("operator", 1)));
        assertEquals("COM-003", thrown.code());
    }

    /** El cuerpo del rechazo no lleva cargo, ni nivel, ni id, ni del actor ni del objetivo. */
    @Test
    void theForbiddenDetail_carriesNoRoleNorLevel() {
        actorIs("finance_manager", 2);

        ApiException sameLevel = assertThrows(ApiException.class,
            () -> workerRankPolicy.assertCanActOn(role("sales", 2)));
        ApiException higherLevel = assertThrows(ApiException.class,
            () -> workerRankPolicy.assertCanActOn(role("general_manager", 3)));

        assertEquals(sameLevel.getMessage(), higherLevel.getMessage());
        assertEquals("No puedes dar de alta ni modificar trabajadores de ese cargo",
            sameLevel.getMessage());
        // Y sin datos propios en el cuerpo: ni cargo, ni nivel, ni id.
        assertEquals(Map.of(), sameLevel.extensions());
    }

    /**
     * Un usuario dado de baja no es actor aunque su fila exista y su token siga vigente. Darlo
     * de baja no revoca el token que ya tenia en la mano.
     */
    @Test
    void assertCanActOn_whenTheActorUserIsInactive_denies() {
        User inactive = new User();
        inactive.id = 7;
        inactive.isActive = false;
        inactive.role = role("general_manager", 3);
        when(currentUser.requireId()).thenReturn(7);
        when(userRepository.findByIdOptional(7)).thenReturn(Optional.of(inactive));

        ApiException thrown = assertThrows(ApiException.class,
            () -> workerRankPolicy.assertCanActOn(role("operator", 1)));
        assertEquals("COM-003", thrown.code());
    }

}
