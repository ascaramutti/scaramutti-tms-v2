package com.scaramutti.tms.users;

import com.scaramutti.tms.shared.repository.UserRepository;
import com.scaramutti.tms.users.service.UserAccountWriteLock;
import com.scaramutti.tms.support.WarehouseTestData;
import com.scaramutti.tms.support.WarehouseTestData.ActorSeed;
import com.scaramutti.tms.support.WarehouseTestData.UserAuditRow;
import com.scaramutti.tms.support.WarehouseTestData.UserAuthorship;
import io.quarkus.arc.ClientProxy;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.mockito.Mockito;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.scaramutti.tms.support.LogCapture.logsWhile;
import static com.scaramutti.tms.support.ResponseHeaders.assertNotSent;
import static com.scaramutti.tms.support.ResponseHeaders.assertSentOnce;
import static com.scaramutti.tms.support.TestAuth.fabricateAccessToken;
import static com.scaramutti.tms.support.TestAuth.fabricateTokenForUser;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * POST /users. Quien escribe es siempre una cuenta de prueba sobre un trabajador de prueba. Los
 * trabajadores del alta, salvo el del respaldo, se llaman "Zeta Testuser...": el nombre que generan
 * empieza con el prefijo de las cuentas de prueba, asi que lo tomado se siembra con el mismo fixture.
 */
@QuarkusTest
class UserAccountCreateResourceTest {

    private static final String TEMPORARY_PATTERN = "^[A-HJ-NP-Za-hjkmnp-z2-9]{12}$";

    @Inject WarehouseTestData fixtures;
    @Inject EntityManager entityManager;
    @Inject DataSource dataSource;
    @Inject UserRepository userRepository;
    @Inject UserAccountWriteLock userAccountWriteLock;

    @ConfigProperty(name = "app.accounts.lock-timeout-ms")
    int configuredLockTimeoutMillis;

    @BeforeEach
    @AfterEach
    void cleanupFixtures() {
        fixtures.deleteTestWorkerDependents();
        QuarkusTransaction.requiringNew().run(() -> fixtures.deleteTestWorkers());
    }

