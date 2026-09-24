package com.scaramutti.tms.workers;

import com.scaramutti.tms.support.WarehouseTestData;
import com.scaramutti.tms.support.WarehouseTestData.WorkerAuditRow;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import jakarta.inject.Inject;
import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.scaramutti.tms.support.TestAuth.adminToken;
import static com.scaramutti.tms.support.TestAuth.fabricateTokenForUser;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * El cambio de estado de un trabajador: desactivar, en cascada sobre su ficha y su cuenta.
 * Toda escritura se afirma contra la FILA releida (trabajador, ficha, cuenta y rastro) y no solo
 * contra la respuesta: la respuesta sale de la misma relectura, y mirarla sola no distinguiria
 * una fila que no se movio de una respuesta armada a mano.
 */
@QuarkusTest
class WorkerStatusResourceTest {

    private static final String DEACTIVATE = "deactivate";

    @Inject WarehouseTestData fixtures;
    @Inject com.scaramutti.tms.support.OperationsTestData operationsFixtures;
    @Inject com.scaramutti.tms.support.HermeticTestData hermeticFixtures;
    @Inject DataSource dataSource;

    /** Los viajes primero: apuntan a la ficha, y la ficha al trabajador. */
    @BeforeEach
    @AfterEach
    void cleanupFixtures() {
        operationsFixtures.deleteTestServices();
        fixtures.deleteTestWorkerDependents();
        QuarkusTransaction.requiringNew().run(() -> fixtures.deleteTestWorkers());
        hermeticFixtures.cleanup();
    }

    private ValidatableResponse post(String token, int workerId, String operation) {
        return given().header("Authorization", "Bearer " + token)
            .when().post("/workers/" + workerId + "/" + operation).then();
    }

    private List<String> auditFieldNames(int workerId) {
        return fixtures.workerAuditRows(workerId).stream().map(WorkerAuditRow::fieldName).toList();
    }

    /** Una fila del rastro del cambio de estado, afirmada entera: tipo, campo, etiqueta y valores. */
    private void assertStatusRow(WorkerAuditRow row, String changeType, String fieldName, String label,
            String oldValue, String newValue, int changedBy) {
        assertEquals(changeType, row.changeType(), fieldName);
        assertEquals(fieldName, row.fieldName());
        assertEquals(label, row.fieldLabel(), fieldName);
        assertEquals(oldValue, row.oldValue(), fieldName);
        assertEquals(newValue, row.newValue(), fieldName);
        assertNull(row.reason(), "el cambio de estado no lleva motivo");
        assertEquals(changedBy, row.changedBy(), "firma quien tiene la sesion");
        assertTrue(row.loggedAt() != null);
    }

    // ---------- desactivar: la cascada ----------

    /**
     * Las TRES filas se apagan en la misma operacion, y cada una deja su fila de rastro. La ficha
     * es un resto de un cargo anterior: la cascada no mira la modalidad del cargo actual.
     */
    @Test
    void deactivate_workerWithProfileAndAccount_turnsTheThreeOff_andAuditsEach() {
        int id = fixtures.seedWorker("ZTESTS001", "Juan", "Pérez", "sales", true);
        fixtures.seedDriverProfileFor(id, "ZTESTLS001", "A-IIb", WarehouseTestData.STATUS_AVAILABLE, true);
        int userId = fixtures.seedUserFor(id, "ztestuserS001", "sales");
        int driverId = fixtures.driverRowOf(id).id();

        post(adminToken(), id, DEACTIVATE).statusCode(200)
            .body("isActive", equalTo(false))
            .body("driver.isActive", equalTo(false))
            .body("hasUser", equalTo(true));

        var row = fixtures.workerRowOf(id);
        assertTrue(!row.isActive(), "el trabajador");
        assertEquals(fixtures.adminId(), row.updatedBy(), "la firma de la ultima modificacion");
        var ficha = fixtures.driverRowOf(id);
        assertTrue(!ficha.isActive(), "la ficha");
        assertEquals(driverId, ficha.id(), "la MISMA ficha, apagada y no borrada");
        assertEquals("ZTESTLS001", ficha.licenseNumber(), "sus datos intactos");
        assertTrue(!fixtures.userIsActive(userId), "la cuenta");
        assertEquals("sales", fixtures.userRoleNameOf(userId), "la cuenta no cambia de rol");

        var rows = fixtures.workerAuditRows(id);
        assertEquals(3, rows.size());
        assertStatusRow(rows.get(0), "DEACTIVATED", "isActive", "Trabajador", "true", "false", fixtures.adminId());
        assertStatusRow(rows.get(1), "DEACTIVATED", "driver.isActive", "Ficha de conductor", "true", "false",
            fixtures.adminId());
        assertStatusRow(rows.get(2), "DEACTIVATED", "user.isActive", "Usuario del sistema", "true", "false",
            fixtures.adminId());
    }

