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

import static com.scaramutti.tms.support.ResponseHeaders.assertNotSent;
import static com.scaramutti.tms.support.ResponseHeaders.assertSentOnce;
import static com.scaramutti.tms.support.TestAuth.fabricateAccessToken;
import static com.scaramutti.tms.support.TestAuth.fabricateTokenForUser;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * POST /users/{id}/deactivate. Quien escribe y la cuenta son siempre cuentas de prueba
 * ({@code ztestuser}) sobre trabajadores de prueba ({@code ZTEST}): la base tiene un solo {@code admin}
 * sembrado y sus cuentas se llaman como su cargo.
 */
@QuarkusTest
class UserAccountDeactivateResourceTest {

    private static final String PASSWORD = "Sales1234";

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
    void deactivate_forEachRoleThatAdministersAccounts_returns200(String role) {
        String token = actorToken(role, "dsr" + role.substring(0, 3));
        int accountId = seedAccount("ZTESTDSR01", "warehouse_keeper", "ztestuserctadest", true);

        deactivate(token, accountId).then().statusCode(200).body("isActive", equalTo(false));
    }

    @ParameterizedTest
    @ValueSource(strings = {"sales", "dispatcher", "warehouse_keeper"})
    void deactivate_forTheOtherRoles_returns403_COM003_withoutCacheHeaders_andChangesNothing(String role) {
        int accountId = seedAccount("ZTESTDSR02", "warehouse_keeper", "ztestuserctaotro", true);

        Response response = deactivate(fabricateAccessToken("ztest-" + role, role), accountId);

        assertEquals(403, response.statusCode());
        assertEquals("COM-003", response.jsonPath().getString("code"));
        assertNotSent(response, "Cache-Control", "Vary", "Pragma");
        assertUnchanged(accountId, true);
    }

    /** El cargo se decide antes que el id: el mismo 403 con un id inexistente o que no es un entero. */
    @ParameterizedTest
    @ValueSource(strings = {"999999", "abc", "2147483648"})
    void deactivate_withRoleWithoutAccess_andAnyId_returns403NotA404(String id) {
        given().header("Authorization", "Bearer " + fabricateAccessToken("ztest-sales", "sales"))
        .when().post("/users/" + id + "/deactivate")
        .then().statusCode(403).body("code", equalTo("COM-003"));
    }

    @Test
    void deactivate_withoutToken_returns401() {
        given().when().post("/users/1/deactivate").then().statusCode(401);
    }

    // ---------- el cambio ----------------------------------------------------------

    /**
     * Una sola fila de rastro, entera, firmada por quien desactiva; la cuenta queda firmada por el y con
     * una marca nueva, y quien la creo no cambia. La respuesta es el detalle tal como lo trae la lectura.
     */
    @Test
    void deactivate_anActiveAccount_returns200Inactive_withOneTrailRow_andSignsIt_keepingItsCreator() {
        ActorSeed actor = fixtures.seedActor("general_manager", "dsrfirma");
        ActorSeed creator = fixtures.seedActor("operations_manager", "dsrcrea");
        int accountId = seedAccount("ZTESTDSR03", "finance_manager", "ztestuserctafila", true);
        OffsetDateTime createdAt = OffsetDateTime.of(2026, 1, 2, 3, 4, 5, 0, ZoneOffset.UTC);
        setAccountAuthorship(accountId, creator.userId(), createdAt);
        OffsetDateTime before = OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(1);

        Response response = deactivate(tokenOf(actor, "general_manager"), accountId);

        assertEquals(200, response.statusCode());
        Map<String, Object> body = response.jsonPath().getMap("");
        assertEquals(false, body.get("isActive"));
        assertEquals(get(accountId).jsonPath().getMap(""), body, "la respuesta es el detalle que trae la lectura");
        assertEquals(actor.userId(), response.jsonPath().getInt("updatedBy.id"));
        assertEquals(creator.userId(), response.jsonPath().getInt("createdBy.id"));

        List<UserAuditRow> rows = fixtures.userAuditRows(accountId);
        assertEquals(1, rows.size());
        UserAuditRow row = rows.get(0);
        assertEquals(new UserAuditRow("DEACTIVATED", "isActive", "Cuenta", "true", "false", actor.userId(),
            row.loggedAt()), row);
        assertTrue(row.loggedAt().isAfter(before), "la fila es de este pedido");

        UserAuthorship authorship = fixtures.userAuthorshipOf(accountId);
        assertEquals(creator.userId(), authorship.createdBy(), "quien la creo no cambia");
        assertEquals(createdAt.toInstant(), authorship.createdAt().toInstant());
        assertEquals(actor.userId(), authorship.updatedBy());
        assertTrue(authorship.updatedAt().isAfter(before), "la marca de cambio se mueve");
        assertFalse(fixtures.userIsActive(accountId));
    }