    // ---------- roles ------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"admin", "general_manager", "operations_manager", "finance_manager"})
    void create_forEachRoleThatAdministersAccounts_returns201(String role) {
        String token = actorToken(role, "cre" + role.substring(0, 3));
        int workerId = seedTarget("ZTESTCRE01", "Testuser Rol", "warehouse_keeper");

        create(token, workerId).then().statusCode(201).body("account.username", equalTo("ztestuserrol"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"sales", "dispatcher", "warehouse_keeper"})
    void create_forTheOtherRoles_returns403_COM003_withoutCacheHeaders_andCreatesNothing(String role) {
        int workerId = seedTarget("ZTESTCRE02", "Testuser Otro", "warehouse_keeper");

        Response response = create(tokenOf(fixtures.seedActor("general_manager", "creotro"), role), workerId);

        assertEquals(403, response.statusCode());
        assertEquals("COM-003", response.jsonPath().getString("code"));
        assertNotSent(response, "Cache-Control", "Vary", "Pragma");
        assertEquals(0, fixtures.countUsersByWorkerId(workerId));
    }

    /** El cargo se decide antes que el cuerpo: sin acceso, un cuerpo invalido tampoco dice nada. */
    @ParameterizedTest
    @ValueSource(strings = {"{}", "{roto"})
    void create_withRoleWithoutAccess_andAnInvalidBody_returns403NotA400(String body) {
        given().header("Authorization", "Bearer " + fabricateAccessToken("ztest-sales", "sales"))
            .contentType(ContentType.JSON).body(body)
        .when().post("/users")
        .then().statusCode(403).body("code", equalTo("COM-003"));
    }

    @Test
    void create_withoutToken_returns401() {
        given().contentType(ContentType.JSON).body("{\"workerId\":1}").when().post("/users").then().statusCode(401);
    }

    // ---------- el cuerpo ----------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"", "{}", "null", "{\"workerId\":null}", "{\"otro\":1}"})
    void create_withoutAWorker_returns400_COM001(String body) {
        given().header("Authorization", "Bearer " + actorToken("admin", "crecuerpo"))
            .contentType(ContentType.JSON).body(body)
        .when().post("/users")
        .then().statusCode(400).contentType("application/problem+json").body("code", equalTo("COM-001"));
    }

    /** Sin tipo, el cuerpo se lee como JSON y falta el trabajador. */
    @Test
    void create_withoutBodyNorContentType_returns400_COM001() {
        given().header("Authorization", "Bearer " + actorToken("admin", "crenotipo")).noContentType()
        .when().post("/users")
        .then().statusCode(400).body("code", equalTo("COM-001"));
    }

    @Test
    void create_withAnotherContentType_returns415() {
        given().header("Authorization", "Bearer " + actorToken("admin", "cretexto"))
            .contentType(ContentType.TEXT).body("{\"workerId\":1}")
        .when().post("/users")
        .then().statusCode(415);
    }

    @ParameterizedTest
    @ValueSource(ints = {999999, 0, -5})
    void create_forAWorkerThatDoesNotExist_returns400_USR004_withAConstantDetail(int workerId) {
        Response response = create(actorToken("admin", "creno"), workerId);

        assertEquals(400, response.statusCode());
        assertEquals("application/problem+json", response.contentType());
        assertEquals("USR-004", response.jsonPath().getString("code"));
        assertEquals("El trabajador no existe", response.jsonPath().getString("detail"));
        assertNotSent(response, "Cache-Control", "Vary", "Pragma");
    }

    // ---------- la cuenta nueva ------------------------------------------------------

    @Test
    void create_returns201_withTheDetailAndATemporaryThatLogsIn_andLeavesTheRowAndItsTrail() {
        ActorSeed actor = fixtures.seedActor("general_manager", "crefirma");
        int workerId = seedTarget("ZTESTCRE03", "Testuser Fila", "finance_manager");
        OffsetDateTime before = OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(1);

        Response response = create(tokenOf(actor, "general_manager"), workerId);

        assertEquals(201, response.statusCode());
        assertSentOnce(response, "Cache-Control", "no-store");
        assertSentOnce(response, "Vary", "Authorization");
        assertNotSent(response, "Pragma");
        assertEquals(List.of("account", "temporaryPassword"),
            response.jsonPath().getMap("").keySet().stream().sorted().toList());
        String temporary = response.jsonPath().getString("temporaryPassword");
        assertTrue(temporary.matches(TEMPORARY_PATTERN), "la temporal sigue el patron del contrato");
        int accountId = response.jsonPath().getInt("account.id");
        Map<String, Object> account = response.jsonPath().getMap("account");
        assertEquals(get(accountId).jsonPath().getMap(""), account, "account es el detalle que trae la lectura");
        assertEquals("ztestuserfila", account.get("username"));
        assertEquals(true, account.get("isActive"));
        assertEquals("finance_manager", response.jsonPath().getString("account.role.name"));
        assertEquals(workerId, response.jsonPath().getInt("account.worker.id"));
        assertEquals(actor.userId(), response.jsonPath().getInt("account.createdBy.id"));
        assertNull(account.get("updatedBy"), "una cuenta nueva no tiene cambios");

        login("ztestuserfila", temporary).then().statusCode(200);

        UserAuthorship authorship = fixtures.userAuthorshipOf(accountId);
        assertEquals(actor.userId(), authorship.createdBy());
        assertNull(authorship.updatedBy());
        assertTrue(authorship.createdAt().isAfter(before), "la marca es de este pedido");
        assertEquals(authorship.createdAt(), authorship.updatedAt());
        assertEquals(0, fixtures.userSessionVersionOf(accountId));
        List<UserAuditRow> rows = fixtures.userAuditRows(accountId);
        assertEquals(1, rows.size());
        UserAuditRow row = rows.get(0);
        assertEquals(new UserAuditRow("CREATED", "username", "Usuario", null, "ztestuserfila", actor.userId(),
            row.loggedAt()), row);
        assertTrue(row.loggedAt().isAfter(before), "la fila es de este pedido");
    }

    @Test
    void create_touchesNeitherTheWorkerNorAnyOtherAccount() {
        String token = actorToken("admin", "cresolo");
        int workerId = seedTarget("ZTESTCRE04", "Testuser Solo", "sales");
        String workerBefore = workerRow(workerId);
        List<String> accountsBefore = everyAccountBut(-1);

        int accountId = create(token, workerId).then().statusCode(201).extract().path("account.id");

        assertEquals(workerBefore, workerRow(workerId), "el trabajador sigue igual y sin firma nueva");
        assertEquals(0, workerTrailRows(workerId));
        assertEquals(accountsBefore, everyAccountBut(accountId));
    }

    /** El cuerpo trae solo el trabajador: lo demas se ignora, tambien una clave, que no entra. */
    @Test
    void create_ignoresTheNameThePasswordTheRoleAndTheStateInTheBody() {
        ActorSeed actor = fixtures.seedActor("admin", "cremasa");
        int workerId = seedTarget("ZTESTCRE05", "Testuser Masa", "sales");
        String body = "{\"workerId\":" + workerId + ",\"username\":\"ztestuserotro\",\"temporaryPassword\":"
            + "\"Cuerpo123\",\"role\":\"admin\",\"isActive\":false,\"sessionVersion\":9,\"createdBy\":7,\"id\":1}";

        Response response = given().header("Authorization", "Bearer " + tokenOf(actor, "admin"))
            .contentType(ContentType.JSON).body(body).when().post("/users");

        assertEquals(201, response.statusCode());
        int accountId = response.jsonPath().getInt("account.id");
        assertEquals("ztestusermasa", response.jsonPath().getString("account.username"));
        assertEquals("sales", response.jsonPath().getString("account.role.name"));
        assertTrue(fixtures.userIsActive(accountId));
        assertEquals(0, fixtures.userSessionVersionOf(accountId));
        assertEquals(actor.userId(), fixtures.userAuthorshipOf(accountId).createdBy());
        login("ztestusermasa", "Cuerpo123").then().statusCode(401).body("code", equalTo("AUTH-001"));
        login("ztestuserotro", "Cuerpo123").then().statusCode(401).body("code", equalTo("AUTH-001"));
        login("ztestusermasa", response.jsonPath().getString("temporaryPassword")).then().statusCode(200);
    }

    /** Si la respuesta se pierde, el reintento no da otra temporal: la cuenta ya existe. */
    @Test
    void create_twiceForTheSameWorker_returns409_USR003_theSecondTime() {
        String token = actorToken("admin", "credos");
        int workerId = seedTarget("ZTESTCRE06", "Testuser Dos", "sales");

        create(token, workerId).then().statusCode(201);
        Response again = create(token, workerId);

        assertEquals(409, again.statusCode());
        assertEquals("USR-003", again.jsonPath().getString("code"));
        assertEquals("El trabajador ya tiene una cuenta", again.jsonPath().getString("detail"));
        assertFalse(again.asString().contains("temporaryPassword"));
        assertEquals(1, fixtures.countUsersByWorkerId(workerId));
    }

    // ---------- el nombre generado ---------------------------------------------------

    /** Tomado es cualquier cuenta: una inactiva tambien empuja al sufijo. */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void create_withTheNameTakenByAnActiveOrInactiveAccount_takesTheFirstFreeSuffix(boolean takenIsActive) {
        String token = actorToken("admin", "cretoma");
        fixtures.seedUserFor(fixtures.seedWorker("ZTESTCRE07", "Otra", "Cuenta", "sales", true),
            "ztestusertoma", "sales", takenIsActive);
        fixtures.seedUserFor(fixtures.seedWorker("ZTESTCRE08", "Otra", "Cuenta", "sales", true),
            "ztestusertoma2", "sales", takenIsActive);
        int workerId = seedTarget("ZTESTCRE09", "Testuser Toma", "sales");

        create(token, workerId).then().statusCode(201).body("account.username", equalTo("ztestusertoma1"));
    }

    /**
     * Tres homonimos de paterno largo: el segundo recorta la base para el sufijo, y el tercero tiene
     * que verlo para no repetirlo.
     */
    @Test
    void create_aThirdHomonymWithALongPaternal_getsTheNextSuffix() {
        String token = actorToken("admin", "crelargo");
        String paternal = "Testuser" + "l".repeat(52);
        String base = "ztestuser" + "l".repeat(52);
        List<String> generated = new ArrayList<>();

        for (String document : List.of("ZTESTCRE10", "ZTESTCRE11", "ZTESTCRE12")) {
            int workerId = fixtures.seedWorker(document, "Zeta", paternal, null, "sales", true);
            generated.add(create(token, workerId).then().statusCode(201).extract().path("account.username"));
        }

        assertEquals(List.of(base.substring(0, 50), base.substring(0, 49) + "1", base.substring(0, 49) + "2"),
            generated);
    }

    /** Defensa: una fila vieja sin letras latinas. */
    @Test
    void create_forAWorkerWithoutLatinLetters_getsTheFallbackWithItsId() {
        int workerId = fixtures.seedWorker("ZTESTCRE13", "Иван", "Петров", null, "sales", true);

        create(actorToken("admin", "crecir"), workerId).then().statusCode(201)
            .body("account.username", equalTo("usuario" + workerId));
    }

    // ---------- el trabajador --------------------------------------------------------

    @Test
    void create_forAWorkerDeactivated_returns409_USR005() {
        int workerId = fixtures.seedWorker("ZTESTCRE14", "Zeta", "Testuser Baja", null, "sales", false);

        assertRejected(create(actorToken("admin", "crebaja"), workerId), 409, "USR-005", workerId);
    }

    @ParameterizedTest
    @ValueSource(strings = {"driver", "escort", "assistant", "operator"})
    void create_forAWorkerWhoseRoleDoesNotLogIn_returns400_USR006(String role) {
        int workerId = seedTarget("ZTESTCRE15", "Testuser Sin Sesion", role);

        assertRejected(create(actorToken("admin", "cresinses"), workerId), 400, "USR-006", workerId);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void create_forAWorkerWithAnAccount_activeOrNot_returns409_USR003(boolean accountIsActive) {
        int workerId = seedTarget("ZTESTCRE16", "Testuser Tiene", "sales");
        fixtures.seedUserFor(workerId, "ztestusertienecta", "sales", accountIsActive);
        String token = actorToken("admin", "cretiene");
        List<Response> responses = new ArrayList<>();

        List<String> logs = logsWhile(() -> responses.add(create(token, workerId)));

        Response response = responses.get(0);
        assertEquals(409, response.statusCode());
        assertEquals("USR-003", response.jsonPath().getString("code"));
        assertTrue(logs.stream().noneMatch(line -> line.contains("choco")), "lo decide la lectura, no el unico: " + logs);
        assertEquals(1, fixtures.countUsersByWorkerId(workerId));
    }

    // ---------- el rango -------------------------------------------------------------

    @ParameterizedTest(name = "{0} sobre {1} -> {2}")
    @CsvSource({
        "finance_manager, warehouse_keeper, 201",
        "finance_manager, sales, 403",
        "general_manager, finance_manager, 201",
        "general_manager, operations_manager, 403",
        "general_manager, general_manager, 403",
        "admin, admin, 201",
    })
    void create_onlyForWorkersOfAStrictlyLowerLevel(String actorRole, String workerRole, int status) {
        String token = actorToken(actorRole, "crerango");
        int workerId = seedTarget("ZTESTCRE17", "Testuser Rango", workerRole);

        Response response = create(token, workerId);

        assertEquals(status, response.statusCode());
        if (status == 403) {
            assertEquals("USR-008", response.jsonPath().getString("code"));
            assertEquals("No puedes gestionar cuentas de ese cargo", response.jsonPath().getString("detail"));
            assertFalse(response.asString().contains(workerRole), "el detalle no nombra el cargo");
            assertEquals(0, fixtures.countUsersByWorkerId(workerId));
        }
    }

    /** El trabajador de la propia sesion ya tiene cuenta, y para el resto queda fuera de rango. */
    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({"admin, USR-003", "general_manager, USR-008"})
    void create_forTheWorkerOfTheSession(String role, String code) {
        ActorSeed actor = fixtures.seedActor(role, "crepropio");

        create(tokenOf(actor, role), actor.workerId()).then().body("code", equalTo(code));
    }

    // ---------- quien escribe, leido de la base y el orden ---------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"cuenta", "trabajador", "cargo"})
    void create_byAnActorNoLongerEnabled_withItsTokenStillValid_returns403_COM003(String what) {
        ActorSeed actor = disabledActor(what, "crebajaact");
        int workerId = seedTarget("ZTESTCRE18", "Testuser Actor", "warehouse_keeper");

        assertRejected(create(tokenOf(actor, "general_manager"), workerId), 403, "COM-003", workerId);
    }

    /** Dos errores contiguos de la cadena a la vez: gana el primero. */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
        "inexistente con quien escribe apagado -> USR-004",
        "fuera de rango con quien escribe apagado -> COM-003",
        "de baja y fuera de rango -> USR-008",
        "de baja y sin sesion -> USR-005",
        "sin sesion y con cuenta -> USR-006",
    })
    void create_withTwoErrorsAtOnce_answersTheFirstOfTheChain(String scenario) {
        String expected = scenario.substring(scenario.indexOf("-> ") + 3);
        boolean actorDisabled = scenario.contains("apagado");
        ActorSeed actor = actorDisabled ? disabledActor("trabajador", "creorden")
            : fixtures.seedActor("finance_manager", "creorden");
        String tokenRole = actorDisabled ? "general_manager" : "finance_manager";
        int workerId;
        if (scenario.startsWith("inexistente")) {
            workerId = 999999;
        } else if (scenario.startsWith("fuera de rango")) {
            workerId = seedTarget("ZTESTCRE19", "Testuser Orden", "operations_manager");
        } else if (scenario.startsWith("de baja y fuera")) {
            workerId = fixtures.seedWorker("ZTESTCRE19", "Zeta", "Testuser Orden", null, "sales", false);
        } else if (scenario.startsWith("de baja y sin")) {
            workerId = fixtures.seedWorker("ZTESTCRE19", "Zeta", "Testuser Orden", null, "driver", false);
        } else {
            workerId = seedTarget("ZTESTCRE19", "Testuser Orden", "driver");
            fixtures.seedUserFor(workerId, "ztestuserordencta", "driver", true);
        }

        create(tokenOf(actor, tokenRole), workerId).then().body("code", equalTo(expected));
    }

