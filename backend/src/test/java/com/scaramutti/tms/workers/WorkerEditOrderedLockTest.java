package com.scaramutti.tms.workers;

import com.scaramutti.tms.support.WarehouseTestData;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static com.scaramutti.tms.support.TestAuth.fabricateTokenForUser;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * La edicion toma la fila del destino y la del trabajador de quien actua, en orden de id, y valida
 * al actor despues. Sin eso, dos administradores que se bajan el cargo entre si a la vez pasaban
 * los dos la validacion y la empresa podia quedar sin administradores.
 */
@QuarkusTest
class WorkerEditOrderedLockTest {

    private static final String LOCK_ROW = "SELECT id FROM public.workers WHERE id = ? FOR NO KEY UPDATE";

    @Inject WarehouseTestData fixtures;
    @Inject DataSource dataSource;

    private Integer documentTypeId;

    @BeforeEach
    @AfterEach
    void cleanupFixtures() {
        fixtures.deleteTestWorkerDependents();
        QuarkusTransaction.requiringNew().run(() -> fixtures.deleteTestWorkers());
        fixtures.deleteTestDocumentTypes();
        documentTypeId = null;
    }

    private int documentTypeId() {
        if (documentTypeId == null) {
            documentTypeId = fixtures.seedDocumentType("ZTDOC73", "ZTEST generico", 20, null, true);
        }
        return documentTypeId;
    }

    /** Un actor cuya ficha se puede reenviar tal cual, cambiando solo el cargo. */
    private WarehouseTestData.ActorSeed seedEditableActor(String role, String suffix) {
        var actor = fixtures.seedActor(role, suffix);
        fixtures.setWorkerDocumentType(actor.workerId(), documentTypeId());
        fixtures.setWorkerHireDate(actor.workerId(), LocalDate.of(2024, 3, 1));
        return actor;
    }

    /** Un destino sin cuenta, para que ninguna regla de la cuenta se cruce con el bloqueo. */
    private int seedTarget() {
        int id = fixtures.seedWorker("ZTESTJ004", "Juan", "Pérez", "operator", true);
        fixtures.setWorkerDocumentType(id, documentTypeId());
        fixtures.setWorkerHireDate(id, LocalDate.of(2024, 3, 1));
        return id;
    }

    /** El cuerpo que reenvia la ficha de un actor sembrado, con el cargo pedido. */
    private String actorBody(String seededRole, String suffix, String newRole) {
        return """
            {"firstName":"Actor","lastName":"%s","documentTypeId":%d,"documentNumber":"ZTESTA%s",
             "role":"%s","hireDate":"2024-03-01"}""".formatted(seededRole, documentTypeId(), suffix, newRole);
    }

    private String edit(String token, int workerId, String body) {
        return outcomeOf(given().header("Authorization", "Bearer " + token).contentType("application/json")
            .body(body).when().put("/workers/" + workerId).then().extract());
    }

    /** "200", o el status con el codigo del error. */
    private String outcomeOf(io.restassured.response.ExtractableResponse<?> extracted) {
        String code = extracted.statusCode() == 200 ? null : extracted.path("code");
        return code == null ? String.valueOf(extracted.statusCode()) : extracted.statusCode() + " " + code;
    }

    private int backendPid(Connection connection) throws Exception {
        try (PreparedStatement query = connection.prepareStatement("SELECT pg_backend_pid()");
             ResultSet rows = query.executeQuery()) {
            rows.next();
            return rows.getInt(1);
        }
    }

    /**
     * Cuantas sesiones esperan DETRAS de la conexion que sostiene: a ella, o a alguien que la espera
     * (el segundo en la cola de una fila espera al primero, no al que la sostiene). Solo las
     * encadenadas cuentan: una espera ajena en la base compartida no puede cumplir el sondeo.
     */
    private int waitersBehind(Connection observer, int holderPid) throws Exception {
        try (PreparedStatement query = observer.prepareStatement(
                "SELECT count(*) FROM pg_stat_activity a WHERE ? = ANY(pg_blocking_pids(a.pid)) OR EXISTS "
                    + "(SELECT 1 FROM unnest(pg_blocking_pids(a.pid)) b WHERE ? = ANY(pg_blocking_pids(b)))")) {
            query.setInt(1, holderPid);
            query.setInt(2, holderPid);
            try (ResultSet rows = query.executeQuery()) {
                rows.next();
                return rows.getInt(1);
            }
        }
    }