    /** Ni el trabajador, ni su rastro, ni la firma de otra cuenta: solo la fila de esta. */
    @Test
    void deactivate_touchesNeitherTheWorkerNorAnyOtherAccount() {
        String token = actorToken("admin", "dsrsolo");
        int workerId = fixtures.seedWorker("ZTESTDSR04", "Ana", "Ztsolo", null, "sales", true);
        int accountId = fixtures.seedUserFor(workerId, "ztestuserctasolo", "sales", true);
        String workerBefore = workerRow(workerId);
        List<String> othersBefore = authorshipOfEveryAccountBut(accountId);

        deactivate(token, accountId).then().statusCode(200);

        assertEquals(workerBefore, workerRow(workerId), "el trabajador sigue activo y sin firma nueva");
        assertEquals(0, workerTrailRows(workerId));
        assertEquals(othersBefore, authorshipOfEveryAccountBut(accountId));
    }

    @Test
    void deactivate_anInactiveAccount_returns200_withoutTrailRow_andWithoutSignature() {
        String token = actorToken("admin", "dsrrep");
        int accountId = seedAccount("ZTESTDSR05", "sales", "ztestuserctarep", false);
        UserAuthorship before = fixtures.userAuthorshipOf(accountId);

        deactivate(token, accountId).then().statusCode(200).body("isActive", equalTo(false));

        assertEquals(List.of(), fixtures.userAuditRows(accountId));
        assertEquals(before, fixtures.userAuthorshipOf(accountId));
    }

    /** Las guardas corren antes del corte idempotente. */
    @Test
    void deactivate_anInactiveAccountOutOfRank_returns403_USR008() {
        String token = actorToken("finance_manager", "dsrrepr");
        int accountId = seedAccount("ZTESTDSR06", "sales", "ztestuserctarepr", false);

        deactivate(token, accountId).then().statusCode(403).body("code", equalTo("USR-008"));
        assertUnchanged(accountId, false);
    }

    // ---------- la propia cuenta y el rango ------------------------------------------

    /** Tambien {@code admin}, exento del rango; y antes que el rango, que para el resto diria otra cosa. */
    @ParameterizedTest
    @ValueSource(strings = {"admin", "general_manager", "operations_manager", "finance_manager"})
    void deactivate_theOwnAccount_returns403_USR007_withAConstantDetail(String role) {
        ActorSeed actor = fixtures.seedActor(role, "dsrprop");

        Response response = deactivate(tokenOf(actor, role), actor.userId());

        assertEquals(403, response.statusCode());
        assertEquals("application/problem+json", response.contentType());
        assertEquals("USR-007", response.jsonPath().getString("code"));
        assertEquals("urn:tms:error:usr-007", response.jsonPath().getString("type"));
        assertEquals("Forbidden", response.jsonPath().getString("title"));
        assertEquals("No puedes hacer esto sobre tu propia cuenta", response.jsonPath().getString("detail"));
        assertNotSent(response, "Cache-Control", "Vary", "Pragma");
        assertUnchanged(actor.userId(), true);
    }

    @Test
    void deactivate_anAdminOverAnotherAdmin_returns200() {
        String token = actorToken("admin", "dsradmuno");
        int otherAdmin = fixtures.seedActor("admin", "dsradmdos").userId();

        deactivate(token, otherAdmin).then().statusCode(200).body("isActive", equalTo(false));
    }

    @ParameterizedTest(name = "{0} sobre {1} -> {2}")
    @CsvSource({
        "general_manager, finance_manager, 200",
        "general_manager, operations_manager, 403",
        "finance_manager, warehouse_keeper, 200",
        "finance_manager, sales, 403",
        "finance_manager, admin, 403",
    })
    void deactivate_onlyOverAccountsOfAStrictlyLowerLevel(String actorRole, String accountRole, int status) {
        String token = actorToken(actorRole, "dsrrango");
        int accountId = seedAccount("ZTESTDSR07", accountRole, "ztestuserctacuenta", true);

        Response response = deactivate(token, accountId);

        assertEquals(status, response.statusCode());
        if (status == 403) {
            assertEquals("USR-008", response.jsonPath().getString("code"));
            assertEquals("No puedes gestionar cuentas de ese cargo", response.jsonPath().getString("detail"));
            assertFalse(response.asString().contains(accountRole), "el detalle no nombra el cargo");
            assertUnchanged(accountId, true);
        } else {
            assertFalse(fixtures.userIsActive(accountId));
        }
    }

