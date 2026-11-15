package com.scaramutti.tms.users;

import com.scaramutti.tms.support.RoutePolicyTrickyUrls;
import com.scaramutti.tms.support.WarehouseTestData;
import com.scaramutti.tms.support.WarehouseTestData.ActorSeed;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.scaramutti.tms.support.ResponseHeaders.assertNotSent;
import static com.scaramutti.tms.support.ResponseHeaders.assertSentOnce;
import static com.scaramutti.tms.support.TestAuth.fabricateAccessToken;
import static com.scaramutti.tms.support.TestAuth.fabricateTokenForUser;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * La lista trae tambien a los elegibles que no son del test: lo que se compara con lo sembrado se filtra por
 * un nombre o un id.
 */
@QuarkusTest
class UserAccountCandidatesResourceTest {

    @Inject WarehouseTestData fixtures;

    @BeforeEach
    @AfterEach
    void cleanupFixtures() {
        fixtures.deleteTestWorkerDependents();
        QuarkusTransaction.requiringNew().run(() -> fixtures.deleteTestWorkers());
    }

    // ---------- roles ------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"admin", "general_manager", "operations_manager", "finance_manager"})
    void candidates_forEachRoleThatAdministersAccounts_returns200(String role) {
        int workerId = seedCandidate("ZTESTCAN01", "Testuser Candrol", "warehouse_keeper");

        candidates(actorToken(role, "can" + role.substring(0, 3)), "").then().statusCode(200)
            .body("workerId", hasItem(workerId));
    }

    @ParameterizedTest
    @ValueSource(strings = {"sales", "dispatcher", "warehouse_keeper"})
    void candidates_forTheOtherRoles_returns403_COM003_withoutCacheHeaders(String role) {
        // En la base es gerente: el 403 sale del cargo del token y no de releer a quien pregunta.
        String token = tokenOf(fixtures.seedActor("general_manager", "canotro"), role);

        Response response = candidates(token, "");
        Response head = given().header("Authorization", "Bearer " + token).when().head("/user-account-candidates");

        assertEquals(403, response.statusCode());
        assertEquals("COM-003", response.jsonPath().getString("code"));
        assertNotSent(response, "Cache-Control", "Vary", "Pragma");
        assertEquals(403, head.statusCode());
        assertNotSent(head, "Cache-Control", "Vary", "Pragma");
    }

    @Test
    void candidates_withoutToken_returns401() {
        given().when().get("/user-account-candidates").then().statusCode(401);
    }

    @Test
    void candidates_withTrickyUrls_withoutToken_returns401() {
        RoutePolicyTrickyUrls.assertAllReturn401WithoutToken("/user-account-candidates");
    }

    @Test
    void candidates_returns200_withNoStoreAndVaryOnce_andWithoutPragma() {
        Response response = candidates(actorToken("admin", "cancache"), "");

        assertEquals(200, response.statusCode());
        assertSentOnce(response, "Cache-Control", "no-store");
        assertSentOnce(response, "Vary", "Authorization");
        assertNotSent(response, "Pragma");
    }

    // ---------- quien pregunta, releido ---------------------------------------------

    @ParameterizedTest
    @CsvSource({"cuenta,?q=Candninguno", "trabajador,?q=Candninguno",
        "cargo,?q=Candninguno", "cuenta,", "cuenta,?workerId=2147483647",
        "cuenta,?workerId=2147483647&q=Candninguno"})
    void candidates_byAnActorNoLongerEnabled_returns403_COM003(String what, String query) {
        ActorSeed actor = disabledActor(what, "candes" + what.substring(0, 3));

        // Las formas filtradas, sin filas: el 403 no puede depender de que haya alguien a quien filtrar.
        Response response = candidates(tokenOf(actor, "general_manager"), query == null ? "" : query);

        assertEquals(403, response.statusCode());
        assertEquals("COM-003", response.jsonPath().getString("code"));
        assertNotSent(response, "Cache-Control", "Vary", "Pragma");
    }

    @Test
    void candidates_withAShortQuery_andAnActorNoLongerEnabled_returns400First() {
        ActorSeed actor = disabledActor("cuenta", "candescorto");

        candidates(tokenOf(actor, "general_manager"), "?q=ab").then().statusCode(400).body("code", equalTo("COM-001"));
    }

    @Test
    void candidates_withAShortQuery_andAWorkerIdThatIsNotAnInteger_returns404First() {
        Response response = candidates(actorToken("admin", "canorden404"), "?q=ab&workerId=abc");

        assertEquals(404, response.statusCode());
        assertEquals("", response.asString());
    }