    // ---------- dos altas a la vez ----------------------------------------------------

    /**
     * Otra alta confirmo el mismo nombre despues de la lectura de lo tomado: con esa lectura vacia, el
     * INSERT choca con el unico del nombre. El 409 no deja fila ni rastro.
     */
    @Test
    void create_whenAnotherCreationTookTheNameAfterTheRead_returns409_USR002() {
        String token = actorToken("admin", "crecarrera");
        fixtures.seedUserFor(fixtures.seedWorker("ZTESTCRE20", "Otra", "Cuenta", "sales", true),
            "ztestusercarrera", "sales", true);
        int workerId = seedTarget("ZTESTCRE21", "Testuser Carrera", "sales");
        UserRepository spy = spyOnTheAccounts();
        Mockito.doReturn(Set.of()).when(spy).findUsernamesStartingWith(Mockito.anyString());

        List<Response> responses = new ArrayList<>();
        List<String> logs = logsWhile(() -> responses.add(create(token, workerId)));
        Response response = responses.get(0);

        assertEquals(409, response.statusCode());
        assertTrue(logs.stream().anyMatch(line -> line.contains("choco users_username_key")), "decidio el unico: " + logs);
        assertEquals("application/problem+json", response.contentType());
        assertEquals("USR-002", response.jsonPath().getString("code"));
        assertEquals("Ya existe una cuenta con ese nombre de usuario", response.jsonPath().getString("detail"));
        assertEquals(Set.of("type", "title", "status", "detail", "instance", "code", "traceId"),
            response.jsonPath().getMap("").keySet());
        assertNotSent(response, "Cache-Control", "Vary", "Pragma");
        assertEquals(0, fixtures.countUsersByWorkerId(workerId));
        assertEquals(List.of(), auditRowsNaming("ztestusercarrera"));
    }

