package com.scaramutti.tms.users;

import com.scaramutti.tms.support.WarehouseTestData;
import com.scaramutti.tms.support.WarehouseTestData.ActorSeed;
import com.scaramutti.tms.support.WarehouseTestData.UserAuditRow;
import com.scaramutti.tms.support.WarehouseTestData.UserAuthorship;
import io.quarkus.narayana.jta.QuarkusTransaction;
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

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.scaramutti.tms.support.LogCapture.logsWhile;
import static com.scaramutti.tms.support.ResponseHeaders.assertNotSent;
import static com.scaramutti.tms.support.ResponseHeaders.assertSentOnce;
import static com.scaramutti.tms.support.TestAuth.fabricateAccessToken;
import static com.scaramutti.tms.support.TestAuth.fabricateTokenForUser;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * POST /users/{id}/reset-password. Quien escribe y la cuenta son siempre cuentas de prueba
 * ({@code ztestuser}) sobre trabajadores de prueba ({@code ZTEST}): la base tiene un solo {@code admin}
 * sembrado y sus cuentas se llaman como su cargo.
 */
@QuarkusTest
class UserAccountResetPasswordResourceTest {

    private static final String PASSWORD = "Sales1234";
    private static final String NO_LOGIN_ROLE = "ztestrolerpr";
    private static final String TEMPORARY_PATTERN = "^[A-HJ-NP-Za-hjkmnp-z2-9]{12}$";

    @Inject WarehouseTestData fixtures;
    @Inject EntityManager entityManager;

    @BeforeEach
    @AfterEach
    void cleanupFixtures() {
        fixtures.deleteTestWorkerDependents();
        QuarkusTransaction.requiringNew().run(() -> fixtures.deleteTestWorkers());
        fixtures.deleteTestRoles();
    }

    // ---------- roles ------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"admin", "general_manager", "operations_manager", "finance_manager"})
    void reset_forEachRoleThatAdministersAccounts_returns200(String role) {
        String token = actorToken(role, "rpr" + role.substring(0, 3));
        int accountId = seedAccount("ZTESTRPR01", "warehouse_keeper", "ztestuserrpcta", true);

        reset(token, accountId).then().statusCode(200).body("account.id", equalTo(accountId));
    }

    @ParameterizedTest
    @ValueSource(strings = {"sales", "dispatcher", "warehouse_keeper"})
    void reset_forTheOtherRoles_returns403_COM003_withoutCacheHeaders_andChangesNothing(String role) {
        int accountId = seedAccount("ZTESTRPR02", "warehouse_keeper", "ztestuserrpotro", true);
        String hashBefore = passwordHashOf(accountId);

        Response response = reset(tokenOf(fixtures.seedActor("general_manager", "rprotro"), role), accountId);

        assertEquals(403, response.statusCode());
        assertEquals("COM-003", response.jsonPath().getString("code"));
        assertNotSent(response, "Cache-Control", "Vary", "Pragma");
        assertUnchanged(accountId, hashBefore);
    }

    @ParameterizedTest
    @ValueSource(strings = {"999999", "abc", "2147483648"})
    void reset_withRoleWithoutAccess_andAnyId_returns403NotA404(String id) {
        given().header("Authorization", "Bearer " + fabricateAccessToken("ztest-sales", "sales"))
        .when().post("/users/" + id + "/reset-password")
        .then().statusCode(403).body("code", equalTo("COM-003"));
    }

    @Test
    void reset_withoutToken_returns401() {
        given().when().post("/users/1/reset-password").then().statusCode(401);
    }

    // ---------- el cambio ----------------------------------------------------------