    /**
     * Todas las permutaciones de la cascada: con y sin ficha, encendida o apagada, con y sin
     * cuenta, encendida o apagada, y con cargos de las tres modalidades. Lo que ya estaba apagado
     * NO deja fila: el rastro dice lo que se movio.
     */
    @ParameterizedTest(name = "{0}: ficha={1}/{2} cuenta={3}/{4} -> {5}")
    @CsvSource(delimiter = '|', value = {
        // cargo      | ficha | activa | cuenta | activa | filas de rastro
        "driver       | si    | si     | no     | -      | isActive,driver.isActive",
        "driver       | si    | no     | no     | -      | isActive",
        "assistant    | si    | si     | no     | -      | isActive,driver.isActive",
        "assistant    | no    | -      | no     | -      | isActive",
        "operator     | si    | si     | no     | -      | isActive,driver.isActive",
        "operator     | no    | -      | no     | -      | isActive",
        "sales        | no    | -      | si     | si     | isActive,user.isActive",
        "sales        | no    | -      | si     | no     | isActive",
        "sales        | si    | no     | si     | no     | isActive",
    })
    void deactivate_cascadeTouchesOnlyWhatWasOn(String role, String hasProfile, String profileOn,
            String hasAccount, String accountOn, String expectedFields) {
        int id = fixtures.seedWorker("ZTESTS002", "Juan", "Pérez", role, true);
        if ("si".equals(hasProfile)) {
            fixtures.seedDriverProfileFor(id, "ZTESTLS002", null, WarehouseTestData.STATUS_AVAILABLE,
                "si".equals(profileOn));
        }
        Integer userId = "si".equals(hasAccount)
            ? fixtures.seedUserFor(id, "ztestuserS002", role, "si".equals(accountOn)) : null;

        post(adminToken(), id, DEACTIVATE).statusCode(200).body("isActive", equalTo(false));

        assertTrue(!fixtures.workerRowOf(id).isActive());
        if ("si".equals(hasProfile)) {
            assertTrue(!fixtures.driverRowOf(id).isActive(), "la ficha queda apagada");
        } else {
            assertNull(fixtures.driverRowOf(id), "desactivar no crea una ficha");
        }
        if (userId != null) {
            assertTrue(!fixtures.userIsActive(userId), "la cuenta queda apagada");
        }
        assertEquals(List.of(expectedFields.split(",")), auditFieldNames(id));
    }

    /** La firma es la de quien tiene la sesion, no la del administrador sembrado. */
    @Test
    void deactivate_theAuditAuthor_isTheSessionUser() {
        var actor = fixtures.seedActor("general_manager", "S01");
        int id = fixtures.seedWorker("ZTESTS003", "Juan", "Pérez", "sales", true);
        int userId = fixtures.seedUserFor(id, "ztestuserS003", "sales");

        post(fabricateTokenForUser(actor.userId(), "ztestuserS01", "general_manager"), id, DEACTIVATE)
            .statusCode(200).body("updatedBy.username", equalTo("ztestuserS01"));

        assertEquals(actor.userId(), fixtures.workerRowOf(id).updatedBy());
        assertEquals(List.of("isActive", "user.isActive"), auditFieldNames(id));
        fixtures.workerAuditRows(id).forEach(r -> assertEquals(actor.userId(), r.changedBy()));
        assertTrue(!fixtures.userIsActive(userId));
    }