    /** Antes que el nombre va el trabajador: con la cuenta propia, el 409 es el suyo aunque el nombre choque. */
    @Test
    void create_forAWorkerWithAnAccount_andItsNameTaken_returns409_USR003() {
        String token = actorToken("admin", "creambos");
        int workerId = seedTarget("ZTESTCRE22", "Testuser Ambos", "sales");
        fixtures.seedUserFor(workerId, "ztestuserambos", "sales", true);
        Mockito.doReturn(Set.of()).when(spyOnTheAccounts()).findUsernamesStartingWith(Mockito.anyString());

        create(token, workerId).then().statusCode(409).body("code", equalTo("USR-003"));
    }

    /** Defensa: con la lectura de su cuenta vaciada, decide el unico del trabajador. */
    @Test
    void create_whenAnotherCreationForTheSameWorkerWonAfterTheRead_returns409_USR003() {
        String token = actorToken("admin", "cremismo");
        int workerId = seedTarget("ZTESTCRE23", "Testuser Mismo", "sales");
        fixtures.seedUserFor(workerId, "ztestuserotracta", "sales", true);
        Mockito.doReturn(Optional.empty()).when(spyOnTheAccounts()).findIdByWorkerId(workerId);

        List<Response> responses = new ArrayList<>();
        List<String> logs = logsWhile(() -> responses.add(create(token, workerId)));
        Response response = responses.get(0);

        assertEquals(409, response.statusCode());
        assertTrue(logs.stream().anyMatch(line -> line.contains("choco users_worker_id_key")), "decidio el unico: " + logs);
        assertEquals("USR-003", response.jsonPath().getString("code"));
        assertEquals(1, fixtures.countUsersByWorkerId(workerId));
    }