    @Test
    void reset_returns200_withATemporaryThatLogsIn_oneTrailRowWithoutValues_andRaisesTheSessionVersion() {
        ActorSeed actor = fixtures.seedActor("general_manager", "rprfirma");
        ActorSeed creator = fixtures.seedActor("operations_manager", "rprcrea");
        int accountId = seedAccount("ZTESTRPR03", "finance_manager", "ztestuserrpfila", true);
        fixtures.copyPasswordHashFrom(accountId, "sales");
        OffsetDateTime createdAt = OffsetDateTime.of(2026, 1, 2, 3, 4, 5, 0, ZoneOffset.UTC);
        setAccountAuthorship(accountId, creator.userId(), createdAt);
        setSessionVersion(accountId, 4);
        OffsetDateTime before = OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(1);

        Response response = reset(tokenOf(actor, "general_manager"), accountId);

        assertEquals(200, response.statusCode());
        assertEquals(List.of("account", "temporaryPassword"),
            response.jsonPath().getMap("").keySet().stream().sorted().toList());
        String temporary = response.jsonPath().getString("temporaryPassword");
        assertTrue(temporary.matches(TEMPORARY_PATTERN), "la temporal sigue el patron del contrato");
        Map<String, Object> account = response.jsonPath().getMap("account");
        assertEquals(get(accountId).jsonPath().getMap(""), account, "account es el detalle que trae la lectura");
        assertEquals(actor.userId(), response.jsonPath().getInt("account.updatedBy.id"));
        assertEquals(true, account.get("isActive"));

        login("ztestuserrpfila", PASSWORD).then().statusCode(401).body("code", equalTo("AUTH-001"));
        login("ztestuserrpfila", temporary).then().statusCode(200);

        List<UserAuditRow> rows = fixtures.userAuditRows(accountId);
        assertEquals(1, rows.size());
        UserAuditRow row = rows.get(0);
        assertEquals(new UserAuditRow("PASSWORD_RESET", "password", "Contraseña", null, null, actor.userId(),
            row.loggedAt()), row);
        assertTrue(row.loggedAt().isAfter(before), "la fila es de este pedido");

        UserAuthorship authorship = fixtures.userAuthorshipOf(accountId);
        assertEquals(creator.userId(), authorship.createdBy(), "quien la creo no cambia");
        assertEquals(createdAt.toInstant(), authorship.createdAt().toInstant());
        assertEquals(actor.userId(), authorship.updatedBy());
        assertTrue(authorship.updatedAt().isAfter(before), "la marca de cambio se mueve");
        assertTrue(fixtures.userIsActive(accountId));
        assertEquals(5, sessionVersionOf(accountId));
    }

    @Test
    void reset_touchesNeitherTheWorkerNorAnyOtherAccount() {
        String token = actorToken("admin", "rprsolo");
        int workerId = fixtures.seedWorker("ZTESTRPR04", "Ana", "Ztsolo", null, "sales", true);
        int accountId = fixtures.seedUserFor(workerId, "ztestuserrpsolo", "sales", true);
        String workerBefore = workerRow(workerId);
        List<String> othersBefore = everyAccountBut(accountId);

        reset(token, accountId).then().statusCode(200);

        assertEquals(workerBefore, workerRow(workerId), "el trabajador sigue igual y sin firma nueva");
        assertEquals(0, workerTrailRows(workerId));
        assertEquals(othersBefore, everyAccountBut(accountId));
    }

    @Test
    void reset_twice_givesTwoDifferentTemporaries_andOnlyTheSecondLogsIn() {
        String token = actorToken("admin", "rprdos");
        int accountId = seedAccount("ZTESTRPR05", "sales", "ztestuserrpdos", true);

        String first = reset(token, accountId).then().statusCode(200).extract().path("temporaryPassword");
        String second = reset(token, accountId).then().statusCode(200).extract().path("temporaryPassword");

        assertNotEquals(first, second);
        login("ztestuserrpdos", first).then().statusCode(401).body("code", equalTo("AUTH-001"));
        login("ztestuserrpdos", second).then().statusCode(200);
        assertEquals(2, fixtures.userAuditRows(accountId).size());
        assertEquals(2, sessionVersionOf(accountId));
    }

    @ParameterizedTest
    @ValueSource(strings = {"cuenta", "trabajador", "cargo"})
    void reset_anAccountThatCannotLogIn_returns200_staysAsItWas_andItsTemporaryIsNotYetUsable(String what) {
        String token = actorToken("admin", "rprnoing");
        int accountId = switch (what) {
            case "cuenta" -> seedAccount("ZTESTRPR06", "sales", "ztestuserrpnoing", false);
            case "trabajador" -> fixtures.seedUserFor(
                fixtures.seedWorker("ZTESTRPR06", "Cuenta", "Ztnoingresa", null, "sales", false),
                "ztestuserrpnoing", "sales", true);
            default -> seedAccountOnANoLoginRole("ZTESTRPR06", "ztestuserrpnoing");
        };
        boolean wasActive = fixtures.userIsActive(accountId);

        String temporary = reset(token, accountId).then().statusCode(200)
            .body("account.isActive", equalTo(wasActive)).extract().path("temporaryPassword");

        assertEquals(wasActive, fixtures.userIsActive(accountId));
        assertEquals(1, fixtures.userAuditRows(accountId).size());
        assertEquals(1, sessionVersionOf(accountId));
        login("ztestuserrpnoing", temporary).then().statusCode(401).body("code", equalTo("AUTH-002"));
    }

