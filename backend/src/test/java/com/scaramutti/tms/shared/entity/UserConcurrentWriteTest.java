package com.scaramutti.tms.shared.entity;

import com.scaramutti.tms.shared.repository.UserRepository;
import com.scaramutti.tms.support.WarehouseTestData;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Dos escrituras sin coordinar sobre la MISMA fila de usuario no se pisan. La baja de un trabajador
 * apaga la cuenta leyendola sin tomar su fila: con columnas completas, si el cambio de contrasena
 * confirma mientras tanto, la baja pisaria su hash y su version, y la revocacion se desharia sin
 * error. Lo evita que la entidad escriba solo lo que cada una cambio, una anotacion de una linea que
 * solo este caso sostiene.
 */
@QuarkusTest
class UserConcurrentWriteTest {

    @Inject UserRepository userRepository;
    @Inject WarehouseTestData fixtures;
    @Inject EntityManager entityManager;

    @BeforeEach
    @AfterEach
    void cleanupFixtures() {
        fixtures.deleteTestWorkerDependents();
        QuarkusTransaction.requiringNew().run(() -> fixtures.deleteTestWorkers());
    }

    @Test
    void twoTransactionsWritingDifferentColumns_doNotOverwriteEachOther() {
        int workerId = fixtures.seedWorker("ZTESTW001", "Ana", "Silva", "sales", true);
        int userId = fixtures.seedUserFor(workerId, "ztestuser700", "sales");

        QuarkusTransaction.requiringNew().run(() -> {
            // T1 carga la fila y le cambia SOLO el hash, sin confirmar todavia.
            User user = userRepository.findById(userId);
            user.passwordHash = "hash-nuevo-de-la-otra-operacion";

            // T2, en su propia transaccion, le apaga SOLO la cuenta y confirma.
            fixtures.setUserActive(userId, false);

            // Al cerrar T1, su UPDATE tiene que tocar unicamente la columna que cambio.
        });

        assertEquals(false,
            QuarkusTransaction.requiringNew().call(() -> userRepository.findById(userId).isActive),
            "la baja que escribio la otra transaccion no se puede perder");
        assertEquals("hash-nuevo-de-la-otra-operacion",
            QuarkusTransaction.requiringNew().call(() -> userRepository.findById(userId).passwordHash),
            "y el hash de esta tampoco");
    }

    /**
     * La direccion que se da hoy: la baja lee la cuenta sin tomarla, el cambio de contrasena confirma
     * el hash y la version, y la baja escribe despues. Ni la baja ni la revocacion se pierden.
     */
    @Test
    void aDeactivationThatReadBeforeAPasswordChange_keepsTheHashAndTheVersionOfTheChange() {
        int workerId = fixtures.seedWorker("ZTESTW709", "Ana", "Silva", "sales", true);
        int userId = fixtures.seedUserFor(workerId, "ztestuser709", "sales");

        QuarkusTransaction.requiringNew().run(() -> {
            userRepository.findById(userId).isActive = false;
            QuarkusTransaction.requiringNew().run(() -> entityManager.createNativeQuery(
                    "UPDATE public.users SET password_hash = 'hash-del-cambio', session_version = 1 WHERE id = ?1")
                .setParameter(1, userId).executeUpdate());
        });

        assertEquals("false|hash-del-cambio|1", QuarkusTransaction.requiringNew().call(() -> {
            User user = userRepository.findById(userId);
            return user.isActive + "|" + user.passwordHash + "|" + user.sessionVersion;
        }), "la baja, el hash y la version del cambio");
    }
}