    @Test
    void candidates_withAWorkerIdThatIsNotAnInteger_andAnActorNoLongerEnabled_returns404First() {
        ActorSeed actor = disabledActor("cuenta", "candes404");

        Response response = candidates(tokenOf(actor, "general_manager"), "?workerId=abc");

        assertEquals(404, response.statusCode());
        assertEquals("", response.asString());
    }

    @Test
    void candidates_withRoleWithoutAccess_andAShortQuery_returns403NotA400() {
        candidates(fabricateAccessToken("ztest-sales", "sales"), "?q=ab").then().statusCode(403)
            .body("code", equalTo("COM-003"));
    }

    // ---------- quienes salen ---------------------------------------------------------

    /** Cada exclusion con un elegible al lado: si la lista viniera vacia, el caso no probaria nada. */
    @ParameterizedTest
    @ValueSource(strings = {"de baja", "con cuenta activa", "con cuenta inactiva", "driver", "escort", "assistant",
        "operator"})
    void candidates_leaveOutAWorker(String why) {
        int eligibleId = seedCandidate("ZTESTCAN02", "Testuser Candsi", "warehouse_keeper");
        int excludedId = switch (why) {
            case "de baja" -> fixtures.seedWorker("ZTESTCAN03", "Zeta", "Testuser Candno", null, "warehouse_keeper",
                false);
            case "con cuenta activa", "con cuenta inactiva" -> {
                int workerId = seedCandidate("ZTESTCAN03", "Testuser Candno", "warehouse_keeper");
                fixtures.seedUserFor(workerId, "ztestusercandcuenta", "warehouse_keeper", why.endsWith(" activa"));
                yield workerId;
            }
            default -> seedCandidate("ZTESTCAN03", "Testuser Candno", why);
        };

        String token = actorToken("admin", "canfuera");

        assertEquals(List.of(eligibleId), idsOf(candidates(token, "?q=Candsi")));
        assertEquals(List.of(), idsOf(candidates(token, "?workerId=" + excludedId)), why);
        assertEquals(List.of(), idsOf(candidates(token, "?q=Candno")), why);
        assertEquals(List.of(), idsOf(candidates(token, "?workerId=" + excludedId + "&q=Candno")), why);
        assertFalse(idsOf(candidates(token, "")).contains(excludedId), why);
    }

    /** Ventas tiene el mismo nivel que finanzas: el rango es estrictamente menor. */
    @Test
    void candidates_asFinanceManager_bringAWarehouseKeeper_butNotASalesWorker() {
        int keeperId = seedCandidate("ZTESTCAN04", "Testuser Candalm", "warehouse_keeper");
        int salesId = seedCandidate("ZTESTCAN05", "Testuser Candven", "sales");

        String token = actorToken("finance_manager", "canfin");

        List<Integer> searched = idsOf(candidates(token, "?q=Testuser"));
        List<Integer> unfiltered = idsOf(candidates(token, ""));

        assertTrue(searched.contains(keeperId));
        assertFalse(searched.contains(salesId));
        assertTrue(unfiltered.contains(keeperId));
        assertFalse(unfiltered.contains(salesId));
        assertEquals(List.of(keeperId), idsOf(candidates(token, "?workerId=" + keeperId)));
        assertEquals(List.of(), idsOf(candidates(token, "?workerId=" + salesId)));
        assertEquals(List.of(), idsOf(candidates(token, "?workerId=" + salesId + "&q=Candven")));
    }

    @Test
    void candidates_asGeneralManager_bringAFinanceManager_butNotAnotherManager() {
        int financeId = seedCandidate("ZTESTCAN06", "Testuser Candfin", "finance_manager");
        int managerId = seedCandidate("ZTESTCAN07", "Testuser Candope", "operations_manager");

        List<Integer> ids = idsOf(candidates(actorToken("general_manager", "canger"), "?q=Testuser"));

        assertTrue(ids.contains(financeId));
        assertFalse(ids.contains(managerId));
    }

