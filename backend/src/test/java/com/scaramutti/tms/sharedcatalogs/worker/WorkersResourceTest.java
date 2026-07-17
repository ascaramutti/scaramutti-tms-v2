package com.scaramutti.tms.sharedcatalogs.worker;

import com.scaramutti.tms.support.WarehouseTestData;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.scaramutti.tms.support.TestAuth.adminToken;
import static com.scaramutti.tms.support.TestAuth.fabricateAccessToken;
import static com.scaramutti.tms.support.TestAuth.login;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests de GET /workers. Hermetico: los workers de test se siembran con
 * {@code document_number} prefijo ZTEST y se limpian en {@code @AfterEach}. La tabla
 * {@code public.workers} es COMPARTIDA con v1 (tiene data real), asi que las aserciones
 * "sin filtro" son por PRESENCIA del registro sembrado (find/hasItem), nunca por tamano.
 */
@QuarkusTest
class WorkersResourceTest {

    @Inject WarehouseTestData fixtures;

    @AfterEach
    void cleanupFixtures() {
        QuarkusTransaction.requiringNew().run(() -> fixtures.deleteTestWorkers());
    }

    // ---------- happy path -------------------------------------------------------

    @Test
    void listWorkers_returnsSeededWorkerWithComposedFullName() {
        int id = fixtures.seedWorker("ZTESTW900", "Juan", "Perez", "Mecánico", true);
        String token = adminToken();

        given().header("Authorization", "Bearer " + token).when().get("/workers")
        .then().statusCode(200)
            .body("find { it.id == " + id + " }.fullName", equalTo("Juan Perez"))
            .body("find { it.id == " + id + " }.position", equalTo("Mecánico"))
            .body("find { it.id == " + id + " }.isActive", equalTo(true));
    }

    // ---------- q filter ---------------------------------------------------------

    @Test
    void listWorkers_qMatchesPartialNameCaseInsensitive() {
        int id = fixtures.seedWorker("ZTESTW902", "Carlos", "Ramirez", "Chofer", true);
        String token = adminToken();

        given().header("Authorization", "Bearer " + token).queryParam("q", "carlos")
        .when().get("/workers")
        .then().statusCode(200)
            .body("id", hasItem(id))
            .body("find { it.id == " + id + " }.fullName", equalTo("Carlos Ramirez"));
    }

    @Test
    void listWorkers_qMultiWordMatchesFirstAndLastName() {
        int id = fixtures.seedWorker("ZTESTW903", "Juan", "Perez", "Mecánico", true);
        String token = adminToken();

        // "juan perez": cada palabra matchea first_name O last_name (MultiWordSearch, AND de ORs)
        given().header("Authorization", "Bearer " + token).queryParam("q", "juan perez")
        .when().get("/workers")
        .then().statusCode(200).body("id", hasItem(id));
    }

    @Test
    void listWorkers_qNoMatchReturnsEmptyArray() {
        fixtures.seedWorker("ZTESTW910", "Ana", "Silva", "Ayudante", true);
        String token = adminToken();

        given().header("Authorization", "Bearer " + token).queryParam("q", "zzzznomatch999")
        .when().get("/workers")
        .then().statusCode(200).body("$", empty());
    }

    @Test
    void listWorkers_qShorterThanMinLengthReturns400_COM001() {
        String token = adminToken();
        given().header("Authorization", "Bearer " + token).queryParam("q", "ab")
        .when().get("/workers")
        .then().statusCode(400).body("code", equalTo("COM-001"));
    }

    // ---------- isActive filter --------------------------------------------------

    @Test
    void listWorkers_isActiveFalseIncludesInactive() {
        int id = fixtures.seedWorker("ZTESTW904", "Ines", "Torres", "Ayudante", false);
        String token = adminToken();

        given().header("Authorization", "Bearer " + token).queryParam("isActive", false)
        .when().get("/workers")
        .then().statusCode(200)
            .body("id", hasItem(id))
            .body("find { it.id == " + id + " }.isActive", equalTo(false));
    }

    @Test
    void listWorkers_isActiveTrueExcludesInactive() {
        int id = fixtures.seedWorker("ZTESTW905", "Pedro", "Diaz", "Chofer", false);
        String token = adminToken();

        given().header("Authorization", "Bearer " + token).queryParam("isActive", true)
        .when().get("/workers")
        .then().statusCode(200).body("id", not(hasItem(id)));
    }

    @Test
    void listWorkers_qAndIsActiveCombined() {
        int active = fixtures.seedWorker("ZTESTW920", "Aaa", "Ztcombo", "Chofer", true);
        int inactive = fixtures.seedWorker("ZTESTW921", "Bbb", "Ztcombo", "Chofer", false);
        String token = adminToken();

        // q acota por apellido comun; isActive=true debe excluir al inactivo (AND de las 2 condiciones)
        given().header("Authorization", "Bearer " + token)
            .queryParam("q", "ztcombo").queryParam("isActive", true)
        .when().get("/workers")
        .then().statusCode(200)
            .body("id", hasItem(active))
            .body("id", not(hasItem(inactive)));
    }

    @Test
    void listWorkers_noFiltersIncludesSeeded() {
        int id = fixtures.seedWorker("ZTESTW906", "Luis", "Vega", "Mecánico", true);
        String token = adminToken();

        given().header("Authorization", "Bearer " + token).when().get("/workers")
        .then().statusCode(200).body("id", hasItem(id));
    }

    @Test
    void listWorkers_orderedByFirstNameAsc() {
        int zeta = fixtures.seedWorker("ZTESTW907", "Zzz", "Ztestord", "Chofer", true);
        int alfa = fixtures.seedWorker("ZTESTW908", "Aaa", "Ztestord", "Chofer", true);
        String token = adminToken();

        // q acota el universo a los 2 sembrados (last_name comun); orden por first_name: Aaa antes que Zzz
        List<Integer> ids = given().header("Authorization", "Bearer " + token).queryParam("q", "ztestord")
        .when().get("/workers")
        .then().statusCode(200).extract().jsonPath().getList("id", Integer.class);

        assertTrue(ids.indexOf(alfa) < ids.indexOf(zeta),
            "Aaa (first_name) debe venir antes que Zzz; ids=" + ids);
    }

    // ---------- roles ------------------------------------------------------------

    @Test
    void listWorkers_withoutToken_returns401() {
        given().when().get("/workers").then().statusCode(401);
    }

    @Test
    void listWorkers_withSalesRole_returns403_COM003() {
        String token = login("lcampos", "Sales1234");
        given().header("Authorization", "Bearer " + token).when().get("/workers")
        .then().statusCode(403).body("code", equalTo("COM-003"));
    }

    @Test
    void listWorkers_withDispatcherRole_returns403_COM003() {
        given().header("Authorization", "Bearer " + fabricateAccessToken("disp_test", "dispatcher"))
        .when().get("/workers").then().statusCode(403).body("code", equalTo("COM-003"));
    }

    @Test
    void listWorkers_withWarehouseKeeperRole_returns200() {
        given().header("Authorization", "Bearer " + fabricateAccessToken("wk_test", "warehouse_keeper"))
        .when().get("/workers").then().statusCode(200);
    }

    @Test
    void listWorkers_withOperationsManagerRole_returns200() {
        given().header("Authorization", "Bearer " + fabricateAccessToken("om_test", "operations_manager"))
        .when().get("/workers").then().statusCode(200);
    }
}