    // ---------- quien escribe, leido de la base --------------------------------------

    /** El token sigue vigente, pero quien lo lleva ya no escribe: la cuenta apagada, el trabajador de baja u otro cargo. */
    @ParameterizedTest
    @ValueSource(strings = {"cuenta", "trabajador", "cargo"})
    void deactivate_byAnActorNoLongerEnabled_withItsTokenStillValid_returns403_COM003(String what) {
        ActorSeed actor = fixtures.seedActor("cargo".equals(what) ? "sales" : "general_manager", "dsrbaja");
        if ("cuenta".equals(what)) fixtures.setUserActive(actor.userId(), false);
        if ("trabajador".equals(what)) fixtures.setWorkerActive(actor.workerId(), false);
        int accountId = seedAccount("ZTESTDSR08", "warehouse_keeper", "ztestuserctabaja", true);

        deactivate(tokenOf(actor, "general_manager"), accountId).then().statusCode(403).body("code", equalTo("COM-003"));
        assertUnchanged(accountId, true);
    }

    /** Quien ya no escribe tampoco salta el orden: la propia cuenta da el 403 comun, no USR-007. */
    @Test
    void deactivate_theOwnAccount_byAnActorNoLongerEnabled_returns403_COM003() {
        ActorSeed actor = fixtures.seedActor("general_manager", "dsrbajap");
        fixtures.setWorkerActive(actor.workerId(), false);

        deactivate(tokenOf(actor, "general_manager"), actor.userId()).then().statusCode(403)
            .body("code", equalTo("COM-003"));
    }

    /** El 404 va antes que quien escribe: un id que no existe da 404 tambien a quien ya no escribe. */
    @Test
    void deactivate_anIdThatDoesNotExist_byAnActorNoLongerEnabled_returns404_USR001() {
        ActorSeed actor = fixtures.seedActor("general_manager", "dsrbaja4");
        fixtures.setWorkerActive(actor.workerId(), false);

        deactivate(tokenOf(actor, "general_manager"), 999999).then().statusCode(404).body("code", equalTo("USR-001"));
    }

    // ---------- 404 ------------------------------------------------------------------

