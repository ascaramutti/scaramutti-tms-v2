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

import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Dos escrituras sin coordinar sobre la MISMA fila de usuario no se pisan lo que cada una cambia
 * sola (la autoria, que firman las dos, si: los dos ultimos casos). La baja de un trabajador apaga la cuenta
 * leyendola sin tomar su fila: con columnas completas, si el cambio de contrasena confirma mientras
 * tanto, la baja pisaria su hash y su version, y la revocacion se desharia sin error. Lo evita que la
 * entidad escriba solo lo que cada una cambio, una anotacion de una linea sin la que caen los cuatro casos.
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

    /**
     * Las dos firman la autoria, asi que esas columnas si se pisan: la baja, que confirma ultima con
     * otro autor que el que leyo, deja el suyo y la hora en que firmo, anterior a la del cambio que
     * pisa. Aca, junto al hash y la version del cambio.
     */
    @Test
    void aDeactivationThatConfirmsAfterAPasswordChange_leavesItsSignature_withTheTimeItSigned() {
        int workerId = fixtures.seedWorker("ZTESTW710", "Ana", "Silva", "sales", true);
        int userId = fixtures.seedUserFor(workerId, "ztestuser710", "sales");
        int adminId = fixtures.adminId();
        OffsetDateTime[] marks = new OffsetDateTime[2];

        QuarkusTransaction.requiringNew().run(() -> {
            User user = userRepository.findById(userId);
            user.isActive = false;
            user.markChangedBy(adminId);
            marks[0] = user.updatedAt;
            QuarkusTransaction.requiringNew().run(() -> entityManager.createNativeQuery(
                    "UPDATE public.users SET password_hash = 'hash-del-cambio', session_version = 1, "
                        + "updated_by = id, updated_at = clock_timestamp() WHERE id = ?1")
                .setParameter(1, userId).executeUpdate());
            // En otra transaccion: en esta, la consulta escribiria antes la baja pendiente.
            marks[1] = QuarkusTransaction.requiringNew().call(() -> fixtures.userAuthorshipOf(userId).updatedAt());
        });

        assertEquals("false|hash-del-cambio|1|" + adminId, QuarkusTransaction.requiringNew().call(() -> {
            User user = userRepository.findById(userId);
            return user.isActive + "|" + user.passwordHash + "|" + user.sessionVersion + "|" + user.updatedBy;
        }), "la baja con su firma, y el hash y la version del cambio");
        OffsetDateTime left = fixtures.userAuthorshipOf(userId).updatedAt();
        assertEquals(marks[0].toInstant(), left.toInstant(), "la hora en que firmo la baja");
        assertTrue(left.isBefore(marks[1]), "anterior a la del cambio que piso: " + left + " y " + marks[1]);
    }

    /**
     * Si la baja firma con el autor que ya leyo, para la entidad esa columna no cambio y solo escribe
     * la hora: queda el autor del cambio con la hora de la baja, una firma que ninguna fila respalda.
     */
    @Test
    void aDeactivationSignedByTheAuthorItRead_writesOnlyItsTime_overAPasswordChange() {
        int workerId = fixtures.seedWorker("ZTESTW711", "Ana", "Silva", "sales", true);
        int userId = fixtures.seedUserFor(workerId, "ztestuser711", "sales");
        int adminId = fixtures.adminId();
        QuarkusTransaction.requiringNew().run(() -> entityManager.createNativeQuery(
                "UPDATE public.users SET updated_by = ?1 WHERE id = ?2")
            .setParameter(1, adminId).setParameter(2, userId).executeUpdate());
        OffsetDateTime[] marks = new OffsetDateTime[2];

        QuarkusTransaction.requiringNew().run(() -> {
            User user = userRepository.findById(userId);
            user.isActive = false;
            user.markChangedBy(adminId);
            marks[0] = user.updatedAt;
            QuarkusTransaction.requiringNew().run(() -> entityManager.createNativeQuery(
                    "UPDATE public.users SET password_hash = 'hash-del-cambio', session_version = 1, "
                        + "updated_by = id, updated_at = clock_timestamp() WHERE id = ?1")
                .setParameter(1, userId).executeUpdate());
            marks[1] = QuarkusTransaction.requiringNew().call(() -> fixtures.userAuthorshipOf(userId).updatedAt());
        });

        assertEquals("false|hash-del-cambio|1|" + userId, QuarkusTransaction.requiringNew().call(() -> {
            User user = userRepository.findById(userId);
            return user.isActive + "|" + user.passwordHash + "|" + user.sessionVersion + "|" + user.updatedBy;
        }), "la baja, el hash y la version del cambio, y el autor del cambio");
        OffsetDateTime left = fixtures.userAuthorshipOf(userId).updatedAt();
        assertEquals(marks[0].toInstant(), left.toInstant(), "con la hora de la baja");
        assertTrue(left.isBefore(marks[1]), "anterior a la del cambio: " + left + " y " + marks[1]);
    }
}