    /** La respuesta es el detalle releido despues de escribir, no la entidad de antes. */
    @Test
    void deactivate_theResponse_isTheSameBodyAsTheDetail() {
        int id = fixtures.seedWorker("ZTESTS004", "Juan", "Pérez", "driver", true);
        fixtures.seedDriverProfileFor(id, "ZTESTLS004", null, WarehouseTestData.STATUS_AVAILABLE, true);

        String respuesta = post(adminToken(), id, DEACTIVATE).statusCode(200).extract().asString();
        String detalle = given().header("Authorization", "Bearer " + adminToken())
            .when().get("/workers/" + id).then().statusCode(200).extract().asString();

        assertEquals(detalle, respuesta);
    }

    // ---------- idempotencia ----------

    /**
     * Repetir no escribe NADA: ni la cascada, ni el rastro, ni la marca de modificacion. La ficha
     * y la cuenta se siembran ENCENDIDAS sobre un trabajador apagado a proposito: si la repeticion
     * corriera la cascada, las apagaria.
     */
    @Test
    void deactivate_anInactiveWorker_returns200_withoutAnyWrite() {
        int id = fixtures.seedWorker("ZTESTS020", "Juan", "Pérez", "sales", false);
        fixtures.seedDriverProfileFor(id, "ZTESTLS020", null, WarehouseTestData.STATUS_AVAILABLE, true);
        int userId = fixtures.seedUserFor(id, "ztestuserS020", "sales", true);
        var antes = fixtures.workerRowOf(id);

        post(adminToken(), id, DEACTIVATE).statusCode(200)
            .body("isActive", equalTo(false)).body("$", not(org.hamcrest.Matchers.hasKey("code")));

        var despues = fixtures.workerRowOf(id);
        assertEquals(antes.updatedAt(), despues.updatedAt(), "la marca no se mueve");
        assertEquals(antes.updatedBy(), despues.updatedBy(), "ni la firma");
        assertTrue(fixtures.driverRowOf(id).isActive(), "la ficha no se toco");
        assertTrue(fixtures.userIsActive(userId), "la cuenta no se toco");
        assertEquals(List.of(), auditFieldNames(id));
    }

    /** Un cambio efectivo SI mueve la marca y la firma. */
    @Test
    void deactivate_anActiveWorker_movesTheModificationStamp() {
        var actor = fixtures.seedActor("general_manager", "S02");
        int id = fixtures.seedWorker("ZTESTS022", "Juan", "Pérez", "operator", true);
        var antes = fixtures.workerRowOf(id);

        post(fabricateTokenForUser(actor.userId(), "ztestuserS02", "general_manager"), id, DEACTIVATE)
            .statusCode(200);

        var despues = fixtures.workerRowOf(id);
        assertNotEquals(antes.updatedAt(), despues.updatedAt(), "la marca se mueve");
        assertEquals(actor.userId(), despues.updatedBy(), "y firma quien tiene la sesion");
    }

    /**
     * Un conductor con un viaje pendiente asignado SE PUEDE desactivar, por decision de producto: el
     * backend no mira sus viajes, y el viaje sigue apuntando a el. La pantalla avisa el conteo.
     */
    @Test
    void deactivate_aDriverWithAPendingTrip_isAllowed_andTheTripKeepsPointingAtHim() {
        int id = fixtures.seedWorker("ZTESTS024", "Juan", "Pérez", "driver", true);
        fixtures.seedDriverProfileFor(id, "ZTESTLS024", null, WarehouseTestData.STATUS_AVAILABLE, true);
        int driverId = fixtures.driverRowOf(id).id();
        long serviceId = createService();
        operationsFixtures.forceServiceResources(serviceId, driverId, null, null);
        operationsFixtures.forceServiceStatus(serviceId, "PENDING_START");

        post(adminToken(), id, DEACTIVATE).statusCode(200);

        assertTrue(!fixtures.driverRowOf(id).isActive(), "la ficha queda apagada");
        given().header("Authorization", "Bearer " + adminToken())
            .when().get("/services/" + serviceId).then().statusCode(200)
            .body("driver.id", equalTo(driverId))
            .body("status", equalTo("PENDING_START"));
    }