    // ---------- la fila tomada --------------------------------------------------------

    /** El INSERT toma la fila del trabajador solo para compartir: sin tomarla antes, esto pasaria. */
    @Test
    void create_withTheWorkerRowHeldPastTheTimeout_returns409_USR009_andCreatesNothing() throws Exception {
        String token = actorToken("admin", "crefila");
        int workerId = seedTarget("ZTESTCRE24", "Testuser Fila Tomada", "sales");

        Response response = createWhileHolding(token, workerId,
            "SELECT id FROM public.workers WHERE id = " + workerId + " FOR NO KEY UPDATE");

        assertBusy(response, workerId);
    }

    /** La espera del INSERT del rastro, dentro del traductor: un 409 y no un error del servidor. */
    @Test
    void create_withTheTrailTableHeldPastTheTimeout_returns409_USR009_andCreatesNothing() throws Exception {
        String token = actorToken("admin", "crerastro");
        int workerId = seedTarget("ZTESTCRE25", "Testuser Rastro", "sales");

        Response response = createWhileHolding(token, workerId, "LOCK TABLE public.user_audit_logs IN SHARE MODE");

        assertBusy(response, workerId);
    }

    @Test
    void lockingTheWorker_appliesTheConfiguredTimeoutWithItsUnit() {
        int workerId = seedTarget("ZTESTCRE27", "Testuser Tope", "sales");

        String applied = QuarkusTransaction.requiringNew().call(() -> {
            userAccountWriteLock.lockWorkerThenFind(workerId);
            return (String) entityManager.createNativeQuery("SHOW lock_timeout").getSingleResult();
        });

        assertEquals(configuredLockTimeoutMillis + "ms", applied);
    }

