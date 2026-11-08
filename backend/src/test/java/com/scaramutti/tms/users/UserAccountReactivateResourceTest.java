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
 * POST /users/{id}/reactivate. Quien escribe y la cuenta son siempre cuentas de prueba
 * ({@code ztestuser}) sobre trabajadores de prueba ({@code ZTEST}): la base tiene un solo {@code admin}
 * sembrado y sus cuentas se llaman como su cargo.
 */
@QuarkusTest
class UserAccountReactivateResourceTest {

    private static final String PASSWORD = "Sales1234";
    private static final String NO_LOGIN_ROLE = "ztestrolersr";

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
    void reactivate_forEachRoleThatAdministersAccounts_returns200(String role) {
        String token = actorToken(role, "rsr" + role.substring(0, 3));
        int accountId = seedAccount("ZTESTRSR01", "warehouse_keeper", "ztestuserrctadest", false);

        reactivate(token, accountId).then().statusCode(200).body("isActive", equalTo(true));
    }

    @ParameterizedTest
    @ValueSource(strings = {"sales", "dispatcher", "warehouse_keeper"})
    void reactivate_forTheOtherRoles_returns403_COM003_withoutCacheHeaders_andChangesNothing(String role) {
        int accountId = seedAccount("ZTESTRSR02", "warehouse_keeper", "ztestuserrctaotro", false);

        Response response = reactivate(tokenOf(fixtures.seedActor("general_manager", "rsrotro"), role), accountId);

        assertEquals(403, response.statusCode());
        assertEquals("COM-003", response.jsonPath().getString("code"));
        assertNotSent(response, "Cache-Control", "Vary", "Pragma");
        assertUnchanged(accountId, false);
    }

    /** El cargo se decide antes que el id: el mismo 403 con un id inexistente o que no es un entero. */
    @ParameterizedTest
    @ValueSource(strings = {"999999", "abc", "2147483648"})
    void reactivate_withRoleWithoutAccess_andAnyId_returns403NotA404(String id) {
        given().header("Authorization", "Bearer " + fabricateAccessToken("ztest-sales", "sales"))
        .when().post("/users/" + id + "/reactivate")
        .then().statusCode(403).body("code", equalTo("COM-003"));
    }

    @Test
    void reactivate_withoutToken_returns401() {
        given().when().post("/users/1/reactivate").then().statusCode(401);
    }

    // ---------- el cambio ----------------------------------------------------------

    /**
     * Una sola fila de rastro, entera, firmada por quien reactiva; la cuenta queda firmada por el, con
     * una marca nueva y la version de sus sesiones subida, y ni quien la creo ni la contrasena cambian.
     */
    @Test
    void reactivate_anInactiveAccount_returns200Active_withOneTrailRow_signsIt_andRaisesTheSessionVersion() {
        ActorSeed actor = fixtures.seedActor("general_manager", "rsrfirma");
        ActorSeed creator = fixtures.seedActor("operations_manager", "rsrcrea");
        int accountId = seedAccount("ZTESTRSR03", "finance_manager", "ztestuserrctafila", false);
        OffsetDateTime createdAt = OffsetDateTime.of(2026, 1, 2, 3, 4, 5, 0, ZoneOffset.UTC);
        setAccountAuthorship(accountId, creator.userId(), createdAt);
        setSessionVersion(accountId, 4);
        String hashBefore = passwordHashOf(accountId);
        OffsetDateTime before = OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(1);

        Response response = reactivate(tokenOf(actor, "general_manager"), accountId);

        assertEquals(200, response.statusCode());
        Map<String, Object> body = response.jsonPath().getMap("");
        assertEquals(true, body.get("isActive"));
        assertEquals(get(accountId).jsonPath().getMap(""), body, "la respuesta es el detalle que trae la lectura");
        assertEquals(actor.userId(), response.jsonPath().getInt("updatedBy.id"));
        assertEquals(creator.userId(), response.jsonPath().getInt("createdBy.id"));

        List<UserAuditRow> rows = fixtures.userAuditRows(accountId);
        assertEquals(1, rows.size());
        UserAuditRow row = rows.get(0);
        assertEquals(new UserAuditRow("REACTIVATED", "isActive", "Cuenta", "false", "true", actor.userId(),
            row.loggedAt()), row);
        assertTrue(row.loggedAt().isAfter(before), "la fila es de este pedido");

        UserAuthorship authorship = fixtures.userAuthorshipOf(accountId);
        assertEquals(creator.userId(), authorship.createdBy(), "quien la creo no cambia");
        assertEquals(createdAt.toInstant(), authorship.createdAt().toInstant());
        assertEquals(actor.userId(), authorship.updatedBy());
        assertTrue(authorship.updatedAt().isAfter(before), "la marca de cambio se mueve");
        assertTrue(fixtures.userIsActive(accountId));
        assertEquals(5, sessionVersionOf(accountId));
        assertEquals(hashBefore, passwordHashOf(accountId), "la contrasena no cambia");
    }