    private long createService() {
        String payload = """
            {"clientId":%d,"tripScope":"PROVINCIA","tentativeDate":"%s","origin":"Piura",
             "destination":"Lima","cargoTypeId":%d,"weightKg":1000,"price":100,"currencyId":%d}"""
            .formatted(hermeticFixtures.seedClient(),
                java.time.LocalDate.now(com.scaramutti.tms.shared.util.DateUtils.LIMA).plusDays(5),
                hermeticFixtures.seedCargoType(), hermeticFixtures.currencyId("PEN"));
        return given().header("Authorization", "Bearer " + adminToken()).contentType("application/json")
            .body(payload).when().post("/services").then().statusCode(201)
            .extract().jsonPath().getLong("id");
    }

    // ---------- rango: roles cruzados ----------

    /**
     * Los cuatro que mantienen el padron, contra cargos de nivel mayor, igual y menor. Mismo nivel
     * NO alcanza. Un administrador desactiva a otro administrador.
     */
    @ParameterizedTest(name = "{0} {2} a {1} -> {3}")
    @CsvSource({
        "general_manager,    admin,              deactivate, 403",
        "general_manager,    operations_manager, deactivate, 403",
        "general_manager,    general_manager,    deactivate, 403",
        "general_manager,    finance_manager,    deactivate, 200",
        "general_manager,    driver,             deactivate, 200",
        "operations_manager, general_manager,    deactivate, 403",
        "operations_manager, operations_manager, deactivate, 403",
        "operations_manager, dispatcher,         deactivate, 200",
        "finance_manager,    finance_manager,    deactivate, 403",
        "finance_manager,    sales,              deactivate, 403",
        "finance_manager,    warehouse_keeper,   deactivate, 200",
        "finance_manager,    driver,             deactivate, 200",
        "admin,              admin,              deactivate, 200",
        "admin,              general_manager,    deactivate, 200",
    })
    void rankMatrix(String actorRole, String targetRole, String operation, int expected) {
        var actor = fixtures.seedActor(actorRole, "S10");
        boolean startsActive = DEACTIVATE.equals(operation);
        int id = fixtures.seedWorker("ZTESTS100", "Juan", "Pérez", targetRole, startsActive);

        var response = post(fabricateTokenForUser(actor.userId(), "ztestuserS10", actorRole), id, operation)
            .statusCode(expected);

        if (expected == 403) {
            response.body("code", equalTo("WRK-006"));
            assertEquals(startsActive, fixtures.workerRowOf(id).isActive(), "nada se movio");
            assertEquals(List.of(), auditFieldNames(id));
        } else {
            assertEquals(!startsActive, fixtures.workerRowOf(id).isActive());
            assertEquals(actor.userId(), fixtures.workerAuditRows(id).get(0).changedBy());
        }
    }

    /**
     * Las guardas corren ANTES del corte idempotente: repetir la operacion sobre alguien fuera de
     * rango sigue siendo un 403, no un 200 con su detalle. El destino ya esta en el estado pedido.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {DEACTIVATE})
    void theGuardsRunBeforeTheIdempotentCut(String operation) {
        var actor = fixtures.seedActor("operations_manager", "S16");
        int id = fixtures.seedWorker("ZTESTS105", "Juan", "Pérez", "general_manager", false);

        post(fabricateTokenForUser(actor.userId(), "ztestuserS16", "operations_manager"), id, operation)
            .statusCode(403).body("code", equalTo("WRK-006"));
    }

    /** Un administrador apaga a otro administrador, cuenta incluida, y el rastro lo firma. */
    @Test
    void deactivate_adminDeactivatesAnotherAdmin_accountIncluded() {
        var a = fixtures.seedActor("admin", "S11");
        var b = fixtures.seedActor("admin", "S12");

        post(fabricateTokenForUser(a.userId(), "ztestuserS11", "admin"), b.workerId(), DEACTIVATE).statusCode(200);

        assertTrue(!fixtures.userIsActive(b.userId()), "la cuenta del otro administrador");
        assertTrue(fixtures.userIsActive(a.userId()), "la del que actua sigue viva");
        assertEquals(List.of("isActive", "user.isActive"), auditFieldNames(b.workerId()));
        fixtures.workerAuditRows(b.workerId()).forEach(r -> assertEquals(a.userId(), r.changedBy()));
    }