    @Test
    void candidates_asAdmin_bringEveryLevel_includingAnotherAdmin() {
        int adminId = seedCandidate("ZTESTCAN08", "Testuser Candadm", "admin");
        int managerId = seedCandidate("ZTESTCAN09", "Testuser Candger", "general_manager");
        int salesId = seedCandidate("ZTESTCAN10", "Testuser Candvta", "sales");

        String token = actorToken("admin", "canadmin");
        List<Integer> ids = idsOf(candidates(token, "?q=Testuser"));

        assertTrue(ids.containsAll(List.of(adminId, managerId, salesId)));
        assertTrue(idsOf(candidates(token, "")).containsAll(List.of(adminId, managerId, salesId)));
        assertEquals(List.of(adminId), idsOf(candidates(token, "?workerId=" + adminId)));
        assertEquals(List.of(adminId), idsOf(candidates(token, "?workerId=" + adminId + "&q=Candadm")));
    }

    /** El nivel sale de la base: el cargo del token no lo sube ni lo baja. */
    @Test
    void candidates_decideTheRankByTheActorInTheDatabase_notByTheToken() {
        int salesId = seedCandidate("ZTESTCAN11", "Testuser Candtok", "sales");
        ActorSeed finance = fixtures.seedActor("finance_manager", "cantokfin");
        ActorSeed admin = fixtures.seedActor("admin", "cantokadm");

        assertFalse(idsOf(candidates(tokenOf(finance, "admin"), "?q=Candtok")).contains(salesId));
        assertTrue(idsOf(candidates(tokenOf(admin, "finance_manager"), "?q=Candtok")).contains(salesId));
    }

    // ---------- la busqueda -----------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"a", "ab"})
    void candidates_withAQueryShorterThanThree_returns400_COM001(String q) {
        candidates(actorToken("admin", "cancorto"), "?q=" + q).then().statusCode(400)
            .contentType("application/problem+json").body("code", equalTo("COM-001"));
    }

    @Test
    void candidates_withAQueryOfThreeCharacters_findsTheWorker() {
        int workerId = fixtures.seedWorker("ZTESTCAN36", "Zeta", "Testuser Xqw", null, "warehouse_keeper", true);

        assertEquals(List.of(workerId), idsOf(candidates(actorToken("admin", "cantres"), "?q=xqw")));
    }

    @Test
    void candidates_withAQueryOf200Characters_returns200() {
        candidates(actorToken("admin", "candos"), "?q=" + "a".repeat(200)).then().statusCode(200);
    }

    @Test
    void candidates_withAQueryLongerThan200_returns400_COM001() {
        candidates(actorToken("admin", "canlargo"), "?q=" + "a".repeat(201)).then().statusCode(400)
            .body("code", equalTo("COM-001"));
    }

    @Test
    void candidates_withAnEmptyQuery_bringTheSameAsWithoutIt() {
        seedCandidate("ZTESTCAN12", "Testuser Candvacio", "warehouse_keeper");
        String token = actorToken("admin", "canvacio");

        List<Integer> without = idsOf(candidates(token, ""));

        assertEquals(without, idsOf(candidates(token, "?q=")));
        assertFalse(without.isEmpty());
    }

    @Test
    void candidates_withAQuery_matchEachWordInTheNameOrASurname() {
        int workerId = fixtures.seedWorker("ZTESTCAN13", "Zeta", "Testuser Candbusca", "Candmaterno",
            "warehouse_keeper", true);
        String token = actorToken("admin", "canbusca");

        assertEquals(List.of(workerId), idsOf(candidates(token, "?q=zeta%20CANDBUSCA")));
        assertEquals(List.of(workerId), idsOf(candidates(token, "?q=candmaterno")));
        assertEquals(List.of(), idsOf(candidates(token, "?q=candbusca%20candotro")));
        assertEquals(List.of(), idsOf(candidates(token, "?q=ZTESTCAN13")));
    }

    @Test
    void candidates_byWorkerId_bringOnlyThatWorker() {
        int workerId = seedCandidate("ZTESTCAN14", "Testuser Candid", "warehouse_keeper");
        seedCandidate("ZTESTCAN15", "Testuser Candotro", "warehouse_keeper");

        assertEquals(List.of(workerId), idsOf(candidates(actorToken("admin", "canid"), "?workerId=" + workerId)));
    }

    @Test
    void candidates_byWorkerIdOfAWorkerNotEligible_bringNothing() {
        int workerId = seedCandidate("ZTESTCAN16", "Testuser Candtiene", "warehouse_keeper");
        fixtures.seedUserFor(workerId, "ztestusercandtiene", "warehouse_keeper", false);

        assertEquals(List.of(), idsOf(candidates(actorToken("admin", "canidno"), "?workerId=" + workerId)));
    }