    // ---------- la temporal no queda en ningun lado ------------------------------------

    /** Solo la respuesta la trae: ni el detalle, ni la lista, ni el rastro, ni el log del alta y el ingreso. */
    @Test
    void theTemporary_isNeitherInTheDetail_norInTheList_norInTheTrail_norInTheLogs() {
        String token = actorToken("admin", "crelogs");
        int workerId = seedTarget("ZTESTCRE26", "Testuser Logs", "sales");
        List<String> temporary = new ArrayList<>();
        List<Integer> accountId = new ArrayList<>();

        List<String> logs = logsWhile(() -> {
            Response response = create(token, workerId).then().statusCode(201).extract().response();
            temporary.add(response.jsonPath().getString("temporaryPassword"));
            accountId.add(response.jsonPath().getInt("account.id"));
            login("ztestuserlogs", temporary.get(0)).then().statusCode(200);
        });

        assertTrue(logs.stream().anyMatch(line -> line.contains("ztestuserlogs")), "la captura escucha: " + logs);
        assertTrue(logs.stream().noneMatch(line -> line.contains(temporary.get(0))), "la temporal en el log: " + logs);
        assertTrue(fixtures.userAuditRowsAsText().stream().noneMatch(row -> row.contains(temporary.get(0))));
        for (Response read : List.of(get(accountId.get(0)), list())) {
            assertEquals(200, read.statusCode());
            assertFalse(read.asString().contains(temporary.get(0)));
            assertFalse(read.asString().contains("temporaryPassword"));
        }
    }

    // ---------- fixtures ----------------------------------------------------------

    /** Un trabajador "Zeta" con ese paterno: el nombre que genera es la z y el paterno junto. */
    private int seedTarget(String document, String paternal, String roleName) {
        return fixtures.seedWorker(document, "Zeta", paternal, null, roleName, true);
    }

    private ActorSeed disabledActor(String what, String suffix) {
        ActorSeed actor = fixtures.seedActor("cargo".equals(what) ? "sales" : "general_manager", suffix);
        if ("cuenta".equals(what)) fixtures.setUserActive(actor.userId(), false);
        if ("trabajador".equals(what)) fixtures.setWorkerActive(actor.workerId(), false);
        return actor;
    }