    /**
     * El rango mira tambien el rol de la CUENTA. La divergencia se siembra: el trabajador es de un
     * cargo alcanzable y su cuenta es de administrador. Es la fila que da los permisos, y sin esta
     * guarda un gerente apagaria la cuenta de un administrador.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {DEACTIVATE})
    void theRankAlsoLooksAtTheAccountRole(String operation) {
        var actor = fixtures.seedActor("general_manager", "S13");
        boolean startsActive = DEACTIVATE.equals(operation);
        int id = fixtures.seedWorker("ZTESTS101", "Juan", "Pérez", "operator", startsActive);
        int userId = fixtures.seedUserFor(id, "ztestuserS101", "operator");
        fixtures.setUserRole(userId, "admin");

        post(fabricateTokenForUser(actor.userId(), "ztestuserS13", "general_manager"), id, operation)
            .statusCode(403).body("code", equalTo("WRK-006"));

        assertEquals(startsActive, fixtures.workerRowOf(id).isActive());
        assertTrue(fixtures.userIsActive(userId), "la cuenta no se toco");
        assertEquals(List.of(), auditFieldNames(id));
    }

    /** El control: sin la divergencia, el mismo montaje pasa. Sin el, "siempre 403" pasaria arriba. */
    @Test
    void theRankOnTheAccount_withoutDivergence_lets_itThrough() {
        var actor = fixtures.seedActor("general_manager", "S14");
        int id = fixtures.seedWorker("ZTESTS102", "Juan", "Pérez", "operator", true);
        int userId = fixtures.seedUserFor(id, "ztestuserS102", "operator");

        post(fabricateTokenForUser(actor.userId(), "ztestuserS14", "general_manager"), id, DEACTIVATE)
            .statusCode(200);

        assertTrue(!fixtures.userIsActive(userId));
    }

    /** La exencion del administrador sale de la BASE: un token que dice admin no alcanza. */
    @Test
    void theAdminExemption_comesFromTheDatabase_notFromTheToken() {
        var actor = fixtures.seedActor("general_manager", "S15");
        int id = fixtures.seedWorker("ZTESTS103", "Juan", "Pérez", "operations_manager", true);

        post(fabricateTokenForUser(actor.userId(), "ztestuserS15", "admin"), id, DEACTIVATE)
            .statusCode(403).body("code", equalTo("WRK-006"));
        assertTrue(fixtures.workerRowOf(id).isActive());
    }

    /**
     * Dos administradores que se desactivan ENTRE SI al mismo tiempo: una prospera y la otra
     * espera y encuentra a su actor ya apagado. Se afirma ese resultado EXACTO y no solo "queda
     * uno": tomando las filas sin el orden por id, terminan en 409 por abrazo mortal o espera.
     */
    @Test
    void twoAdminsDeactivatingEachOtherAtOnce_neverLeaveBothAccountsOff() throws Exception {
        for (int vuelta = 0; vuelta < 5; vuelta++) {
            cleanupFixtures();
            var a = fixtures.seedActor("admin", "S17");
            var b = fixtures.seedActor("admin", "S18");
            String tokenA = fabricateTokenForUser(a.userId(), "ztestuserS17", "admin");
            String tokenB = fabricateTokenForUser(b.userId(), "ztestuserS18", "admin");
            var largada = new java.util.concurrent.CountDownLatch(1);
            ExecutorService executor = Executors.newFixedThreadPool(2);
            try {
                Future<String> aSobreB = executor.submit(() -> {
                    largada.await();
                    return outcome(post(tokenA, b.workerId(), DEACTIVATE));
                });
                Future<String> bSobreA = executor.submit(() -> {
                    largada.await();
                    return outcome(post(tokenB, a.workerId(), DEACTIVATE));
                });
                largada.countDown();
                String primero = aSobreB.get(30, TimeUnit.SECONDS);
                String segundo = bSobreA.get(30, TimeUnit.SECONDS);

                assertEquals(List.of("200", "403 COM-003"), java.util.stream.Stream.of(primero, segundo).sorted().toList(),
                    "vuelta " + vuelta + ": una prospera y la otra encuentra a su actor apagado");
                assertTrue(fixtures.userIsActive(a.userId()) || fixtures.userIsActive(b.userId()));
            } finally {
                executor.shutdownNow();
            }
        }
    }