    // ---------- la propia cuenta y el rango ------------------------------------------

    /** Tambien {@code admin}, exento del rango; y antes que el rango, que para el resto diria otra cosa. */
    @ParameterizedTest
    @ValueSource(strings = {"admin", "general_manager", "operations_manager", "finance_manager"})
    void reset_theOwnAccount_returns403_USR007_withAConstantDetail(String role) {
        ActorSeed actor = fixtures.seedActor(role, "rprprop");
        String hashBefore = passwordHashOf(actor.userId());

        Response response = reset(tokenOf(actor, role), actor.userId());

        assertEquals(403, response.statusCode());
        assertEquals("application/problem+json", response.contentType());
        assertEquals("USR-007", response.jsonPath().getString("code"));
        assertEquals("urn:tms:error:usr-007", response.jsonPath().getString("type"));
        assertEquals("Forbidden", response.jsonPath().getString("title"));
        assertEquals("No puedes hacer esto sobre tu propia cuenta", response.jsonPath().getString("detail"));
        assertNotSent(response, "Cache-Control", "Vary", "Pragma");
        assertUnchanged(actor.userId(), hashBefore);
    }

    @Test
    void reset_anAdminOverAnotherAdmin_returns200() {
        String token = actorToken("admin", "rpradmuno");
        int otherAdmin = fixtures.seedActor("admin", "rpradmdos").userId();

        reset(token, otherAdmin).then().statusCode(200);
    }

    @ParameterizedTest(name = "{0} sobre {1} -> {2}")
    @CsvSource({
        "general_manager, finance_manager, 200",
        "general_manager, operations_manager, 403",
        "finance_manager, warehouse_keeper, 200",
        "finance_manager, sales, 403",
        "finance_manager, admin, 403",
    })
    void reset_onlyOverAccountsOfAStrictlyLowerLevel(String actorRole, String accountRole, int status) {
        String token = actorToken(actorRole, "rprrango");
        int accountId = seedAccount("ZTESTRPR07", accountRole, "ztestuserrprango", true);
        String hashBefore = passwordHashOf(accountId);

        Response response = reset(token, accountId);

        assertEquals(status, response.statusCode());
        if (status == 403) {
            assertEquals("USR-008", response.jsonPath().getString("code"));
            assertEquals("No puedes gestionar cuentas de ese cargo", response.jsonPath().getString("detail"));
            assertFalse(response.asString().contains(accountRole), "el detalle no nombra el cargo");
            assertUnchanged(accountId, hashBefore);
        } else {
            assertNotEquals(hashBefore, passwordHashOf(accountId));
        }
    }

    // ---------- quien escribe, leido de la base --------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"cuenta", "trabajador", "cargo"})
    void reset_byAnActorNoLongerEnabled_withItsTokenStillValid_returns403_COM003(String what) {
        ActorSeed actor = fixtures.seedActor("cargo".equals(what) ? "sales" : "general_manager", "rprbaja");
        if ("cuenta".equals(what)) fixtures.setUserActive(actor.userId(), false);
        if ("trabajador".equals(what)) fixtures.setWorkerActive(actor.workerId(), false);
        int accountId = seedAccount("ZTESTRPR08", "warehouse_keeper", "ztestuserrpbaja", true);
        String hashBefore = passwordHashOf(accountId);

        reset(tokenOf(actor, "general_manager"), accountId).then().statusCode(403).body("code", equalTo("COM-003"));
        assertUnchanged(accountId, hashBefore);
    }

    /** Quien ya no escribe tampoco salta el orden: la propia cuenta da el 403 comun, no USR-007. */
    @Test
    void reset_theOwnAccount_byAnActorNoLongerEnabled_returns403_COM003() {
        ActorSeed actor = fixtures.seedActor("general_manager", "rprbajap");
        fixtures.setWorkerActive(actor.workerId(), false);

        reset(tokenOf(actor, "general_manager"), actor.userId()).then().statusCode(403)
            .body("code", equalTo("COM-003"));
    }

    /** El 404 va antes que quien escribe: un id que no existe da 404 tambien a quien ya no escribe. */
    @Test
    void reset_anIdThatDoesNotExist_byAnActorNoLongerEnabled_returns404_USR001() {
        ActorSeed actor = fixtures.seedActor("general_manager", "rprbaja4");
        fixtures.setWorkerActive(actor.workerId(), false);

        reset(tokenOf(actor, "general_manager"), 999999).then().statusCode(404).body("code", equalTo("USR-001"));
    }

