package com.scaramutti.tms.workers;

import com.scaramutti.tms.support.WarehouseTestData;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import jakarta.inject.Inject;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.Stream;

import static com.scaramutti.tms.support.TestAuth.fabricateTokenForUser;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Quien escribe tiene que estar habilitado HOY, leido de la base: cuenta vigente, rol de escritura
 * en su fila y trabajador activo. El token no alcanza: vale hasta que vence y su rol puede ser
 * viejo. Cada condicion se mide con tres actores en las cuatro escrituras, contra la fila, y la
 * fila "ninguna" es el control: con el mismo montaje y sin la condicion, la escritura prospera.
 */
@QuarkusTest
class WorkerActorFromDatabaseTest {

    @Inject WarehouseTestData fixtures;

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
            documentTypeId = fixtures.seedDocumentType("ZTDOC72", "ZTEST generico", 20, null, true);
        }
        return documentTypeId;
    }

    /** Aplica la condicion que deja al actor sin permiso para escribir, leida de la base. */
    private void apply(String condition, WarehouseTestData.ActorSeed actor) {
        switch (condition) {
            // Un rol que no escribe trabajadores, pero de nivel mayor que el del destino: sin la
            // guarda, el rango lo dejaria pasar.
            case "rol_bajado" -> fixtures.setUserRole(actor.userId(), "sales");
            case "trabajador_apagado" -> fixtures.setWorkerActive(actor.workerId(), false);
            case "cuenta_apagada" -> fixtures.setUserActive(actor.userId(), false);
            case "ninguna" -> { }
            default -> throw new IllegalArgumentException(condition);
        }
    }

    private int seedTarget(String documentNumber, boolean isActive) {
        int id = fixtures.seedWorker(documentNumber, "Juan", "Pérez", "operator", isActive);
        fixtures.setWorkerDocumentType(id, documentTypeId());
        fixtures.setWorkerHireDate(id, LocalDate.of(2024, 3, 1));
        return id;
    }

    private String body(String documentNumber, String phone) {
        return """
            {"firstName":"Juan","lastName":"Pérez","documentTypeId":%d,"documentNumber":"%s",
             %s"role":"operator","hireDate":"2024-03-01"}"""
            .formatted(documentTypeId(), documentNumber, phone == null ? "" : "\"phone\":\"" + phone + "\",");
    }

    /**
     * Los tres roles de actor que importan: el administrador, exento del rango, tiene que caer
     * igual; el gerente general y finanzas, en los dos niveles que escriben.
     */
    static Stream<Arguments> actorConditionOperation() {
        List<String> actors = List.of("general_manager", "admin", "finance_manager");
        List<String> conditions = List.of("rol_bajado", "trabajador_apagado", "cuenta_apagada", "ninguna");
        List<String> operations = List.of("alta", "edicion", "desactivar", "reactivar");
        return actors.stream().flatMap(actor -> conditions.stream().flatMap(condition ->
            operations.stream().map(operation -> Arguments.of(actor, condition, operation))));
    }

    @ParameterizedTest(name = "{0}: {1} en {2}")
    @MethodSource("actorConditionOperation")
    void anActorNotEnabledToday_cannotWrite(String actorRole, String condition, String operation) {
        var actor = fixtures.seedActor(actorRole, "H1");
        String token = fabricateTokenForUser(actor.userId(), "ztestuserH1", actorRole);
        boolean blocked = !"ninguna".equals(condition);
        int target = "alta".equals(operation) ? 0 : seedTarget("ZTESTH001", !"reactivar".equals(operation));
        apply(condition, actor);

        var request = given().header("Authorization", "Bearer " + token);
        ValidatableResponse response = switch (operation) {
            case "alta" -> request.contentType("application/json").body(body("ZTESTH002", null))
                .when().post("/workers").then();
            case "edicion" -> request.contentType("application/json").body(body("ZTESTH001", "987654321"))
                .when().put("/workers/" + target).then();
            case "desactivar" -> request.when().post("/workers/" + target + "/deactivate").then();
            default -> request.when().post("/workers/" + target + "/reactivate").then();
        };

        if (!blocked) {
            response.statusCode("alta".equals(operation) ? 201 : 200);
            return;
        }
        response.statusCode(403).body("code", equalTo("COM-003"));
        switch (operation) {
            case "alta" -> assertEquals(0, fixtures.countWorkersByDocumentNumber("ZTESTH002"), "no nacio nadie");
            case "edicion" -> assertNull(fixtures.workerRowOf(target).phone(), "la fila no se movio");
            case "desactivar" -> assertEquals(true, fixtures.workerRowOf(target).isActive(), "sigue activo");
            default -> assertEquals(false, fixtures.workerRowOf(target).isActive(), "sigue inactivo");
        }
        if (target != 0) {
            assertEquals(List.of(), fixtures.workerAuditRows(target), "y sin rastro");
        }
    }

    /**
     * Un administrador bajado en la base a gerente general, con su token de administrador: el rol
     * nuevo escribe, asi que la guarda lo deja pasar, pero el rango lo mide la fila, con nivel 3,
     * y ya no esta exento. Si la exencion leyera el token, desactivaria a sus pares y a un admin.
     */
    @ParameterizedTest(name = "desactivar a {0}")
    @ValueSource(strings = {"general_manager", "admin", "finance_manager"})
    void anAdminLoweredToGeneralManager_isMeasuredByTheRowRank(String targetRole) {
        var actor = fixtures.seedActor("admin", "H1");
        String token = fabricateTokenForUser(actor.userId(), "ztestuserH1", "admin");
        fixtures.setUserRole(actor.userId(), "general_manager");
        int target = fixtures.seedWorker("ZTESTH001", "Juan", "Pérez", targetRole, true);

        var response = given().header("Authorization", "Bearer " + token)
            .when().post("/workers/" + target + "/deactivate").then();

        if ("finance_manager".equals(targetRole)) {
            response.statusCode(200);
            assertEquals(false, fixtures.workerRowOf(target).isActive(), "quedo dado de baja");
            return;
        }
        response.statusCode(403).body("code", equalTo("WRK-006"));
        assertEquals(true, fixtures.workerRowOf(target).isActive(), "sigue activo");
        assertEquals(List.of(), fixtures.workerAuditRows(target), "y sin rastro");
    }

    /**
     * El orden que publica el contrato: lo que se decide antes del organigrama (el 404, o en el alta
     * el cargo del cuerpo) sale antes que el 403 de quien no puede escribir. Sin esto, mover la
     * guarda al principio del metodo deja la suite verde y el contrato falso.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"alta", "edicion", "desactivar", "reactivar"})
    void order_whatIsDecidedBeforeTheRankBeatsAnActorNotEnabledToday(String operation) {
        var actor = fixtures.seedActor("general_manager", "H1");
        String token = fabricateTokenForUser(actor.userId(), "ztestuserH1", "general_manager");
        apply("trabajador_apagado", actor);

        var request = given().header("Authorization", "Bearer " + token);
        ValidatableResponse response = switch (operation) {
            case "alta" -> request.contentType("application/json")
                .body(body("ZTESTH002", null).replace("\"role\":\"operator\"", "\"role\":\"ztest_no_existe\""))
                .when().post("/workers").then();
            case "edicion" -> request.contentType("application/json").body(body("ZTESTH001", null))
                .when().put("/workers/999999").then();
            case "desactivar" -> request.when().post("/workers/999999/deactivate").then();
            default -> request.when().post("/workers/999999/reactivate").then();
        };

        if ("alta".equals(operation)) {
            response.statusCode(400).body("code", equalTo("WRK-005"));
        } else {
            response.statusCode(404).body("code", equalTo("WRK-001"));
        }
    }
}