    /** Ni el trabajador, ni su rastro, ni la firma ni la version de otra cuenta. */
    @Test
    void reactivate_touchesNeitherTheWorkerNorAnyOtherAccount() {
        String token = actorToken("admin", "rsrsolo");
        int workerId = fixtures.seedWorker("ZTESTRSR04", "Ana", "Ztsolo", null, "sales", true);
        int accountId = fixtures.seedUserFor(workerId, "ztestuserrctasolo", "sales", false);
        String workerBefore = workerRow(workerId);
        List<String> othersBefore = authorshipOfEveryAccountBut(accountId);
        List<String> versionsBefore = sessionVersionOfEveryAccountBut(accountId);

        reactivate(token, accountId).then().statusCode(200);

        assertEquals(workerBefore, workerRow(workerId), "el trabajador sigue igual y sin firma nueva");
        assertEquals(0, workerTrailRows(workerId));
        assertEquals(othersBefore, authorshipOfEveryAccountBut(accountId));
        assertEquals(versionsBefore, sessionVersionOfEveryAccountBut(accountId));
    }

    /** Repetir no escribe nada, tampoco la version: el refresh vigente de la cuenta sigue renovando. */
    @Test
    void reactivate_anActiveAccount_returns200_withoutTrailRowNorSignature_andItsRefreshStillRenews() {
        String token = actorToken("admin", "rsrrep");
        int accountId = seedAccount("ZTESTRSR05", "sales", "ztestuserrctarep", true);
        fixtures.copyPasswordHashFrom(accountId, "sales");
        String refreshToken = loginResponse("ztestuserrctarep").jsonPath().getString("refreshToken");
        UserAuthorship before = fixtures.userAuthorshipOf(accountId);

        reactivate(token, accountId).then().statusCode(200).body("isActive", equalTo(true));

        assertEquals(List.of(), fixtures.userAuditRows(accountId));
        assertEquals(before, fixtures.userAuthorshipOf(accountId));
        assertEquals(0, sessionVersionOf(accountId));
        refresh(refreshToken).then().statusCode(200);
    }

    /** Las guardas corren antes del corte idempotente. */
    @Test
    void reactivate_anActiveAccountOutOfRank_returns403_USR008() {
        String token = actorToken("finance_manager", "rsrrepr");
        int accountId = seedAccount("ZTESTRSR06", "sales", "ztestuserrctarepr", true);

        reactivate(token, accountId).then().statusCode(403).body("code", equalTo("USR-008"));
        assertUnchanged(accountId, true);
    }

    // ---------- el rango y la propia cuenta ------------------------------------------

    @Test
    void reactivate_anAdminOverAnotherAdmin_returns200() {
        String token = actorToken("admin", "rsradmuno");
        int otherAdmin = fixtures.seedActor("admin", "rsradmdos").userId();
        fixtures.setUserActive(otherAdmin, false);

        reactivate(token, otherAdmin).then().statusCode(200).body("isActive", equalTo(true));
    }

