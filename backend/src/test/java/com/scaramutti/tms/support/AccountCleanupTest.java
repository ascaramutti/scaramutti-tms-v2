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
    private static final String GENERATED = "zlimpiagenerada";

    @Inject WarehouseTestData fixtures;
    @Inject EntityManager entityManager;

    @BeforeEach
    @AfterEach
    void cleanupFixtures() {
        QuarkusTransaction.requiringNew().run(this::deleteGenerated);
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

    /** El alta pone el nombre generado: la cuenta de un trabajador de prueba se va igual. */
    @Test
    void theCleanup_alsoRemovesAnAccountOutsideThePrefix_onATestWorker() {
        int adminId = fixtures.adminId();
        int workerId = fixtures.seedWorker("ZTESTLIM2", "Limpia", "Generada", "sales", true);
        int generated = QuarkusTransaction.requiringNew().call(() -> ((Number) entityManager.createNativeQuery(
                "INSERT INTO public.users (username, password_hash, worker_id, is_active, created_at) "
                    + "VALUES (?1, 'ztest-no-login', ?2, true, CURRENT_TIMESTAMP) RETURNING id")
            .setParameter(1, GENERATED).setParameter(2, workerId).getSingleResult()).intValue());
        int keeper = seedKeeper(0);
        QuarkusTransaction.requiringNew().run(() -> signAccount(keeper, generated, generated));
        long created = trailRow(generated, "CREATED", adminId);
        long signed = trailRow(keeper, "PASSWORD_RESET", generated);
        int product = fixtures.seedProduct(WarehouseTestData.PREFIX + "limpiagenerada");
        QuarkusTransaction.requiringNew().run(() -> {
            entityManager.createNativeQuery("UPDATE almacen.products SET created_by = ?1 WHERE id = ?2")
                .setParameter(1, generated).setParameter(2, product).executeUpdate();
            entityManager.createNativeQuery(
                    "INSERT INTO public.worker_audit_logs (worker_id, change_type, changed_by) "
                        + "SELECT worker_id, 'FIELD_EDIT', ?1 FROM public.users WHERE id = ?2")
                .setParameter(1, generated).setParameter(2, keeper).executeUpdate();
        });

        assertDoesNotThrow(() -> fixtures.deleteTestWorkerDependents());

        assertEquals(0, countOf("SELECT count(*) FROM public.users WHERE id = ?1", generated));
        assertEquals("se fue|se fue", stateOf(created) + "|" + stateOf(signed));
        assertEquals(0, countOf("SELECT count(*) FROM almacen.products WHERE id = ?1", product));
        assertEquals(0, countOf("SELECT count(*) FROM public.worker_audit_logs WHERE changed_by = ?1", generated));
        var authorship = fixtures.userAuthorshipOf(keeper);
        assertEquals("null|null", authorship.createdBy() + "|" + authorship.updatedBy());
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

    /** Lo que se prueba es la limpieza compartida: si se rompe, esta no deja la cuenta para la clase siguiente. */
    private void deleteGenerated() {
        String generated = "(SELECT id FROM public.users WHERE username = '" + GENERATED + "')";
        entityManager.createNativeQuery("DELETE FROM public.worker_audit_logs WHERE changed_by IN " + generated)
            .executeUpdate();
        entityManager.createNativeQuery("DELETE FROM almacen.products WHERE created_by IN " + generated)
            .executeUpdate();
        entityManager.createNativeQuery("DELETE FROM public.user_audit_logs WHERE user_id IN " + generated
            + " OR changed_by IN " + generated).executeUpdate();
        entityManager.createNativeQuery("UPDATE public.users SET created_by = NULL WHERE created_by IN " + generated)
            .executeUpdate();
        entityManager.createNativeQuery("UPDATE public.users SET updated_by = NULL WHERE updated_by IN " + generated)
            .executeUpdate();
        entityManager.createNativeQuery("DELETE FROM public.users WHERE username = '" + GENERATED + "'")
            .executeUpdate();
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
