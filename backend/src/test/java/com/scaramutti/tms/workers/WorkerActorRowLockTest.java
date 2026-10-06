package com.scaramutti.tms.workers;

import com.scaramutti.tms.support.WarehouseTestData;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ExtractableResponse;
import jakarta.inject.Inject;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.LocalDate;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static com.scaramutti.tms.support.LockWaiters.UNTIL_IT_ARRIVES_MILLIS;
import static com.scaramutti.tms.support.LockWaiters.WHILE_ANOTHER_WAITS_MILLIS;
import static com.scaramutti.tms.support.LockWaiters.awaitWaiters;
import static com.scaramutti.tms.support.LockWaiters.backendPid;
import static com.scaramutti.tms.support.TestAuth.fabricateTokenForUser;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * El alta y reactivar tambien toman la fila del trabajador de quien actua, como la edicion y
 * desactivar: una baja o un cambio de cargo simultaneo de quien escribe ya no se cuela entre su
 * validacion y su confirmacion. Y quien ya no esta habilitado se corta ANTES de bloquear.
 */
@QuarkusTest
class WorkerActorRowLockTest {

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
            documentTypeId = fixtures.seedDocumentType("ZTDOC74", "ZTEST generico", 20, null, true);
        }
        return documentTypeId;
    }

    private WarehouseTestData.ActorSeed seedEditableActor(String role, String suffix) {
        var actor = fixtures.seedActor(role, suffix);
        fixtures.setWorkerDocumentType(actor.workerId(), documentTypeId());
        fixtures.setWorkerHireDate(actor.workerId(), LocalDate.of(2024, 3, 1));
        return actor;
    }

    private String workerBody(String firstName, String documentNumber, String role, String extra) {
        return """
            {"firstName":"%s","lastName":"%s","documentTypeId":%d,"documentNumber":"%s",
             "role":"%s","hireDate":"2024-03-01"%s}"""
            .formatted(firstName, "Actor".equals(firstName) ? role : "Pérez", documentTypeId(), documentNumber,
                role, extra);
    }

    private String outcomeOf(ExtractableResponse<?> extracted) {
        int status = extracted.statusCode();
        String code = status == 200 || status == 201 ? null : extracted.path("code");
        return code == null ? String.valueOf(status) : status + " " + code;
    }

    private String write(String token, String operation, int workerId, String body) {
        var request = given().header("Authorization", "Bearer " + token).contentType("application/json");
        return outcomeOf(switch (operation) {
            case "alta" -> request.body(body).when().post("/workers").then().extract();
            case "edicion" -> request.body(body).when().put("/workers/" + workerId).then().extract();
            default -> request.when().post("/workers/" + workerId + "/" + operation).then().extract();
        });
    }

    /** Lo que el admin le hace a quien escribe: darlo de baja o bajarle el cargo a uno que no escribe. */
    private String adminChanges(String adminToken, WarehouseTestData.ActorSeed actor, String actorRole,
            String action) {
        return "deactivate".equals(action)
            ? write(adminToken, "deactivate", actor.workerId(), null)
            : write(adminToken, "edicion", actor.workerId(), workerBody("Actor", "ZTESTA" + "H5", "sales", ""));
    }

    /**
     * Otra conexion deja la escritura en pausa DESPUES de validar y, en esa pausa, un admin cambia
     * a quien escribe. El admin tiene que esperar la fila de quien escribe: la escritura entra
     * entera y el cambio va despues. Sin la fila propia, el admin termina antes y la escritura
     * confirma firmada por alguien ya dado de baja o sin permiso.
     */
    @ParameterizedTest(name = "{0} hace {1} mientras un admin hace {2}")
    @CsvSource({
        "finance_manager, alta, deactivate", "general_manager, alta, lower",
        "admin, reactivate, deactivate", "general_manager, reactivate, lower",
    })
    void aWriterChangedWhileItsWriteIsInFlight_theWriteLandsWhole_beforeTheChange(
            String actorRole, String operation, String adminAction) throws Exception {
        var actor = seedEditableActor(actorRole, "H5");
        var admin = seedEditableActor("admin", "H6");
        String actorToken = fabricateTokenForUser(actor.userId(), "ztestuserH5", actorRole);
        String adminToken = fabricateTokenForUser(admin.userId(), "ztestuserH6", "admin");
        // La pausa: el alta espera el numero de documento que otra transaccion no confirmo; reactivar
        // espera la ficha del destino, que otra transaccion tiene tomada.
        int spare = fixtures.seedWorker("ZTESTH911", "Juan", "Pérez", "operator", true);
        int target = fixtures.seedWorker("ZTESTH912", "Juan", "Pérez", "driver", false);
        fixtures.seedDriverProfileFor(target, "ZTESTLH12", null, WarehouseTestData.STATUS_AVAILABLE, false);
        String pause = "alta".equals(operation)
            ? "UPDATE public.workers SET document_number = 'ZTESTH910' WHERE id = ?"
            : "SELECT id FROM public.drivers WHERE worker_id = ? FOR UPDATE";
        // En caliente: el primer pedido de la clase no puede llegar tarde y confundirse con uno que no espero.
        assertEquals("200", write(adminToken, "edicion", admin.workerId(), workerBody("Actor", "ZTESTAH6", "admin", "")));
        assertEquals("200", write(adminToken, "deactivate", spare, null));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        boolean adminSeenWaiting;
        boolean adminDoneBeforeTheWrite;
        try (Connection holder = dataSource.getConnection(); Connection observer = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (PreparedStatement taken = holder.prepareStatement(pause)) {
                taken.setInt(1, "alta".equals(operation) ? spare : target);
                taken.execute();
            }
            int holderPid = backendPid(holder);
            Future<String> writing = executor.submit(() -> write(actorToken, operation, target,
                workerBody("Juan", "ZTESTH910", "operator", "")));
            assertTrue(awaitWaiters(observer, holderPid, 1, writing, UNTIL_IT_ARRIVES_MILLIS),
                "precondicion: la escritura quedo en pausa");
            Future<String> change = executor.submit(() -> adminChanges(adminToken, actor, actorRole, adminAction));
            adminSeenWaiting = awaitWaiters(observer, holderPid, 2, change, WHILE_ANOTHER_WAITS_MILLIS);
            adminDoneBeforeTheWrite = change.isDone();
            holder.rollback();

            assertEquals("alta".equals(operation) ? "201" : "200", writing.get(30, TimeUnit.SECONDS),
                "la escritura entra entera");
            assertEquals("200", change.get(30, TimeUnit.SECONDS), "y el cambio del admin va despues");
        } finally {
            executor.shutdownNow();
        }
        assertFalse(adminDoneBeforeTheWrite, "el admin termino antes: no espero la fila de quien escribe");
        assertTrue(adminSeenWaiting, "el admin tiene que verse esperando un bloqueo, no solo llegar tarde");
        if ("alta".equals(operation)) {
            assertEquals(1, fixtures.countWorkersByDocumentNumber("ZTESTH910"), "el alta quedo hecha");
        } else {
            assertTrue(fixtures.workerRowOf(target).isActive(), "el destino quedo reactivado");
            assertEquals(actor.userId(), fixtures.workerRowOf(target).updatedBy(), "firmado por quien escribio");
        }
    }

    /**
     * Quien ya no puede escribir se corta ANTES de bloquear: no retiene su fila ni la del destino.
     * Con su propia fila tomada por otra transaccion, sin el corte previo esperaria el tope y
     * saldria 409; con el corte sale el 403 de siempre, sin esperar a nadie.
     */
    static Stream<Arguments> conditionAndOperation() {
        return Stream.of("cuenta_apagada", "trabajador_apagado", "rol_bajado").flatMap(condition ->
            Stream.of("alta", "edicion", "deactivate", "reactivate").map(op -> Arguments.of(condition, op)));
    }

    @ParameterizedTest(name = "{0} en {1}")
    @MethodSource("conditionAndOperation")
    void aDisabledWriter_isRejectedBeforeLocking_evenWithItsOwnRowTaken(String condition, String operation)
            throws Exception {
        var actor = seedEditableActor("general_manager", "H7");
        switch (condition) {
            case "cuenta_apagada" -> fixtures.setUserActive(actor.userId(), false);
            case "trabajador_apagado" -> fixtures.setWorkerActive(actor.workerId(), false);
            default -> fixtures.setUserRole(actor.userId(), "sales");
        }
        String token = fabricateTokenForUser(actor.userId(), "ztestuserH7", "general_manager");
        int target = fixtures.seedWorker("ZTESTH913", "Juan", "Pérez", "operator", !"reactivate".equals(operation));
        fixtures.setWorkerDocumentType(target, documentTypeId());
        fixtures.setWorkerHireDate(target, LocalDate.of(2024, 3, 1));
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (PreparedStatement taken = holder.prepareStatement(
                    "SELECT id FROM public.workers WHERE id = ? FOR NO KEY UPDATE")) {
                taken.setInt(1, actor.workerId());
                taken.executeQuery().close();
            }
            assertEquals("403 COM-003", write(token, operation, target,
                workerBody("Juan", "alta".equals(operation) ? "ZTESTH914" : "ZTESTH913", "operator", "")));
            holder.rollback();
        }
        assertEquals(0, fixtures.countWorkersByDocumentNumber("ZTESTH914"), "no nacio nadie");
    }

    /**
     * Tomar la fila propia puso el tope de espera en la transaccion del alta, que antes esperaba sin
     * limite: un numero de documento que otra transaccion retiene sin confirmar mas alla del tope
     * es el conflicto transitorio, no un error interno.
     */
    @Test
    void create_whenTheDocumentNumberIsHeldPastTheTimeout_returns409_WRK013_andNothingIsCreated() throws Exception {
        var actor = seedEditableActor("admin", "H8");
        int spare = fixtures.seedWorker("ZTESTH915", "Juan", "Pérez", "operator", true);
        String token = fabricateTokenForUser(actor.userId(), "ztestuserH8", "admin");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (PreparedStatement taken = holder.prepareStatement(
                    "UPDATE public.workers SET document_number = 'ZTESTH916' WHERE id = ?")) {
                taken.setInt(1, spare);
                taken.executeUpdate();
            }
            // En otro hilo y con plazo: sin tope en la transaccion, el alta esperaria para siempre.
            Future<String> creation = executor.submit(() ->
                write(token, "alta", 0, workerBody("Juan", "ZTESTH916", "operator", "")));
            assertEquals("409 WRK-013", creation.get(30, TimeUnit.SECONDS));
            holder.rollback();
        } finally {
            executor.shutdownNow();
        }
        assertEquals(0, fixtures.countWorkersByDocumentNumber("ZTESTH916"), "no nacio nadie");
    }

    /**
     * Quien escribe tiene que quedar INHABILITADO mientras espera su propia fila: otra transaccion la
     * retiene y le apaga la cuenta sin confirmar. El corte previo lo deja pasar (todavia no se ve), y
     * al confirmar la otra, la validacion de despues del bloqueo tiene que verlo. Validando antes de
     * bloquear, la escritura prosperaria con la cuenta ya apagada.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"alta", "reactivate"})
    void aWriterDisabledWhileItWaitsForItsOwnRow_isRejected_notWritten(String operation) throws Exception {
        var actor = seedEditableActor("general_manager", "H9");
        String token = fabricateTokenForUser(actor.userId(), "ztestuserH9", "general_manager");
        int target = fixtures.seedWorker("ZTESTH917", "Juan", "Pérez", "operator", false);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection holder = dataSource.getConnection(); Connection observer = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (PreparedStatement row = holder.prepareStatement(
                    "SELECT id FROM public.workers WHERE id = ? FOR NO KEY UPDATE");
                 PreparedStatement off = holder.prepareStatement(
                    "UPDATE public.users SET is_active = false WHERE id = ?")) {
                row.setInt(1, actor.workerId());
                row.executeQuery().close();
                off.setInt(1, actor.userId());
                off.executeUpdate();
            }
            Future<String> writing = executor.submit(() -> write(token, operation, target,
                workerBody("Juan", "ZTESTH918", "operator", "")));
            assertTrue(awaitWaiters(observer, backendPid(holder), 1, writing, UNTIL_IT_ARRIVES_MILLIS),
                "precondicion: la escritura espera la fila de quien escribe");
            holder.commit();
            assertEquals("403 COM-003", writing.get(30, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
        assertEquals(0, fixtures.countWorkersByDocumentNumber("ZTESTH918"), "no nacio nadie");
        assertFalse(fixtures.workerRowOf(target).isActive(), "el destino sigue de baja");
    }

    /** El gemelo por la licencia: la espera agotada en su indice tambien es el conflicto transitorio. */
    @Test
    void create_whenTheLicenseIsHeldPastTheTimeout_returns409_WRK013_andNothingIsCreated() throws Exception {
        var actor = seedEditableActor("admin", "H8");
        int other = fixtures.seedWorker("ZTESTH919", "Juan", "Pérez", "driver", true);
        String token = fabricateTokenForUser(actor.userId(), "ztestuserH8", "admin");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (PreparedStatement taken = holder.prepareStatement(
                    "INSERT INTO public.drivers (worker_id, license_number, status_id, is_active, created_at) "
                        + "VALUES (?, 'ZTESTLH19', ?, true, now())")) {
                taken.setInt(1, other);
                taken.setInt(2, fixtures.lowestResourceStatusIdIgnoringCase("available"));
                taken.executeUpdate();
            }
            Future<String> creation = executor.submit(() -> write(token, "alta", 0,
                workerBody("Juan", "ZTESTH920", "driver", ",\"driver\":{\"licenseNumber\":\"ZTESTLH19\"}")));
            assertEquals("409 WRK-013", creation.get(30, TimeUnit.SECONDS));
            holder.rollback();
        } finally {
            executor.shutdownNow();
        }
        assertEquals(0, fixtures.countWorkersByDocumentNumber("ZTESTH920"), "no nacio nadie");
    }
}