    /** Espera a ver esas sesiones encadenadas; corta si el pedido ya termino, y antes del tope de 600ms. */
    private boolean awaitWaiters(Connection observer, int holderPid, int waiters, Future<?> orDone)
            throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(550);
        while (!orDone.isDone() && System.nanoTime() < deadline) {
            if (waitersBehind(observer, holderPid) >= waiters) {
                return true;
            }
            Thread.sleep(5);
        }
        return false;
    }

    /**
     * El cruce, sin depender de la suerte: otra conexion sostiene las DOS filas hasta ver a los dos
     * pedidos esperando, y recien ahi suelta. Uno prospera; el otro relee su cuenta ya bajada: fuera
     * de rango si su cargo nuevo escribe, sin permiso si ya no. Tomando solo el destino, los dos dan
     * 200; sin el orden por id, abrazo mortal y 409. Por eso se afirma el resultado exacto.
     */
    @ParameterizedTest(name = "bajados a {0}")
    @CsvSource({"general_manager, 403 WRK-006", "sales, 403 COM-003"})
    void twoAdminsLoweringEachOtherAtOnce_oneWins_theOtherIsOutOfRank(String newRole, String rejected)
            throws Exception {
        var a = seedEditableActor("admin", "K1");
        var b = seedEditableActor("admin", "K2");
        String tokenA = fabricateTokenForUser(a.userId(), "ztestuserK1", "admin");
        String tokenB = fabricateTokenForUser(b.userId(), "ztestuserK2", "admin");
        // En caliente: en frio el primer pedido puede llegar despues de que el sondeo se rinda.
        assertEquals("200", edit(tokenA, a.workerId(), actorBody("admin", "K1", "admin")));
        assertEquals("200", edit(tokenB, b.workerId(), actorBody("admin", "K2", "admin")));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try (Connection holder = dataSource.getConnection(); Connection observer = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            for (int id : new int[] {a.workerId(), b.workerId()}) {
                try (PreparedStatement lock = holder.prepareStatement(LOCK_ROW)) {
                    lock.setInt(1, id);
                    lock.executeQuery().close();
                }
            }
            Future<String> aLowersB = executor.submit(() ->
                edit(tokenA, b.workerId(), actorBody("admin", "K2", newRole)));
            Future<String> bLowersA = executor.submit(() ->
                edit(tokenB, a.workerId(), actorBody("admin", "K1", newRole)));
            assertTrue(awaitWaiters(observer, backendPid(holder), 2, bLowersA), "precondicion: los dos esperan");
            holder.rollback();

            List<String> outcomes = Stream.of(aLowersB.get(30, TimeUnit.SECONDS),
                bLowersA.get(30, TimeUnit.SECONDS)).sorted().toList();
            assertEquals(List.of("200", rejected), outcomes,
                "uno prospera y el otro relee su cuenta ya bajada");
        } finally {
            executor.shutdownNow();
        }

        List<String> accountRoles = List.of(fixtures.userRoleNameOf(a.userId()), fixtures.userRoleNameOf(b.userId()));
        assertEquals(List.of("admin", newRole), accountRoles.stream().sorted().toList(),
            "queda exactamente un administrador");
        int loserTarget = "admin".equals(accountRoles.get(0)) ? a.workerId() : b.workerId();
        assertEquals(fixtures.roleIdOf("admin"), fixtures.workerRowOf(loserTarget).roleId(),
            "el destino del rechazado no se movio");
        assertEquals(List.of(), fixtures.workerAuditRows(loserTarget), "ni dejo rastro");
    }

    /**
     * La fila de quien actua tambien se toma: si otra conexion la sostiene, la edicion se rinde con
     * el conflicto del contrato. Con los dos ordenes de siembra, porque el bloqueo toma primero la
     * fila de id menor y cada orden pasa por una rama distinta.
     */
    @ParameterizedTest(name = "actor sembrado primero={0}")
    @ValueSource(booleans = {true, false})
    void update_whenAnotherTransactionHoldsTheActorRow_returns409_WRK013(boolean actorFirst) throws Exception {
        int early = actorFirst ? 0 : seedTarget();
        var actor = seedEditableActor("admin", "K3");
        int target = actorFirst ? seedTarget() : early;
        assertEquals(actorFirst, actor.workerId() < target, "precondicion: el orden de ids que se mide");
        String token = fabricateTokenForUser(actor.userId(), "ztestuserK3", "admin");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (PreparedStatement lock = holder.prepareStatement(LOCK_ROW)) {
                lock.setInt(1, actor.workerId());
                lock.executeQuery().close();
            }
            Future<String> response = executor.submit(() ->
                edit(token, target, """
                    {"firstName":"Juan","lastName":"Pérez","documentTypeId":%d,"documentNumber":"ZTESTJ004",
                     "role":"assistant","hireDate":"2024-03-01"}""".formatted(documentTypeId())));
            assertEquals("409 WRK-013", response.get(30, TimeUnit.SECONDS));
            holder.rollback();
        } finally {
            executor.shutdownNow();
        }
        assertEquals(fixtures.roleIdOf("operator"), fixtures.workerRowOf(target).roleId(), "el destino no se movio");
        assertTrue(fixtures.workerAuditRows(target).isEmpty(), "ni dejo rastro");
    }

    /** Una sesion cuyo usuario ya no existe no tiene fila propia que tomar: 403, no un error. */
    @Test
    void update_byASessionWhoseUserNoLongerExists_returns403_COM003() {
        int target = seedTarget();
        assertEquals("403 COM-003", edit(fabricateTokenForUser(999999, "ztestghost", "admin"), target, """
            {"firstName":"Juan","lastName":"Pérez","documentTypeId":%d,"documentNumber":"ZTESTJ004",
             "role":"assistant","hireDate":"2024-03-01"}""".formatted(documentTypeId())));
        assertEquals(fixtures.roleIdOf("operator"), fixtures.workerRowOf(target).roleId(), "el destino no se movio");
    }

    /**
     * El bloqueo de la fila de quien actua vale para toda edicion, no solo entre administradores.
     * La edicion queda en pausa DESPUES de validar (otra conexion retiene el numero de documento que
     * pide), y en ese momento un admin da de baja o le baja el cargo a quien edita. El admin tiene
     * que esperar: la edicion entra entera y firmada por alguien habilitado, y el cambio va despues.
     * Tomando solo el destino, el admin termina antes y la edicion confirma firmada por quien ya no.
     */
    @ParameterizedTest(name = "{0} edita a {1} mientras un admin hace {2}")
    @CsvSource({"finance_manager, driver, deactivate", "general_manager, operator, lower"})
    void anActorChangedWhileItsEditIsInFlight_theEditLandsWhole_beforeTheChange(
            String actorRole, String targetRole, String adminAction) throws Exception {
        var actor = seedEditableActor(actorRole, "K5");
        var admin = seedEditableActor("admin", "K6");
        int target = fixtures.seedWorker("ZTESTJ010", "Juan", "Pérez", targetRole, true);
        fixtures.setWorkerDocumentType(target, documentTypeId());
        fixtures.setWorkerHireDate(target, LocalDate.of(2024, 3, 1));
        String driver = "";
        if ("driver".equals(targetRole)) {
            fixtures.seedDriverProfileFor(target, "ZTESTLJ10", null, WarehouseTestData.STATUS_AVAILABLE, true);
            driver = ",\"driver\":{\"licenseNumber\":\"ZTESTLJ10\"}";
        }
        int spare = fixtures.seedWorker("ZTESTJ011", "Juan", "Pérez", "operator", true);
        String editBody = """
            {"firstName":"Juan","lastName":"Pérez","documentTypeId":%d,"documentNumber":"ZTESTJ012",
             "role":"%s","hireDate":"2024-03-01","reason":"Correccion del documento"%s}"""
            .formatted(documentTypeId(), targetRole, driver);
        String actorToken = fabricateTokenForUser(actor.userId(), "ztestuserK5", actorRole);
        String adminToken = fabricateTokenForUser(admin.userId(), "ztestuserK6", "admin");
        // Los dos caminos, en caliente: el tope de espera de la edicion en pausa no deja margen
        // para que el primer pedido de la clase llegue tarde y se confunda con uno que no espero.
        int warmUp = fixtures.seedWorker("ZTESTJ013", "Juan", "Pérez", "operator", true);
        assertEquals("200", outcomeOf(given().header("Authorization", "Bearer " + adminToken)
            .when().post("/workers/" + warmUp + "/deactivate").then().extract()));
        assertEquals("200", edit(adminToken, admin.workerId(), actorBody("admin", "K6", "admin")));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        boolean adminSeenWaiting;
        boolean adminDoneBeforeTheEdit;
        try (Connection holder = dataSource.getConnection(); Connection observer = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (PreparedStatement taken = holder.prepareStatement(
                    "UPDATE public.workers SET document_number = 'ZTESTJ012' WHERE id = ?")) {
                taken.setInt(1, spare);
                taken.executeUpdate();
            }
            Future<String> edit = executor.submit(() -> edit(actorToken, target, editBody));
            int holderPid = backendPid(holder);
            assertTrue(awaitWaiters(observer, holderPid, 1, edit), "precondicion: la edicion quedo en pausa");
            Future<String> change = executor.submit(() -> "deactivate".equals(adminAction)
                ? outcomeOf(given().header("Authorization", "Bearer " + adminToken)
                    .when().post("/workers/" + actor.workerId() + "/deactivate").then().extract())
                : edit(adminToken, actor.workerId(), actorBody(actorRole, "K5", "sales")));
            adminSeenWaiting = awaitWaiters(observer, holderPid, 2, change);
            adminDoneBeforeTheEdit = change.isDone();
            holder.rollback();

            assertEquals("200", edit.get(30, TimeUnit.SECONDS), "la edicion entra entera");
            assertEquals("200", change.get(30, TimeUnit.SECONDS), "y el cambio del admin va despues");
        } finally {
            executor.shutdownNow();
        }
        assertFalse(adminDoneBeforeTheEdit, "el admin termino antes: no espero la fila de quien edita");
        assertTrue(adminSeenWaiting, "el admin tiene que verse esperando un bloqueo, no solo llegar tarde");
        var row = fixtures.workerRowOf(target);
        assertEquals("ZTESTJ012", row.documentNumber(), "la edicion no quedo a medias");
        assertEquals(actor.userId(), row.updatedBy(), "firmada por quien edito");
        assertEquals("deactivate".equals(adminAction) ? "false" : "sales",
            "deactivate".equals(adminAction) ? String.valueOf(fixtures.workerRowOf(actor.workerId()).isActive())
                : fixtures.userRoleNameOf(actor.userId()), "y el cambio del admin quedo aplicado");
    }
}