    /** Lo demas de la cuenta lo hace la real: solo lo que cada caso vacia cambia. */
    private UserRepository spyOnTheAccounts() {
        UserRepository spy = Mockito.spy(ClientProxy.unwrap(userRepository));
        QuarkusMock.installMockForType(spy, UserRepository.class);
        return spy;
    }

    /**
     * Si el alta no se rinde, el motor corta al titular a los cinco segundos y el caso falla en vez de
     * colgar la suite. La conexion vuelve al pool sin nada pendiente.
     */
    private Response createWhileHolding(String token, int workerId, String holdingStatement) throws Exception {
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try {
                try (Statement statement = holder.createStatement()) {
                    statement.execute("SET LOCAL idle_in_transaction_session_timeout = '5s'");
                }
                try (PreparedStatement statement = holder.prepareStatement(holdingStatement)) {
                    statement.execute();
                }
                return create(token, workerId);
            } finally {
                rollbackUnlessTerminated(holder);
            }
        }
    }

    /** Si el motor ya corto al titular, no hay nada que deshacer: gana la asercion del caso y no esto. */
    private static void rollbackUnlessTerminated(Connection holder) throws SQLException {
        try {
            holder.rollback();
        } catch (SQLException rollbackFailed) {
            if (holder.isValid(1)) {
                throw rollbackFailed;
            }
        }
    }

    private void assertBusy(Response response, int workerId) {
        assertEquals(409, response.statusCode());
        assertEquals("USR-009", response.jsonPath().getString("code"));
        assertEquals(0, fixtures.countUsersByWorkerId(workerId));
    }

    private void assertRejected(Response response, int status, String code, int workerId) {
        assertEquals(status, response.statusCode());
        assertEquals(code, response.jsonPath().getString("code"));
        assertNotSent(response, "Cache-Control", "Vary", "Pragma");
        assertEquals(0, fixtures.countUsersByWorkerId(workerId));
    }

    private String actorToken(String role, String suffix) {
        return tokenOf(fixtures.seedActor(role, suffix), role);
    }

    private String tokenOf(ActorSeed actor, String tokenRole) {
        return fabricateTokenForUser(actor.userId(), "ztest-" + tokenRole, tokenRole);
    }

    private String workerRow(int workerId) {
        return (String) entityManager.createNativeQuery(
                "SELECT is_active || '|' || coalesce(updated_by::text, '-') || '|' || updated_at::text "
                    + "FROM public.workers WHERE id = ?1")
            .setParameter(1, workerId).getSingleResult();
    }

    private int workerTrailRows(int workerId) {
        return ((Number) entityManager.createNativeQuery(
                "SELECT count(*) FROM public.worker_audit_logs WHERE worker_id = ?1")
            .setParameter(1, workerId).getSingleResult()).intValue();
    }

    @SuppressWarnings("unchecked")
    private List<String> everyAccountBut(int accountId) {
        return entityManager.createNativeQuery(
                "SELECT id || '|' || coalesce(updated_by::text, '-') || '|' || updated_at::text || '|' "
                    + "|| password_hash || '|' || session_version || '|' || is_active FROM public.users "
                    + "WHERE id <> ?1 ORDER BY id")
            .setParameter(1, accountId).getResultList();
    }

    @SuppressWarnings("unchecked")
    private List<String> auditRowsNaming(String username) {
        return entityManager.createNativeQuery("SELECT id::text FROM public.user_audit_logs WHERE new_value = ?1")
            .setParameter(1, username).getResultList();
    }

    private static Response create(String token, int workerId) {
        return given().header("Authorization", "Bearer " + token).contentType(ContentType.JSON)
            .body("{\"workerId\":" + workerId + "}")
        .when().post("/users");
    }

    private static Response login(String username, String password) {
        return given().contentType(ContentType.JSON)
            .body("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}")
        .when().post("/auth/login");
    }

    private static Response get(int accountId) {
        return given().header("Authorization", "Bearer " + fabricateAccessToken("ztest-admin", "admin"))
            .when().get("/users/" + accountId);
    }

    private static Response list() {
        return given().header("Authorization", "Bearer " + fabricateAccessToken("ztest-admin", "admin"))
            .when().get("/users");
    }
}