    // ---------- 404 ------------------------------------------------------------------

    @Test
    void reset_anIdThatDoesNotExist_returns404_USR001_withoutCacheHeaders() {
        Response response = reset(actorToken("admin", "rprno"), 999999);

        assertEquals(404, response.statusCode());
        assertEquals("application/problem+json", response.contentType());
        assertEquals("USR-001", response.jsonPath().getString("code"));
        assertNotSent(response, "Cache-Control", "Vary", "Pragma");
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "2147483648"})
    void reset_anIdThatIsNotAnInt32_returns404WithoutBody(String id) {
        given().header("Authorization", "Bearer " + actorToken("admin", "rprnoint"))
        .when().post("/users/" + id + "/reset-password")
        .then().statusCode(404).body(equalTo(""));
    }

    // ---------- cabeceras y cuerpo ---------------------------------------------------

    @Test
    void reset_sendsNoStoreAndVaryOnce() {
        int accountId = seedAccount("ZTESTRPR09", "sales", "ztestuserrpcache", true);

        Response response = reset(actorToken("admin", "rprcache"), accountId);

        assertEquals(200, response.statusCode());
        assertSentOnce(response, "Cache-Control", "no-store");
        assertSentOnce(response, "Vary", "Authorization");
        assertNotSent(response, "Pragma");
    }

    @ParameterizedTest
    @CsvSource(value = {
        "application/json|{\"temporaryPassword\":\"Cuerpo123\"}",
        "application/json|{roto",
        "text/plain|Cuerpo123",
        "application/x-www-form-urlencoded|temporaryPassword=Cuerpo123",
        "-|-",
    }, delimiter = '|')
    void reset_ignoresAnyBodyAndContentType_andAPasswordInTheBodyDoesNotLogIn(String contentType, String body) {
        String token = actorToken("admin", "rprcuerpo");
        int accountId = seedAccount("ZTESTRPR10", "sales", "ztestuserrpcuerpo", true);

        var request = given().header("Authorization", "Bearer " + token);
        if (!"-".equals(contentType)) {
            request = request.contentType(contentType).body(body);
        }
        String temporary = request.when().post("/users/" + accountId + "/reset-password")
            .then().statusCode(200).extract().path("temporaryPassword");

        login("ztestuserrpcuerpo", "Cuerpo123").then().statusCode(401).body("code", equalTo("AUTH-001"));
        login("ztestuserrpcuerpo", temporary).then().statusCode(200);
    }

    // ---------- la temporal no queda en ningun lado ------------------------------------

    /**
     * Solo la respuesta la trae: ni el detalle, ni la lista, ni lo que se loguea mientras se pide y
     * se ingresa con ella. El ingreso deja su linea, asi que la captura escucha.
     */
    @Test
    void theTemporary_isNeitherInTheDetail_norInTheList_norInTheLogs() {
        String token = actorToken("admin", "rprlogs");
        int accountId = seedAccount("ZTESTRPR11", "sales", "ztestuserrplogs", true);
        List<String> temporary = new ArrayList<>();

        List<String> logs = logsWhile(() -> {
            temporary.add(reset(token, accountId).then().statusCode(200).extract().path("temporaryPassword"));
            login("ztestuserrplogs", temporary.get(0)).then().statusCode(200);
        });

        assertTrue(logs.stream().anyMatch(line -> line.contains("ztestuserrplogs")), "la captura escucha: " + logs);
        assertTrue(logs.stream().noneMatch(line -> line.contains(temporary.get(0))), "la temporal en el log: " + logs);
        for (Response read : List.of(get(accountId), list())) {
            assertEquals(200, read.statusCode());
            assertFalse(read.asString().contains(temporary.get(0)));
            assertFalse(read.asString().contains("temporaryPassword"));
        }
    }

    // ---------- las sesiones ---------------------------------------------------------

    @Test
    void afterResetting_aRefreshFromBeforeNoLongerRenews_butOneFromALoginWithTheTemporaryDoes() {
        String adminToken = actorToken("admin", "rprsesion");
        int accountId = seedAccount("ZTESTRPR12", "finance_manager", "ztestuserrpsesion", true);
        fixtures.copyPasswordHashFrom(accountId, "sales");
        Response session = login("ztestuserrpsesion", PASSWORD).then().statusCode(200).extract().response();
        String oldAccessToken = session.jsonPath().getString("token");
        String oldRefreshToken = session.jsonPath().getString("refreshToken");

        String temporary = reset(adminToken, accountId).then().statusCode(200).extract().path("temporaryPassword");

        refresh(oldRefreshToken).then().statusCode(401).body("code", equalTo("AUTH-003"));
        String newRefreshToken = login("ztestuserrpsesion", temporary).then().statusCode(200)
            .extract().path("refreshToken");
        refresh(newRefreshToken).then().statusCode(200);
        given().header("Authorization", "Bearer " + oldAccessToken)
        .when().get("/auth/me").then().statusCode(200);
        given().header("Authorization", "Bearer " + oldAccessToken).contentType(ContentType.JSON)
            .body("{\"currentPassword\":\"" + PASSWORD + "\",\"newPassword\":\"Otra12345\"}")
        .when().post("/auth/change-password").then().statusCode(400).body("code", equalTo("AUTH-004"));
    }

    // ---------- fixtures ----------------------------------------------------------

    private int seedAccount(String document, String roleName, String username, boolean accountIsActive) {
        int workerId = fixtures.seedWorker(document, "Cuenta", "Ztrestablecer", null, roleName, true);
        return fixtures.seedUserFor(workerId, username, roleName, accountIsActive);
    }

    private int seedAccountOnANoLoginRole(String document, String username) {
        fixtures.seedRole(NO_LOGIN_ROLE, "ZTEST cargo sin sesion", 1, false, "NONE", true);
        int workerId = fixtures.seedWorker(document, "Cuenta", "Ztsinsesion", null, "sales", true);
        int accountId = fixtures.seedUserFor(workerId, username, "sales", true);
        fixtures.setWorkerRole(workerId, NO_LOGIN_ROLE);
        return accountId;
    }

    private String actorToken(String role, String suffix) {
        return tokenOf(fixtures.seedActor(role, suffix), role);
    }

    private String tokenOf(ActorSeed actor, String tokenRole) {
        return fabricateTokenForUser(actor.userId(), "ztest-" + tokenRole, tokenRole);
    }

    private void assertUnchanged(int accountId, String hashBefore) {
        assertEquals(hashBefore, passwordHashOf(accountId));
        assertEquals(0, sessionVersionOf(accountId));
        assertEquals(List.of(), fixtures.userAuditRows(accountId));
        assertEquals(null, fixtures.userAuthorshipOf(accountId).updatedBy());
    }

    private void setAccountAuthorship(int accountId, int createdBy, OffsetDateTime createdAt) {
        QuarkusTransaction.requiringNew().run(() -> entityManager.createNativeQuery(
                "UPDATE public.users SET created_by = ?1, created_at = ?2, updated_at = ?2 WHERE id = ?3")
            .setParameter(1, createdBy).setParameter(2, createdAt).setParameter(3, accountId).executeUpdate());
    }

    private void setSessionVersion(int accountId, int version) {
        QuarkusTransaction.requiringNew().run(() -> entityManager.createNativeQuery(
                "UPDATE public.users SET session_version = ?1 WHERE id = ?2 AND username LIKE 'ztestuser%'")
            .setParameter(1, version).setParameter(2, accountId).executeUpdate());
    }

    private int sessionVersionOf(int accountId) {
        return ((Number) entityManager.createNativeQuery(
                "SELECT session_version FROM public.users WHERE id = ?1")
            .setParameter(1, accountId).getSingleResult()).intValue();
    }

    private String passwordHashOf(int accountId) {
        return (String) entityManager.createNativeQuery("SELECT password_hash FROM public.users WHERE id = ?1")
            .setParameter(1, accountId).getSingleResult();
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
                    + "|| password_hash || '|' || session_version FROM public.users WHERE id <> ?1 ORDER BY id")
            .setParameter(1, accountId).getResultList();
    }

    private static Response login(String username, String password) {
        return given().contentType(ContentType.JSON)
            .body("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}")
        .when().post("/auth/login");
    }

    private static Response refresh(String refreshToken) {
        return given().contentType(ContentType.JSON).body("{\"refreshToken\":\"" + refreshToken + "\"}")
            .when().post("/auth/refresh");
    }

    private static Response get(int accountId) {
        return given().header("Authorization", "Bearer " + fabricateAccessToken("ztest-admin", "admin"))
            .when().get("/users/" + accountId);
    }

    private static Response list() {
        return given().header("Authorization", "Bearer " + fabricateAccessToken("ztest-admin", "admin"))
            .when().get("/users");
    }

    private static Response reset(String token, Object id) {
        return given().header("Authorization", "Bearer " + token).when().post("/users/" + id + "/reset-password");
    }
}
