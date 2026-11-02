package com.scaramutti.tms.support;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * La limpieza compartida suelta el rastro y las firmas de las cuentas de prueba antes de borrarlas,
 * y nada mas: una fila del rastro de una cuenta de prueba o firmada por una, o una cuenta que no se
 * borra y que una de prueba firmo, trabaria el borrado; lo que no toca a una cuenta de prueba queda.
 * Las cuentas que no se borran son dos propias del caso, fuera de los prefijos de la limpieza: las
 * borra esta clase antes y despues, asi una corrida cortada no deja una firma ajena en una sembrada.
 */
@QuarkusTest
class AccountCleanupTest {

    private static final String[] KEEPERS = {"zlimpiaqueda1", "zlimpiaqueda2"};
    private static final String[] KEEPER_DOCUMENTS = {"ZLIMPQ001", "ZLIMPQ002"};

    @Inject WarehouseTestData fixtures;
    @Inject EntityManager entityManager;

    @BeforeEach
    @AfterEach
    void cleanupFixtures() {
        fixtures.deleteTestWorkerDependents();
        QuarkusTransaction.requiringNew().run(() -> fixtures.deleteTestWorkers());
        QuarkusTransaction.requiringNew().run(this::deleteKeepers);
    }

    @Test
    void theCleanup_releasesTheTrailAndTheSignaturesOfTheTestAccounts_andNothingElse() {
        int adminId = fixtures.adminId();
        int workerId = fixtures.seedWorker("ZTESTLIM1", "Limpia", "Prueba", "sales", true);
        int testAccount = fixtures.seedUserFor(workerId, "ztestuserlimpia1", "sales");
        int firstKeeper = seedKeeper(0);
        int secondKeeper = seedKeeper(1);
        QuarkusTransaction.requiringNew().run(() -> {
            signAccount(firstKeeper, adminId, testAccount);
            signAccount(secondKeeper, testAccount, adminId);
        });
        long ownRow = trailRow(testAccount, "PASSWORD_CHANGED", testAccount);
        long signedByTheTest = trailRow(firstKeeper, "PASSWORD_RESET", testAccount);
        long ofTheTest = trailRow(testAccount, "PASSWORD_RESET", firstKeeper);
        long foreign = trailRow(firstKeeper, "REACTIVATED", adminId);

        assertDoesNotThrow(() -> fixtures.deleteTestWorkerDependents());

        assertEquals(0, countOf("SELECT count(*) FROM public.users WHERE id = ?1", testAccount));
        assertEquals("se fue|se fue|se fue|queda", stateOf(ownRow) + "|" + stateOf(signedByTheTest) + "|"
            + stateOf(ofTheTest) + "|" + stateOf(foreign), "las filas de la cuenta de prueba o firmadas por ella");
        var first = fixtures.userAuthorshipOf(firstKeeper);
        var second = fixtures.userAuthorshipOf(secondKeeper);
        assertEquals(adminId + "|null", first.createdBy() + "|" + first.updatedBy(),
            "se suelta solo la firma de la de prueba");
        assertEquals("null|" + adminId, second.createdBy() + "|" + second.updatedBy(), "en las dos columnas");
    }

    // ---------- fixtures ----------------------------------------------------------

    /** Una cuenta apagada y sin clave usable, con su trabajador, fuera de los prefijos de la limpieza. */
    private int seedKeeper(int index) {
        int workerId = fixtures.seedWorker(KEEPER_DOCUMENTS[index], "Queda", "Limpieza", "sales", false);
        return QuarkusTransaction.requiringNew().call(() -> ((Number) entityManager.createNativeQuery(
                "INSERT INTO public.users (username, password_hash, worker_id, is_active, created_at) "
                    + "VALUES (?1, 'ztest-no-login', ?2, false, CURRENT_TIMESTAMP) RETURNING id")
            .setParameter(1, KEEPERS[index]).setParameter(2, workerId).getSingleResult()).intValue());
    }

    /** Lo que dejo el caso sobre sus dos cuentas, y las cuentas con sus trabajadores, en orden de claves. */
    private void deleteKeepers() {
        String keepers = "(SELECT id FROM public.users WHERE username IN ('" + String.join("', '", KEEPERS) + "'))";
        entityManager.createNativeQuery("DELETE FROM public.user_audit_logs WHERE user_id IN " + keepers
            + " OR changed_by IN " + keepers).executeUpdate();
        entityManager.createNativeQuery("UPDATE public.users SET created_by = NULL WHERE created_by IN " + keepers)
            .executeUpdate();
        entityManager.createNativeQuery("UPDATE public.users SET updated_by = NULL WHERE updated_by IN " + keepers)
            .executeUpdate();
        entityManager.createNativeQuery("DELETE FROM public.users WHERE username IN ('"
            + String.join("', '", KEEPERS) + "')").executeUpdate();
        entityManager.createNativeQuery("DELETE FROM public.workers WHERE document_number IN ('"
            + String.join("', '", KEEPER_DOCUMENTS) + "')").executeUpdate();
    }

    /** Los dos son enteros de una fila sembrada, o nulos; en el texto, un nulo queda NULL. */
    private void signAccount(int accountId, Integer createdBy, Integer updatedBy) {
        entityManager.createNativeQuery("UPDATE public.users SET created_by = " + createdBy + ", updated_by = "
            + updatedBy + " WHERE id = ?1").setParameter(1, accountId).executeUpdate();
    }

    private long trailRow(int accountId, String changeType, int changedBy) {
        return QuarkusTransaction.requiringNew().call(() -> ((Number) entityManager.createNativeQuery(
                "INSERT INTO public.user_audit_logs (user_id, change_type, changed_by) VALUES (?1, ?2, ?3)"
                    + " RETURNING id")
            .setParameter(1, accountId).setParameter(2, changeType).setParameter(3, changedBy)
            .getSingleResult()).longValue());
    }

    private String stateOf(long row) {
        return countOf("SELECT count(*) FROM public.user_audit_logs WHERE id = ?1", row) == 1 ? "queda" : "se fue";
    }

    private int countOf(String sql, long id) {
        return QuarkusTransaction.requiringNew().call(() -> ((Number) entityManager.createNativeQuery(sql)
            .setParameter(1, id).getSingleResult()).intValue());
    }
}
