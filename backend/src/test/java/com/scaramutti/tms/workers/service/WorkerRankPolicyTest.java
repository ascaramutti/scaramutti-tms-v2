package com.scaramutti.tms.workers.service;

import com.scaramutti.tms.auth.security.CurrentUser;
import com.scaramutti.tms.shared.entity.Role;
import com.scaramutti.tms.shared.entity.User;
import com.scaramutti.tms.shared.entity.Worker;
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
        assertEquals("No puedes gestionar trabajadores de ese cargo",
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


    // ---------- nadie se cambia su propio cargo ----------

    private void actorOwnsWorker(Worker worker, String roleName, int level) {
        User actor = new User();
        actor.id = 7;
        actor.isActive = true;
        actor.role = role(roleName, level);
        actor.worker = worker;
        when(currentUser.requireId()).thenReturn(7);
        when(userRepository.findByIdOptional(7)).thenReturn(Optional.of(actor));
    }

    private Worker workerWith(int id, Role role) {
        Worker worker = new Worker();
        worker.id = id;
        worker.role = role;
        return worker;
    }

    /** La cuenta del sistema del trabajador que se edita: la OTRA fila que la edicion mueve. */
    private User accountWith(int id, Role role) {
        User account = new User();
        account.id = id;
        account.isActive = true;
        account.role = role;
        return account;
    }

    @Test
    void assertCanChangeRoleOf_whenItIsItsOwnWorker_andTheRoleChanges_throwsWRK012() {
        Role adminRole = role("admin", 4);
        adminRole.id = 1;
        Worker own = workerWith(10, adminRole);
        actorOwnsWorker(own, "admin", 4);
        Role other = role("general_manager", 3);
        other.id = 2;

        ApiException thrown = assertThrows(ApiException.class,
            () -> workerRankPolicy.assertCanChangeRoleOf(own, accountWith(7, own.role), other));
        assertEquals("WRK-012", thrown.code());
        assertEquals(403, thrown.status());
    }

    /** El mismo cargo sobre uno mismo pasa: lo cerrado es el CAMBIO, no editarse. */
    @Test
    void assertCanChangeRoleOf_whenItIsItsOwnWorker_andTheRoleIsTheSame_passes() {
        Role adminRole = role("admin", 4);
        adminRole.id = 1;
        Worker own = workerWith(10, adminRole);
        actorOwnsWorker(own, "admin", 4);
        Role same = role("admin", 4);
        same.id = 1;

        assertDoesNotThrow(
            () -> workerRankPolicy.assertCanChangeRoleOf(own, accountWith(7, own.role), same));
    }

    /** Sobre el trabajador de OTRO no aplica, aunque el actor sea administrador. */
    @Test
    void assertCanChangeRoleOf_whenItIsSomeoneElse_passes() {
        Role adminRole = role("admin", 4);
        adminRole.id = 1;
        actorOwnsWorker(workerWith(10, adminRole), "admin", 4);
        Role target = role("sales", 2);
        target.id = 3;
        Role newRole = role("driver", 1);
        newRole.id = 4;

        assertDoesNotThrow(() -> workerRankPolicy.assertCanChangeRoleOf(
            workerWith(11, target), accountWith(99, target), newRole));
    }

    /**
     * LA FILA DE LA CUENTA TAMBIEN. Es el caso que separa esta guarda de la que tenia antes: el
     * cuerpo reenvia el cargo del TRABAJADOR sin cambios —asi que mirando solo esa fila no pasa
     * nada— mientras la cascada mueve el de la cuenta, que es la que otorga los permisos. Con la
     * guarda vieja esto devolvia 200 y el administrador se degradaba solo.
     */
    @Test
    void assertCanChangeRoleOf_whenOnlyItsOwnAccountRoleWouldMove_throwsWRK012() {
        Role generalManager = role("general_manager", 3);
        generalManager.id = 2;
        Role adminRole = role("admin", 4);
        adminRole.id = 1;
        Worker own = workerWith(10, generalManager);
        actorOwnsWorker(own, "admin", 4);

        ApiException thrown = assertThrows(ApiException.class,
            () -> workerRankPolicy.assertCanChangeRoleOf(own, accountWith(7, adminRole),
                generalManager));
        assertEquals("WRK-012", thrown.code());
        assertEquals(403, thrown.status());
    }

    /**
     * Y al reves: si lo que se mueve es el cargo del TRABAJADOR mientras la cuenta ya esta en el
     * nuevo, tampoco pasa. Las dos filas estan cerradas, no una.
     */
    @Test
    void assertCanChangeRoleOf_whenOnlyItsOwnWorkerRoleWouldMove_throwsWRK012() {
        Role adminRole = role("admin", 4);
        adminRole.id = 1;
        Role generalManager = role("general_manager", 3);
        generalManager.id = 2;
        Worker own = workerWith(10, adminRole);
        actorOwnsWorker(own, "admin", 4);

        ApiException thrown = assertThrows(ApiException.class,
            () -> workerRankPolicy.assertCanChangeRoleOf(own, accountWith(7, generalManager),
                generalManager));
        assertEquals("WRK-012", thrown.code());
    }

    /** Con las dos filas ya en el cargo pedido no hay nada que mover, y editarse sigue permitido. */
    @Test
    void assertCanChangeRoleOf_whenNeitherRowWouldMove_passes() {
        Role adminRole = role("admin", 4);
        adminRole.id = 1;
        Worker own = workerWith(10, adminRole);
        actorOwnsWorker(own, "admin", 4);
        Role same = role("admin", 4);
        same.id = 1;

        assertDoesNotThrow(
            () -> workerRankPolicy.assertCanChangeRoleOf(own, accountWith(7, adminRole), same));
    }

    /** Un trabajador SIN cuenta no tiene esa segunda fila, y la regla no se la inventa. */
    @Test
    void assertCanChangeRoleOf_whenItsOwnWorkerHasNoAccount_looksOnlyAtTheWorkerRow() {
        Role adminRole = role("admin", 4);
        adminRole.id = 1;
        Worker own = workerWith(10, adminRole);
        actorOwnsWorker(own, "admin", 4);
        Role same = role("admin", 4);
        same.id = 1;

        assertDoesNotThrow(() -> workerRankPolicy.assertCanChangeRoleOf(own, null, same));
    }

    // ---------- nadie desactiva su propio trabajador (WRK-010) ----------

    /** Tampoco el administrador, que es a quien existe para frenar: el rango lo deja pasar. */
    @Test
    void assertIsNotOwnWorker_whenItIsItsOwnWorker_evenForAdmin_throwsWRK010() {
        Role adminRole = role("admin", 4);
        Worker own = workerWith(10, adminRole);
        actorOwnsWorker(own, "admin", 4);

        ApiException thrown = assertThrows(ApiException.class,
            () -> workerRankPolicy.assertIsNotOwnWorker(own));
        assertEquals("WRK-010", thrown.code());
        assertEquals(403, thrown.status());
        assertEquals("No puedes darte de baja a ti mismo", thrown.getMessage());
        assertEquals(Map.of(), thrown.extensions());
    }

    /**
     * Sobre OTRO trabajador no aplica. El id del trabajador ajeno se elige IGUAL al id del usuario
     * de la sesion (7) a proposito: comparar contra el id del usuario en vez del de su trabajador
     * es el error facil, y con ids distintos pasaria igual.
     */
    @Test
    void assertIsNotOwnWorker_whenItIsSomeoneElse_passes_evenIfItsIdMatchesTheActorUserId() {
        Role adminRole = role("admin", 4);
        actorOwnsWorker(workerWith(10, adminRole), "admin", 4);

        assertDoesNotThrow(() -> workerRankPolicy.assertIsNotOwnWorker(workerWith(7, role("sales", 2))));
    }

    /** El detalle es constante y no lleva datos propios: no se deduce nada de la jerarquia. */
    @Test
    void theSelfRoleChangeDetail_carriesNothingOfItsOwn() {
        Role adminRole = role("admin", 4);
        adminRole.id = 1;
        Worker own = workerWith(10, adminRole);
        actorOwnsWorker(own, "admin", 4);
        Role other = role("general_manager", 3);
        other.id = 2;

        ApiException thrown = assertThrows(ApiException.class,
            () -> workerRankPolicy.assertCanChangeRoleOf(own, accountWith(7, own.role), other));
        assertEquals("No puedes cambiar tu propio cargo", thrown.getMessage());
        assertEquals(Map.of(), thrown.extensions());
    }

}