    @ParameterizedTest(name = "{0} sobre {1} -> {2}")
    @CsvSource({
        "general_manager, finance_manager, 200",
        "general_manager, operations_manager, 403",
        "finance_manager, warehouse_keeper, 200",
        "finance_manager, sales, 403",
        "finance_manager, admin, 403",
    })
    void reactivate_onlyOverAccountsOfAStrictlyLowerLevel(String actorRole, String accountRole, int status) {
        String token = actorToken(actorRole, "rsrrango");
        int accountId = seedAccount("ZTESTRSR07", accountRole, "ztestuserrctacuenta", false);

        Response response = reactivate(token, accountId);

        assertEquals(status, response.statusCode());
        if (status == 403) {
            assertEquals("USR-008", response.jsonPath().getString("code"));
            assertEquals("No puedes gestionar cuentas de ese cargo", response.jsonPath().getString("detail"));
            assertFalse(response.asString().contains(accountRole), "el detalle no nombra el cargo");
            assertUnchanged(accountId, false);
        } else {
            assertTrue(fixtures.userIsActive(accountId));
        }
    }

    /**
     * Reactivar no tiene regla de la propia cuenta: sobre la propia activa, {@code admin} repite sin
     * cambio y otro cargo cae en el rango, porque su cargo no es menor que el suyo.
     */
    @ParameterizedTest(name = "{0} sobre su propia cuenta activa -> {1}")
    @CsvSource({
        "admin, 200",
        "general_manager, 403",
    })
    void reactivate_theOwnActiveAccount_isTheIdempotentCutForAdmin_andOutOfRankForAGeneralManager(String role, int status) {
        ActorSeed actor = fixtures.seedActor(role, "rsrprop");

        Response response = reactivate(tokenOf(actor, role), actor.userId());

        assertEquals(status, response.statusCode());
        if (status == 403) {
            assertEquals("USR-008", response.jsonPath().getString("code"));
        }
        assertUnchanged(actor.userId(), true);
        assertEquals(0, sessionVersionOf(actor.userId()));
    }

    // ---------- el trabajador y su cargo ------------------------------------------------

    @Test
    void reactivate_withTheWorkerInactive_returns409_USR005_andChangesNothing() {
        String token = actorToken("admin", "rsrtrab");
        int workerId = fixtures.seedWorker("ZTESTRSR08", "Cuenta", "Ztreactivar", null, "sales", false);
        int accountId = fixtures.seedUserFor(workerId, "ztestuserrctatrab", "sales", false);

        Response response = reactivate(token, accountId);

        assertEquals(409, response.statusCode());
        assertEquals("application/problem+json", response.contentType());
        assertEquals("USR-005", response.jsonPath().getString("code"));
        assertEquals("urn:tms:error:usr-005", response.jsonPath().getString("type"));
        assertEquals("Conflict", response.jsonPath().getString("title"));
        assertEquals("El trabajador está dado de baja", response.jsonPath().getString("detail"));
        assertNotSent(response, "Cache-Control", "Vary", "Pragma");
        assertUnchanged(accountId, false);
        assertEquals(0, sessionVersionOf(accountId));
    }

    @Test
    void reactivate_withTheWorkerOnARoleThatDoesNotLogIn_returns400_USR006_andChangesNothing() {
        String token = actorToken("admin", "rsrcargo");
        int accountId = seedAccountOnANoLoginRole("ZTESTRSR09", "ztestuserrctacargo", true, false);

        Response response = reactivate(token, accountId);

        assertEquals(400, response.statusCode());
        assertEquals("application/problem+json", response.contentType());
        assertEquals("USR-006", response.jsonPath().getString("code"));
        assertEquals("urn:tms:error:usr-006", response.jsonPath().getString("type"));
        assertEquals("Bad Request", response.jsonPath().getString("title"));
        assertEquals("El cargo del trabajador no inicia sesión", response.jsonPath().getString("detail"));
        assertNotSent(response, "Cache-Control", "Vary", "Pragma");
        assertUnchanged(accountId, false);
        assertEquals(0, sessionVersionOf(accountId));
    }

    @Test
    void reactivate_withTheWorkerInactiveAndOnARoleThatDoesNotLogIn_returns409_USR005() {
        String token = actorToken("admin", "rsrambas");
        int accountId = seedAccountOnANoLoginRole("ZTESTRSR10", "ztestuserrctaambas", false, false);

        reactivate(token, accountId).then().statusCode(409).body("code", equalTo("USR-005"));
    }