    /** El status y, si lo hay, el codigo de un pedido hecho en otro hilo. */
    private String outcome(ValidatableResponse response) {
        var extracted = response.extract();
        String code = extracted.statusCode() == 200 ? null : extracted.path("code");
        return code == null ? String.valueOf(extracted.statusCode()) : extracted.statusCode() + " " + code;
    }

    // ---------- uno mismo ----------

    /**
     * Nadie desactiva su propio trabajador, con ninguno de los cuatro roles. Para los que no son
     * administrador sale WRK-010 y no WRK-006: el caso especifico corre primero.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"admin", "general_manager", "operations_manager", "finance_manager"})
    void deactivate_ownWorker_returns403_WRK010(String role) {
        var actor = fixtures.seedActor(role, "S20");
        assertNotEquals(actor.userId(), actor.workerId(), "precondicion: ids distintos");

        post(fabricateTokenForUser(actor.userId(), "ztestuserS20", role), actor.workerId(), DEACTIVATE)
            .statusCode(403)
            .body("code", equalTo("WRK-010"))
            .body("detail", equalTo("No puedes darte de baja a ti mismo"));

        assertTrue(fixtures.workerRowOf(actor.workerId()).isActive());
        assertTrue(fixtures.userIsActive(actor.userId()));
        assertEquals(List.of(), auditFieldNames(actor.workerId()));
    }

    /** Aun si su trabajador ya estuviera apagado: la guarda corre antes que el corte idempotente. */
    @Test
    void deactivate_ownAlreadyInactiveWorker_stillReturns403_WRK010() {
        var actor = fixtures.seedActor("admin", "S21");
        fixtures.setWorkerActive(actor.workerId(), false);

        post(fabricateTokenForUser(actor.userId(), "ztestuserS21", "admin"), actor.workerId(), DEACTIVATE)
            .statusCode(403).body("code", equalTo("WRK-010"));
    }