    @Test
    void candidates_byWorkerIdAndAQuery_applyBoth() {
        int workerId = seedCandidate("ZTESTCAN17", "Testuser Candambos", "warehouse_keeper");
        seedCandidate("ZTESTCAN39", "Testuser Candambos", "warehouse_keeper");
        String token = actorToken("admin", "canambos");

        assertEquals(List.of(workerId), idsOf(candidates(token, "?workerId=" + workerId + "&q=candambos")));
        assertEquals(List.of(), idsOf(candidates(token, "?workerId=" + workerId + "&q=candnada")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "2147483648", "1.5"})
    void candidates_withAWorkerIdThatIsNotAnInteger_returns404WithoutBody(String workerId) {
        Response response = candidates(actorToken("admin", "canidmal"), "?workerId=" + workerId);

        assertEquals(404, response.statusCode());
        assertEquals("", response.asString());
    }

    @Test
    void candidates_withAnEmptyWorkerId_bringTheSameAsWithoutIt() {
        seedCandidate("ZTESTCAN18", "Testuser Candidvacio", "warehouse_keeper");
        String token = actorToken("admin", "canidvacio");

        List<Integer> without = idsOf(candidates(token, ""));

        assertEquals(without, idsOf(candidates(token, "?workerId=")));
        assertFalse(without.isEmpty());
    }

    // ---------- cada fila y el orden ----------------------------------------------------

    @Test
    void candidates_eachRow_bringsExactlyTheWorkerItsNamePartsItsRoleAndTheProposal() {
        int workerId = fixtures.seedWorker("ZTESTCAN19", "Rosa", "Testuser Candfila", "Candmat", "warehouse_keeper",
            true);

        Map<String, Object> row = onlyRowOf(candidates(actorToken("admin", "canfila"), "?workerId=" + workerId));

        assertEquals(Set.of("workerId", "fullName", "firstName", "paternalLastName", "maternalLastName", "role",
            "proposedUsername"), row.keySet());
        assertEquals(workerId, row.get("workerId"));
        assertEquals("Rosa Testuser Candfila Candmat", row.get("fullName"));
        assertEquals("Rosa", row.get("firstName"));
        assertEquals("Testuser Candfila", row.get("paternalLastName"));
        assertEquals("Candmat", row.get("maternalLastName"));
        assertEquals("rtestusercandfila", row.get("proposedUsername"));
        Map<String, Object> catalogRole = given()
            .header("Authorization", "Bearer " + fabricateAccessToken("ztest-admin", "admin"))
            .when().get("/roles")
            .then().statusCode(200).extract().jsonPath().getMap("find { it.name == 'warehouse_keeper' }");
        assertEquals(catalogRole, row.get("role"));
    }

    @Test
    void candidates_aWorkerWithoutMaternal_bringsItNull_andTheFullNameWithoutIt() {
        int workerId = seedCandidate("ZTESTCAN20", "Testuser Candsolo", "warehouse_keeper");

        Map<String, Object> row = onlyRowOf(candidates(actorToken("admin", "cansolo"), "?workerId=" + workerId));

        assertTrue(row.containsKey("maternalLastName"));
        assertNull(row.get("maternalLastName"));
        assertEquals("Zeta Testuser Candsolo", row.get("fullName"));
    }

    /** Los de materno se siembran primero y en orden inverso: por id irian antes que el que no tiene. */
    @Test
    void candidates_comeByFirstNameThenSurnames_withTheOneWithoutMaternalFirst() {
        int withMaternalId = fixtures.seedWorker("ZTESTCAN46", "Zeta", "Testuser Candpadx", "Candmat",
            "warehouse_keeper", true);
        int withoutMaternalId = seedCandidate("ZTESTCAN47", "Testuser Candpadx", "warehouse_keeper");
        int otherFirstNameId = fixtures.seedWorker("ZTESTCAN48", "Ana", "Testuser Candpadz", null,
            "warehouse_keeper", true);
        int earlierMaternalId = fixtures.seedWorker("ZTESTCAN49", "Zeta", "Testuser Candpadx", "Candmaa",
            "warehouse_keeper", true);

        List<Integer> expected = List.of(otherFirstNameId, withoutMaternalId, earlierMaternalId, withMaternalId);
        String token = actorToken("admin", "canpadron");
        assertEquals(expected, idsOf(candidates(token, "?q=Candpad")));
        assertEquals(expected, idsOf(candidates(token, "")).stream().filter(expected::contains).toList());
    }

    @Test
    void candidates_comeInTheOrderOfTheRoster_andHomonymsById() {
        int secondId = seedCandidate("ZTESTCAN21", "Testuser Candordb", "warehouse_keeper");
        int firstId = seedCandidate("ZTESTCAN22", "Testuser Candorda", "warehouse_keeper");
        List<Integer> expected = new ArrayList<>(List.of(firstId, secondId));
        for (int twin = 0; twin < 6; twin++) {
            expected.add(seedCandidate("ZTESTCAN4" + twin, "Testuser Candordc", "warehouse_keeper"));
        }

        String token = actorToken("admin", "canorden");
        assertEquals(expected, idsOf(candidates(token, "?q=Candord")));
        assertEquals(expected, idsOf(candidates(token, "")).stream().filter(expected::contains).toList());
    }

    // ---------- la propuesta, contra el alta real ----------------------------------------

    @Test
    void candidates_proposeTheUsernameTheCreationThenGives() {
        String token = actorToken("admin", "canalta");
        int workerId = seedCandidate("ZTESTCAN25", "Testuser Candalta", "warehouse_keeper");

        String proposed = proposalOf(token, workerId);

        assertEquals(proposed, createdUsername(token, workerId));
    }

    @Test
    void candidates_withTheNameTaken_proposeTheSuffixTheCreationThenGives() {
        String token = actorToken("admin", "canhomo");
        int firstId = seedCandidate("ZTESTCAN26", "Testuser Candhomo", "warehouse_keeper");
        int secondId = seedCandidate("ZTESTCAN27", "Testuser Candhomo", "warehouse_keeper");
        String firstUsername = createdUsername(token, firstId);

        String proposed = proposalOf(token, secondId);

        assertNotEquals(firstUsername, proposed);
        assertEquals(proposed, createdUsername(token, secondId));
    }

    /** Con un paterno largo el tomado ya va recortado: la propuesta tiene que verlo para dar el sufijo. */
    @Test
    void candidates_aLongPaternalTaken_proposeTheSuffixTheCreationThenGives() {
        String token = actorToken("admin", "canlargo2");
        String paternal = "Testuser Candlargo" + "l".repeat(40);
        int firstId = seedCandidate("ZTESTCAN34", paternal, "warehouse_keeper");
        int secondId = seedCandidate("ZTESTCAN35", paternal, "warehouse_keeper");
        String firstUsername = createdUsername(token, firstId);

        String proposed = proposalOf(token, secondId);

        assertEquals(50, firstUsername.length());
        assertEquals(firstUsername.substring(0, 49) + "1", proposed);
        assertEquals(proposed, createdUsername(token, secondId));
    }

    @Test
    void candidates_withTheNameTakenByAnInactiveAccount_proposeTheSuffixTheCreationThenGives() {
        String token = actorToken("admin", "caninact");
        int holderId = seedCandidate("ZTESTCAN32", "Testuser Candapagada", "warehouse_keeper");
        fixtures.seedUserFor(holderId, "ztestusercandapagada", "warehouse_keeper", false);
        int workerId = seedCandidate("ZTESTCAN33", "Testuser Candapagada", "warehouse_keeper");

        String proposed = proposalOf(token, workerId);

        assertEquals("ztestusercandapagada1", proposed);
        assertEquals(List.of(proposed), candidates(token, "?q=Candapagada").jsonPath().getList("proposedUsername"));
        assertEquals(List.of(proposed), candidates(token, "?q=Candapagada&workerId=" + workerId).jsonPath()
            .getList("proposedUsername"));
        assertEquals(proposed, candidates(token, "").jsonPath().getString("find { it.workerId == " + workerId
            + " }.proposedUsername"));
        assertEquals(proposed, createdUsername(token, workerId));
    }

    @Test
    void candidates_withTheNameTakenByTheAccountOfADeactivatedWorker_proposeTheSuffixTheCreationThenGives() {
        String token = actorToken("admin", "canbajado");
        int holderId = seedCandidate("ZTESTCAN37", "Testuser Candbajado", "warehouse_keeper");
        fixtures.seedUserFor(holderId, "ztestusercandbajado", "warehouse_keeper", false);
        fixtures.setWorkerActive(holderId, false);
        int workerId = seedCandidate("ZTESTCAN38", "Testuser Candbajado", "warehouse_keeper");

        String proposed = proposalOf(token, workerId);

        assertEquals("ztestusercandbajado1", proposed);
        assertEquals(proposed, createdUsername(token, workerId));
    }

    /** El trabajador paso a un cargo sin sesion despues de tener cuenta: su nombre sigue tomado. */
    @Test
    void candidates_withTheNameTakenByTheAccountOfAWorkerWhoNoLongerLogsIn_proposeTheSuffixTheCreationThenGives() {
        String token = actorToken("admin", "cannolog");
        int holderId = seedCandidate("ZTESTCAN44", "Testuser Candnolog", "warehouse_keeper");
        fixtures.seedUserFor(holderId, "ztestusercandnolog", "warehouse_keeper", false);
        fixtures.setWorkerRole(holderId, "driver");
        int workerId = seedCandidate("ZTESTCAN45", "Testuser Candnolog", "warehouse_keeper");

        String proposed = proposalOf(token, workerId);

        assertEquals("ztestusercandnolog1", proposed);
        assertEquals(proposed, createdUsername(token, workerId));
    }

    @Test
    void candidates_twoHomonymsInTheSameList_showTheSameProposal() {
        seedCandidate("ZTESTCAN28", "Testuser Candgemelo", "warehouse_keeper");
        seedCandidate("ZTESTCAN29", "Testuser Candgemelo", "warehouse_keeper");

        Response response = candidates(actorToken("admin", "cangemelo"), "?q=Candgemelo");

        assertEquals(200, response.statusCode());
        assertEquals(List.of("ztestusercandgemelo", "ztestusercandgemelo"),
            response.jsonPath().getList("proposedUsername", String.class));
    }

    /** Un nombre corto entra entero, con el materno: el generador recibe las tres partes en su lugar. */
    @Test
    void candidates_aShortName_proposeWhatTheCreationGives() {
        String token = actorToken("admin", "cancorta");
        int workerId = fixtures.seedWorker("ZTESTCAN30", "Z", "B", "Testuser Candcorta", "warehouse_keeper", true);

        String proposed = proposalOf(token, workerId);

        assertEquals("zbtestusercandcorta", proposed);
        assertEquals(proposed, createdUsername(token, workerId));
    }

    @Test
    void candidates_aWorkerWithoutLatinLetters_proposeWhatTheCreationGives() {
        String token = actorToken("admin", "cancir");
        int workerId = fixtures.seedWorker("ZTESTCAN31", "Иван", "Петров", null, "warehouse_keeper", true);

        String proposed = proposalOf(token, workerId);

        assertEquals("usuario" + workerId, proposed);
        assertEquals(proposed, createdUsername(token, workerId));
    }

    // ---------- fixtures ----------------------------------------------------------

    private int seedCandidate(String document, String paternal, String roleName) {
        return fixtures.seedWorker(document, "Zeta", paternal, null, roleName, true);
    }

    private ActorSeed disabledActor(String what, String suffix) {
        ActorSeed actor = fixtures.seedActor("cargo".equals(what) ? "sales" : "general_manager", suffix);
        if ("cuenta".equals(what)) fixtures.setUserActive(actor.userId(), false);
        if ("trabajador".equals(what)) fixtures.setWorkerActive(actor.workerId(), false);
        return actor;
    }

    private String actorToken(String role, String suffix) {
        return tokenOf(fixtures.seedActor(role, suffix), role);
    }

    private static String tokenOf(ActorSeed actor, String tokenRole) {
        return fabricateTokenForUser(actor.userId(), "ztest-" + tokenRole, tokenRole);
    }

    private static Response candidates(String token, String query) {
        return given().header("Authorization", "Bearer " + token).urlEncodingEnabled(false)
            .when().get("/user-account-candidates" + query);
    }

    private static List<Integer> idsOf(Response response) {
        assertEquals(200, response.statusCode(), response.asString());
        return response.jsonPath().getList("workerId", Integer.class);
    }

    private static Map<String, Object> onlyRowOf(Response response) {
        assertEquals(200, response.statusCode(), response.asString());
        List<Map<String, Object>> rows = response.jsonPath().getList("$");
        assertEquals(1, rows.size());
        return rows.get(0);
    }

    private static String proposalOf(String token, int workerId) {
        return (String) onlyRowOf(candidates(token, "?workerId=" + workerId)).get("proposedUsername");
    }

    private static String createdUsername(String token, int workerId) {
        return given().header("Authorization", "Bearer " + token).contentType(ContentType.JSON)
            .body("{\"workerId\":" + workerId + "}")
        .when().post("/users")
        .then().statusCode(201).extract().path("account.username");
    }
}