    /**
     * Las dos reglas corren antes del corte idempotente: una cuenta que quedo activa sobre un trabajador
     * de baja o sobre un cargo sin sesion responde el error, no un 200 que la daria por utilizable.
     */
    @ParameterizedTest
    @ValueSource(strings = {"trabajador", "cargo"})
    void reactivate_anActiveAccountThatCannotLogIn_returnsTheRuleNotTheIdempotent200(String what) {
        String token = actorToken("admin", "rsrcorte");
        int accountId = "trabajador".equals(what)
            ? fixtures.seedUserFor(fixtures.seedWorker("ZTESTRSR11", "Cuenta", "Ztcorte", null, "sales", false),
                "ztestuserrctacorte", "sales", true)
            : seedAccountOnANoLoginRole("ZTESTRSR11", "ztestuserrctacorte", true, true);

        Response response = reactivate(token, accountId);

        assertEquals("trabajador".equals(what) ? 409 : 400, response.statusCode());
        assertEquals("trabajador".equals(what) ? "USR-005" : "USR-006", response.jsonPath().getString("code"));
        assertUnchanged(accountId, true);
    }

    /** El rango va antes que las reglas: fuera de rango y con el trabajador de baja, el 403. */
    @Test
    void reactivate_outOfRank_withTheWorkerInactive_returns403_USR008() {
        String token = actorToken("finance_manager", "rsrrango2");
        int workerId = fixtures.seedWorker("ZTESTRSR12", "Cuenta", "Ztreactivar", null, "sales", false);
        int accountId = fixtures.seedUserFor(workerId, "ztestuserrctarango", "sales", false);

        reactivate(token, accountId).then().statusCode(403).body("code", equalTo("USR-008"));
        assertUnchanged(accountId, false);
    }

    /** El rango va antes que el cargo sin sesion: uno de nivel dos queda fuera del alcance de finanzas. */
    @Test
    void reactivate_outOfRank_onARoleThatDoesNotLogIn_returns403_USR008() {
        String token = actorToken("finance_manager", "rsrrango3");
        fixtures.seedRole("ztestrolersrdos", "ZTEST cargo sin sesion de nivel dos", 2, false, "NONE", true);
        int workerId = fixtures.seedWorker("ZTESTRSR18", "Cuenta", "Ztsinsesion", null, "sales", true);
        int accountId = fixtures.seedUserFor(workerId, "ztestuserrctarango3", "sales", false);
        fixtures.setWorkerRole(workerId, "ztestrolersrdos");

        reactivate(token, accountId).then().statusCode(403).body("code", equalTo("USR-008"));
        assertUnchanged(accountId, false);
    }

    /**
     * La baja desde Trabajadores apaga la cuenta y reactivar al trabajador no la enciende: hasta que el
     * trabajador vuelve, la cuenta no se reactiva.
     */
    @Test
    void reactivate_afterTheWorkerWasDeactivatedFromWorkers_returns409_andAfterItsReactivation_returns200() {
        String token = actorToken("admin", "rsrcasc");
        int workerId = fixtures.seedWorker("ZTESTRSR13", "Cuenta", "Ztcascada", null, "sales", true);
        int accountId = fixtures.seedUserFor(workerId, "ztestuserrctacasc", "sales", true);

        workerStatus(token, workerId, "deactivate").then().statusCode(200);
        assertFalse(fixtures.userIsActive(accountId), "la baja del trabajador apaga la cuenta");
        reactivate(token, accountId).then().statusCode(409).body("code", equalTo("USR-005"));

        workerStatus(token, workerId, "reactivate").then().statusCode(200);
        assertFalse(fixtures.userIsActive(accountId), "reactivar al trabajador no enciende la cuenta");
        reactivate(token, accountId).then().statusCode(200).body("isActive", equalTo(true));
    }

    // ---------- quien escribe, leido de la base --------------------------------------