    /** Una sesion cuyo usuario ya no esta vigente no escribe, aunque su token siga valiendo. */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {DEACTIVATE})
    void byAnActorWhoseAccountIsInactive_returns403_COM003(String operation) {
        var actor = fixtures.seedActor("general_manager", "S23");
        fixtures.setUserActive(actor.userId(), false);
        boolean startsActive = DEACTIVATE.equals(operation);
        int id = fixtures.seedWorker("ZTESTS104", "Juan", "Pérez", "sales", startsActive);

        post(fabricateTokenForUser(actor.userId(), "ztestuserS23", "general_manager"), id, operation)
            .statusCode(403).body("code", equalTo("COM-003"));
        assertEquals(startsActive, fixtures.workerRowOf(id).isActive());
    }

    /** Una sesion cuyo usuario ya no existe no escribe: 403, no un error del servidor. */
    @Test
    void deactivate_byASessionWhoseUserNoLongerExists_returns403_COM003() {
        int id = fixtures.seedWorker("ZTESTS106", "Juan", "Pérez", "operator", true);

        post(fabricateTokenForUser(999999, "ztestghost", "admin"), id, DEACTIVATE)
            .statusCode(403).body("code", equalTo("COM-003"));
        assertTrue(fixtures.workerRowOf(id).isActive());
    }

    /** El 404 va antes del rechazo por sesion apagada: el orden que publica el contrato. */
    @Test
    void order_notFoundBeatsAnActorWhoseAccountIsOff() {
        var actor = fixtures.seedActor("general_manager", "S24");
        fixtures.setUserActive(actor.userId(), false);

        post(fabricateTokenForUser(actor.userId(), "ztestuserS24", "general_manager"), 999999, DEACTIVATE)
            .statusCode(404).body("code", equalTo("WRK-001"));
    }

    // ---------- sesion viva ----------

    /**
     * Tras desactivar, la cuenta no vuelve a entrar ni a renovar; el token que ya tenia sigue
     * valiendo hasta que vence. El caso DOCUMENTA la ventana, no la niega.
     */
    @Test
    void deactivate_liveSession_refreshAndLoginFail_theAccessTokenStillAnswers() {
        int id = fixtures.seedWorker("ZTESTS040", "Juan", "Pérez", "sales", true);
        int userId = fixtures.seedUserFor(id, "ztestuserS040", "sales");
        fixtures.copyPasswordHashFrom(userId, "sales");
        String loginBody = "{\"username\":\"ztestuserS040\",\"password\":\"Sales1234\"}";
        var login = given().contentType("application/json").body(loginBody)
            .when().post("/auth/login").then().statusCode(200).extract();
        String access = login.path("token");
        String refresh = login.path("refreshToken");

        post(adminToken(), id, DEACTIVATE).statusCode(200);

        given().contentType("application/json").body("{\"refreshToken\":\"" + refresh + "\"}")
            .when().post("/auth/refresh").then().statusCode(401).body("code", equalTo("AUTH-002"));
        given().contentType("application/json").body(loginBody)
            .when().post("/auth/login").then().statusCode(401).body("code", equalTo("AUTH-002"));
        given().header("Authorization", "Bearer " + access)
            .when().get("/auth/me").then().statusCode(200);
    }

    // ---------- autenticacion, roles y 404 ----------

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {DEACTIVATE})
    void withoutToken_returns401(String operation) {
        int id = fixtures.seedWorker("ZTESTS050", "Juan", "Pérez", "sales", true);
        given().when().post("/workers/" + id + "/" + operation).then().statusCode(401);
        assertTrue(fixtures.workerRowOf(id).isActive());
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({
        "warehouse_keeper, deactivate", "sales, deactivate", "dispatcher, deactivate",
    })
    void withARoleOutsideTheFour_returns403_COM003(String role, String operation) {
        var actor = fixtures.seedActor(role, "S51");
        int id = fixtures.seedWorker("ZTESTS051", "Juan", "Pérez", "operator", DEACTIVATE.equals(operation));

        post(fabricateTokenForUser(actor.userId(), "ztestuserS51", role), id, operation)
            .statusCode(403).body("code", equalTo("COM-003"));
        assertEquals(DEACTIVATE.equals(operation), fixtures.workerRowOf(id).isActive());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {DEACTIVATE})
    void withAnIdThatDoesNotExist_returns404_WRK001(String operation) {
        post(adminToken(), 999999, operation).statusCode(404).body("code", equalTo("WRK-001"));
    }

    /** El rol va antes que el 404: quien no puede operar no se entera de si el id existe. */
    @Test
    void order_theRoleBeatsNotFound() {
        var actor = fixtures.seedActor("sales", "S52");
        post(fabricateTokenForUser(actor.userId(), "ztestuserS52", "sales"), 999999, DEACTIVATE)
            .statusCode(403).body("code", equalTo("COM-003"));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {DEACTIVATE})
    void withANonNumericId_returns404(String operation) {
        given().header("Authorization", "Bearer " + adminToken())
            .when().post("/workers/abc/" + operation).then().statusCode(404);
    }

    // ---------- sin cuerpo ----------

    /**
     * Un cuerpo que intenta decir otra cosa se IGNORA: no hay parametro que lo lea. Un tipo de
     * contenido ajeno tampoco da 415, un codigo que el contrato no declara.
     */
    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
        "application/json | {\"isActive\":true,\"reason\":\"ZTEST motivo largo\"}",
        "application/json | {",
        "text/plain       | hola",
        "application/x-www-form-urlencoded | a=b",
    })
    void deactivate_withAnUnexpectedBodyOrContentType_ignoresIt(String contentType, String body) {
        int id = fixtures.seedWorker("ZTESTS060", "Juan", "Pérez", "sales", true);

        given().header("Authorization", "Bearer " + adminToken()).contentType(contentType).body(body)
            .when().post("/workers/" + id + "/deactivate").then().statusCode(200).body("isActive", equalTo(false));

        assertTrue(!fixtures.workerRowOf(id).isActive());
        assertNull(fixtures.workerAuditRows(id).get(0).reason(), "el motivo del cuerpo no se leyo");
    }

    // ---------- bloqueo (WRK-013) ----------

    /**
     * Tambien cuando lo tomado es la fila del trabajador de QUIEN ACTUA, que desactivar bloquea.
     * Con los dos ordenes de siembra: el bloqueo toma primero la fila de id menor, y cada orden
     * pasa por una rama distinta.
     */
    @ParameterizedTest(name = "actor sembrado primero={0}")
    @ValueSource(booleans = {true, false})
    void deactivate_whenAnotherTransactionHoldsTheActorRow_returns409_WRK013(boolean actorFirst) throws Exception {
        int destinoPrevio = actorFirst ? 0 : fixtures.seedWorker("ZTESTS107", "Juan", "Pérez", "operator", true);
        var actor = fixtures.seedActor("admin", "S25");
        int id = actorFirst ? fixtures.seedWorker("ZTESTS107", "Juan", "Pérez", "operator", true) : destinoPrevio;
        assertEquals(actorFirst, actor.workerId() < id, "precondicion: el orden de ids que se quiere medir");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (PreparedStatement lock = holder.prepareStatement(
                "SELECT id FROM public.workers WHERE id = ? FOR NO KEY UPDATE")) {
                lock.setInt(1, actor.workerId());
                lock.executeQuery().close();
            }
            Future<String> response = executor.submit(() ->
                outcome(post(fabricateTokenForUser(actor.userId(), "ztestuserS25", "admin"), id, DEACTIVATE)));
            assertEquals("409 WRK-013", response.get(30, TimeUnit.SECONDS));
            holder.rollback();
        } finally {
            executor.shutdownNow();
        }
        assertTrue(fixtures.workerRowOf(id).isActive(), "el destino no se movio");
        assertEquals(List.of(), auditFieldNames(id));
    }

    /**
     * Otra conexion sostiene una fila y no confirma: la operacion se rinde con el conflicto del
     * contrato, y NADA queda a medias. Se cubren las tres filas que la operacion puede esperar:
     * la del trabajador (al tomarla) y la de la ficha y la cuenta (al escribirlas).
     */
    @ParameterizedTest(name = "{0} con {1} tomada")
    @CsvSource({
        "deactivate, workers", "deactivate, drivers", "deactivate, users",
    })
    void whenAnotherTransactionHoldsARow_returns409_WRK013_andNothingMoves(String operation, String table)
            throws Exception {
        boolean startsActive = DEACTIVATE.equals(operation);
        int id = fixtures.seedWorker("ZTESTS070", "Juan", "Pérez", "driver", startsActive);
        fixtures.seedDriverProfileFor(id, "ZTESTLS070", null, WarehouseTestData.STATUS_AVAILABLE, startsActive);
        int userId = fixtures.seedUserFor(id, "ztestuserS070", "sales", startsActive);
        String sql = switch (table) {
            case "workers" -> "SELECT id FROM public.workers WHERE id = ? FOR NO KEY UPDATE";
            case "drivers" -> "SELECT id FROM public.drivers WHERE worker_id = ? FOR UPDATE";
            default -> "SELECT id FROM public.users WHERE worker_id = ? FOR UPDATE";
        };
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (PreparedStatement lock = holder.prepareStatement(sql)) {
                lock.setInt(1, id);
                lock.executeQuery().close();
            }
            Future<int[]> response = executor.submit(() -> {
                var extracted = post(adminToken(), id, operation).extract();
                String code = extracted.path("code");
                return new int[] {extracted.statusCode(), "WRK-013".equals(code) ? 1 : 0};
            });
            int[] result = response.get(30, TimeUnit.SECONDS);
            assertEquals(409, result[0], "el choque es transitorio, no un error interno");
            assertEquals(1, result[1], "con el codigo WRK-013");
            holder.rollback();
        } finally {
            executor.shutdownNow();
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                System.err.println("AVISO: quedo un hilo vivo del caso de bloqueo del estado");
            }
        }

        assertEquals(startsActive, fixtures.workerRowOf(id).isActive(), "el trabajador no se movio");
        assertEquals(startsActive, fixtures.driverRowOf(id).isActive(), "la ficha tampoco");
        assertEquals(startsActive, fixtures.userIsActive(userId), "ni la cuenta");
        assertEquals(List.of(), auditFieldNames(id), "ni quedo rastro");
    }
}