    @Test
    void deactivate_anIdThatDoesNotExist_returns404_USR001_withoutCacheHeaders() {
        Response response = deactivate(actorToken("admin", "dsrno"), 999999);

        assertEquals(404, response.statusCode());
        assertEquals("application/problem+json", response.contentType());
        assertEquals("USR-001", response.jsonPath().getString("code"));
        assertNotSent(response, "Cache-Control", "Vary", "Pragma");
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "2147483648"})
    void deactivate_anIdThatIsNotAnInt32_returns404WithoutBody(String id) {
        given().header("Authorization", "Bearer " + actorToken("admin", "dsrnoint"))
        .when().post("/users/" + id + "/deactivate")
        .then().statusCode(404).body(equalTo(""));
    }

    // ---------- cabeceras y cuerpo ---------------------------------------------------

    @Test
    void deactivate_sendsNoStoreAndVaryOnce() {
        int accountId = seedAccount("ZTESTDSR09", "sales", "ztestuserctacache", true);

        Response response = deactivate(actorToken("admin", "dsrcache"), accountId);

        assertEquals(200, response.statusCode());
        assertSentOnce(response, "Cache-Control", "no-store");
        assertSentOnce(response, "Vary", "Authorization");
        assertNotSent(response, "Pragma");
    }

    /** Sin cuerpo: uno que pide dejarla activa, otro tipo de contenido o ninguno se ignoran. */
    @ParameterizedTest
    @CsvSource(value = {
        "application/json|{\"isActive\":true}",
        "application/json|{roto",
        "text/plain|activa",
        "application/x-www-form-urlencoded|isActive=true",
        "-|-",
    }, delimiter = '|')
    void deactivate_ignoresAnyBodyAndContentType(String contentType, String body) {
        String token = actorToken("admin", "dsrcuerpo");
        int accountId = seedAccount("ZTESTDSR10", "sales", "ztestuserctacuerpo", true);

        var request = given().header("Authorization", "Bearer " + token);
        if (!"-".equals(contentType)) {
            request = request.contentType(contentType).body(body);
        }
        request.when().post("/users/" + accountId + "/deactivate").then().statusCode(200).body("isActive", equalTo(false));
    }

    // ---------- despues -----------------------------------------------------------------

    /**
     * La cuenta apagada ya no ingresa, no renueva y no lee la sesion; su token de antes sigue leyendo
     * hasta que vence, pero ya no desactiva: Usuarios relee de la base a quien escribe.
     */
    @Test
    void afterDeactivating_theAccountNoLongerLogsInNorRefreshes_andItsTokenStillReadsButNoLongerDeactivates() {
        String adminToken = actorToken("admin", "dsrdesp");
        int accountId = seedAccount("ZTESTDSR11", "finance_manager", "ztestuserctadesp", true);
        fixtures.copyPasswordHashFrom(accountId, "sales");
        int belowId = seedAccount("ZTESTDSR12", "warehouse_keeper", "ztestuserctaabajo", true);
        Response session = loginResponse("ztestuserctadesp");
        String accessToken = session.jsonPath().getString("token");
        String refreshToken = session.jsonPath().getString("refreshToken");

        deactivate(adminToken, accountId).then().statusCode(200);

        given().contentType(ContentType.JSON)
            .body("{\"username\":\"ztestuserctadesp\",\"password\":\"" + PASSWORD + "\"}")
        .when().post("/auth/login").then().statusCode(401).body("code", equalTo("AUTH-002"));
        given().contentType(ContentType.JSON).body("{\"refreshToken\":\"" + refreshToken + "\"}")
        .when().post("/auth/refresh").then().statusCode(401).body("code", equalTo("AUTH-002"));
        given().header("Authorization", "Bearer " + accessToken)
        .when().get("/auth/me").then().statusCode(401).body("code", equalTo("AUTH-002"));
        given().header("Authorization", "Bearer " + accessToken)
        .when().get("/users/" + belowId).then().statusCode(200);
        deactivate(accessToken, belowId).then().statusCode(403).body("code", equalTo("COM-003"));
        assertUnchanged(belowId, true);
    }

    // ---------- fixtures ----------------------------------------------------------

    private int seedAccount(String document, String roleName, String username, boolean accountIsActive) {
        int workerId = fixtures.seedWorker(document, "Cuenta", "Ztdesactivar", null, roleName, true);
        return fixtures.seedUserFor(workerId, username, roleName, accountIsActive);
    }

    private String actorToken(String role, String suffix) {
        return tokenOf(fixtures.seedActor(role, suffix), role);
    }

    private String tokenOf(ActorSeed actor, String tokenRole) {
        return fabricateTokenForUser(actor.userId(), "ztest-" + tokenRole, tokenRole);
    }

    /** Sin cambio: el mismo estado, ninguna fila de rastro y sin firma. */
    private void assertUnchanged(int accountId, boolean isActive) {
        assertEquals(isActive, fixtures.userIsActive(accountId));
        assertEquals(List.of(), fixtures.userAuditRows(accountId));
        assertEquals(null, fixtures.userAuthorshipOf(accountId).updatedBy());
    }

    private void setAccountAuthorship(int accountId, int createdBy, OffsetDateTime createdAt) {
        QuarkusTransaction.requiringNew().run(() -> entityManager.createNativeQuery(
                "UPDATE public.users SET created_by = ?1, created_at = ?2, updated_at = ?2 WHERE id = ?3")
            .setParameter(1, createdBy).setParameter(2, createdAt).setParameter(3, accountId).executeUpdate());
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

    private List<String> authorshipOfEveryAccountBut(int accountId) {
        List<String> rows = new ArrayList<>(fixtures.authorshipOfEveryAccount());
        rows.removeIf(row -> row.startsWith(accountId + "|"));
        return rows;
    }

    private static Response loginResponse(String username) {
        return given().contentType(ContentType.JSON)
            .body("{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}")
        .when().post("/auth/login")
        .then().statusCode(200).extract().response();
    }

    private static Response get(int accountId) {
        return given().header("Authorization", "Bearer " + fabricateAccessToken("ztest-admin", "admin"))
            .when().get("/users/" + accountId);
    }

    private static Response deactivate(String token, Object id) {
        return given().header("Authorization", "Bearer " + token).when().post("/users/" + id + "/deactivate");
    }
}