    /** El token sigue vigente, pero quien lo lleva ya no escribe: la cuenta apagada, el trabajador de baja u otro cargo. */
    @ParameterizedTest
    @ValueSource(strings = {"cuenta", "trabajador", "cargo"})
    void reactivate_byAnActorNoLongerEnabled_withItsTokenStillValid_returns403_COM003(String what) {
        ActorSeed actor = fixtures.seedActor("cargo".equals(what) ? "sales" : "general_manager", "rsrbaja");
        if ("cuenta".equals(what)) fixtures.setUserActive(actor.userId(), false);
        if ("trabajador".equals(what)) fixtures.setWorkerActive(actor.workerId(), false);
        int accountId = seedAccount("ZTESTRSR14", "warehouse_keeper", "ztestuserrctabaja", false);

        reactivate(tokenOf(actor, "general_manager"), accountId).then().statusCode(403).body("code", equalTo("COM-003"));
        assertUnchanged(accountId, false);
    }

    /** La propia cuenta apagada no se reactiva: con ella apagada, la sesion ya no escribe. */
    @ParameterizedTest
    @ValueSource(strings = {"admin", "general_manager"})
    void reactivate_theOwnInactiveAccount_returns403_COM003(String role) {
        ActorSeed actor = fixtures.seedActor(role, "rsrpropia");
        fixtures.setUserActive(actor.userId(), false);

        reactivate(tokenOf(actor, role), actor.userId()).then().statusCode(403).body("code", equalTo("COM-003"));
        assertUnchanged(actor.userId(), false);
    }

    /** El 404 va antes que quien escribe: un id que no existe da 404 tambien a quien ya no escribe. */
    @Test
    void reactivate_anIdThatDoesNotExist_byAnActorNoLongerEnabled_returns404_USR001() {
        ActorSeed actor = fixtures.seedActor("general_manager", "rsrbaja4");
        fixtures.setWorkerActive(actor.workerId(), false);

        reactivate(tokenOf(actor, "general_manager"), 999999).then().statusCode(404).body("code", equalTo("USR-001"));
    }

    // ---------- 404 ------------------------------------------------------------------

    @Test
    void reactivate_anIdThatDoesNotExist_returns404_USR001_withoutCacheHeaders() {
        Response response = reactivate(actorToken("admin", "rsrno"), 999999);

        assertEquals(404, response.statusCode());
        assertEquals("application/problem+json", response.contentType());
        assertEquals("USR-001", response.jsonPath().getString("code"));
        assertNotSent(response, "Cache-Control", "Vary", "Pragma");
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "2147483648"})
    void reactivate_anIdThatIsNotAnInt32_returns404WithoutBody(String id) {
        given().header("Authorization", "Bearer " + actorToken("admin", "rsrnoint"))
        .when().post("/users/" + id + "/reactivate")
        .then().statusCode(404).body(equalTo(""));
    }

    // ---------- cabeceras y cuerpo ---------------------------------------------------

    @Test
    void reactivate_sendsNoStoreAndVaryOnce() {
        int accountId = seedAccount("ZTESTRSR15", "sales", "ztestuserrctacache", false);

        Response response = reactivate(actorToken("admin", "rsrcache"), accountId);

        assertEquals(200, response.statusCode());
        assertSentOnce(response, "Cache-Control", "no-store");
        assertSentOnce(response, "Vary", "Authorization");
        assertNotSent(response, "Pragma");
    }

    /** Sin cuerpo: uno que pide dejarla inactiva, otro tipo de contenido o ninguno se ignoran. */
    @ParameterizedTest
    @CsvSource(value = {
        "application/json|{\"isActive\":false}",
        "application/json|{roto",
        "text/plain|inactiva",
        "application/x-www-form-urlencoded|isActive=false",
        "-|-",
    }, delimiter = '|')
    void reactivate_ignoresAnyBodyAndContentType(String contentType, String body) {
        String token = actorToken("admin", "rsrcuerpo");
        int accountId = seedAccount("ZTESTRSR16", "sales", "ztestuserrctacuerpo", false);

        var request = given().header("Authorization", "Bearer " + token);
        if (!"-".equals(contentType)) {
            request = request.contentType(contentType).body(body);
        }
        request.when().post("/users/" + accountId + "/reactivate").then().statusCode(200).body("isActive", equalTo(true));
    }

    // ---------- las sesiones ---------------------------------------------------------

    /**
     * El refresh guardado antes de la baja ya no renueva despues de reactivar, y uno de un ingreso
     * posterior si. El token de acceso de antes de la baja vuelve a leer la sesion.
     */
    @Test
    void afterReactivating_aRefreshFromBeforeTheDeactivationNoLongerRenews_butANewLoginDoes() {
        String adminToken = actorToken("admin", "rsrsesion");
        int accountId = seedAccount("ZTESTRSR17", "finance_manager", "ztestuserrctasesion", true);
        fixtures.copyPasswordHashFrom(accountId, "sales");
        Response session = loginResponse("ztestuserrctasesion");
        String oldAccessToken = session.jsonPath().getString("token");
        String oldRefreshToken = session.jsonPath().getString("refreshToken");

        deactivate(adminToken, accountId).then().statusCode(200);
        refresh(oldRefreshToken).then().statusCode(401).body("code", equalTo("AUTH-002"));
        given().header("Authorization", "Bearer " + oldAccessToken)
        .when().get("/auth/me").then().statusCode(401).body("code", equalTo("AUTH-002"));
        reactivate(adminToken, accountId).then().statusCode(200);

        refresh(oldRefreshToken).then().statusCode(401).body("code", equalTo("AUTH-003"));
        String newRefreshToken = loginResponse("ztestuserrctasesion").jsonPath().getString("refreshToken");
        refresh(newRefreshToken).then().statusCode(200);
        given().header("Authorization", "Bearer " + oldAccessToken)
        .when().get("/auth/me").then().statusCode(200);
    }

    // ---------- fixtures ----------------------------------------------------------

    private int seedAccount(String document, String roleName, String username, boolean accountIsActive) {
        int workerId = fixtures.seedWorker(document, "Cuenta", "Ztreactivar", null, roleName, true);
        return fixtures.seedUserFor(workerId, username, roleName, accountIsActive);
    }

    private int seedAccountOnANoLoginRole(String document, String username, boolean workerIsActive,
            boolean accountIsActive) {
        fixtures.seedRole(NO_LOGIN_ROLE, "ZTEST cargo sin sesion", 1, false, "NONE", true);
        int workerId = fixtures.seedWorker(document, "Cuenta", "Ztsinsesion", null, "sales", workerIsActive);
        int accountId = fixtures.seedUserFor(workerId, username, "sales", accountIsActive);
        fixtures.setWorkerRole(workerId, NO_LOGIN_ROLE);
        return accountId;
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

    private List<String> authorshipOfEveryAccountBut(int accountId) {
        List<String> rows = new ArrayList<>(fixtures.authorshipOfEveryAccount());
        rows.removeIf(row -> row.startsWith(accountId + "|"));
        return rows;
    }

    @SuppressWarnings("unchecked")
    private List<String> sessionVersionOfEveryAccountBut(int accountId) {
        return entityManager.createNativeQuery(
                "SELECT id || '|' || session_version FROM public.users WHERE id <> ?1 ORDER BY id")
            .setParameter(1, accountId).getResultList();
    }

    private static Response loginResponse(String username) {
        return given().contentType(ContentType.JSON)
            .body("{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}")
        .when().post("/auth/login")
        .then().statusCode(200).extract().response();
    }

    private static Response refresh(String refreshToken) {
        return given().contentType(ContentType.JSON).body("{\"refreshToken\":\"" + refreshToken + "\"}")
            .when().post("/auth/refresh");
    }

    private static Response get(int accountId) {
        return given().header("Authorization", "Bearer " + fabricateAccessToken("ztest-admin", "admin"))
            .when().get("/users/" + accountId);
    }

    private static Response workerStatus(String token, int workerId, String action) {
        return given().header("Authorization", "Bearer " + token).when().post("/workers/" + workerId + "/" + action);
    }

    private static Response deactivate(String token, Object id) {
        return given().header("Authorization", "Bearer " + token).when().post("/users/" + id + "/deactivate");
    }

    private static Response reactivate(String token, Object id) {
        return given().header("Authorization", "Bearer " + token).when().post("/users/" + id + "/reactivate");
    }
}
