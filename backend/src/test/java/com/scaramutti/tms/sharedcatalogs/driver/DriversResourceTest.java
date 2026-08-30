package com.scaramutti.tms.sharedcatalogs.driver;

import com.scaramutti.tms.support.OperationsTestData;
import com.scaramutti.tms.support.WarehouseTestData;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.scaramutti.tms.support.TestAuth.adminToken;
import static com.scaramutti.tms.support.TestAuth.fabricateAccessToken;
import static com.scaramutti.tms.support.TestAuth.login;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests de GET /drivers. Hermetico: cada conductor de test siembra su propio
 * trabajador y se limpia en {@code @AfterEach} por los ids sembrados (NUNCA por prefijo de
 * licencia: {@code public.drivers} es COMPARTIDA con v1). Los conductores se borran ANTES que
 * los trabajadores, que son su FK. Aserciones por PRESENCIA: la tabla tiene data real.
 */
@QuarkusTest
class DriversResourceTest {

    @Inject OperationsTestData fixtures;
    @Inject WarehouseTestData warehouseFixtures;

    @AfterEach
    void cleanupFixtures() {
        QuarkusTransaction.requiringNew().run(() -> {
            fixtures.deleteTestDrivers();
            warehouseFixtures.deleteTestWorkers();
        });
    }

    // ---------- happy path -------------------------------------------------------

    /** El shape es el del contrato: ni un campo de mas ni uno de menos. */
    @Test
    void listDrivers_responseShapeMatchesTheContract() {
        int driver = fixtures.seedDriver("ZTEST Ana", "Quispe", "987654321", "A-IIIc",
            WarehouseTestData.STATUS_AVAILABLE, true);
        String token = adminToken();

        Map<String, Object> found = given().header("Authorization", "Bearer " + token)
        .when().get("/drivers")
        .then().statusCode(200)
            .body("find { it.id == " + driver + " }.fullName", equalTo("ZTEST Ana Quispe"))
            .body("find { it.id == " + driver + " }.licenseCategory", equalTo("A-IIIc"))
            .body("find { it.id == " + driver + " }.phone", equalTo("987654321"))
            .body("find { it.id == " + driver + " }.status", equalTo("AVAILABLE"))
            .body("find { it.id == " + driver + " }.isActive", equalTo(true))
            .extract().jsonPath().getMap("find { it.id == " + driver + " }");

        assertEquals(
            Set.of("id", "fullName", "licenseNumber", "licenseCategory", "phone", "status", "isActive"),
            found.keySet());
    }

    /** La categoria y el telefono son opcionales en la BD de v1. */
    @Test
    void listDrivers_optionalLicenseCategoryAndPhoneTravelAsNull() {
        int driver = fixtures.seedDriver("ZTEST Bruno", "Rojas");
        String token = adminToken();

        given().header("Authorization", "Bearer " + token).when().get("/drivers")
        .then().statusCode(200)
            .body("find { it.id == " + driver + " }.licenseCategory", nullValue())
            .body("find { it.id == " + driver + " }.phone", nullValue());
    }

    /** La disponibilidad sale del catalogo POR NOMBRE (sus ids difieren entre ambientes). */
    @Test
    void listDrivers_carryTheirAvailability() {
        int maintenance = fixtures.seedDriver("ZTEST Carla", "Diaz", null, null,
            WarehouseTestData.STATUS_MAINTENANCE, true);
        int notAvailable = fixtures.seedDriver("ZTEST Diego", "Flores", null, null,
            WarehouseTestData.STATUS_NOT_AVAILABLE, true);
        String token = adminToken();

        given().header("Authorization", "Bearer " + token).when().get("/drivers")
        .then().statusCode(200)
            .body("find { it.id == " + maintenance + " }.status", equalTo("MAINTENANCE"))
            .body("find { it.id == " + notAvailable + " }.status", equalTo("NOT_AVAILABLE"));
    }

    @Test
    void listDrivers_orderedByNameAsc() {
        int last = fixtures.seedDriver("ZZTEST Ultimo", "Vargas");
        int first = fixtures.seedDriver("ZTEST Aaa", "Aguirre");
        String token = adminToken();

        List<Integer> ids = given().header("Authorization", "Bearer " + token)
        .when().get("/drivers")
        .then().statusCode(200).extract().jsonPath().getList("id", Integer.class);

        assertTrue(ids.indexOf(first) < ids.indexOf(last),
            "ZTEST Aaa debe venir antes que ZZTEST Ultimo (nombre ASC); ids=" + ids);
    }

    // ---------- isActive filter --------------------------------------------------

    @Test
    void listDrivers_isActiveTrueExcludesInactive() {
        int inactive = fixtures.seedDriver("ZTEST Elena", "Mendoza", null, null,
            WarehouseTestData.STATUS_AVAILABLE, false);
        String token = adminToken();

        given().header("Authorization", "Bearer " + token).queryParam("isActive", true)
        .when().get("/drivers")
        .then().statusCode(200).body("id", not(hasItem(inactive)));
    }

    @Test
    void listDrivers_isActiveFalseReturnsOnlyInactive() {
        int inactive = fixtures.seedDriver("ZTEST Fabio", "Reyes", null, null,
            WarehouseTestData.STATUS_AVAILABLE, false);
        int active = fixtures.seedDriver("ZTEST Gina", "Salas");
        String token = adminToken();

        given().header("Authorization", "Bearer " + token).queryParam("isActive", false)
        .when().get("/drivers")
        .then().statusCode(200)
            .body("id", hasItem(inactive))
            .body("id", not(hasItem(active)));
    }

    @Test
    void listDrivers_withoutFilterReturnsActiveAndInactive() {
        int inactive = fixtures.seedDriver("ZTEST Hugo", "Torres", null, null,
            WarehouseTestData.STATUS_AVAILABLE, false);
        int active = fixtures.seedDriver("ZTEST Ines", "Vega");
        String token = adminToken();

        given().header("Authorization", "Bearer " + token).when().get("/drivers")
        .then().statusCode(200)
            .body("id", hasItem(inactive))
            .body("id", hasItem(active));
    }

    // ---------- roles ------------------------------------------------------------

    @Test
    void listDrivers_withoutToken_returns401() {
        given().when().get("/drivers").then().statusCode(401);
    }

    @Test
    void listDrivers_withDispatcherRole_returns200() {
        given().header("Authorization", "Bearer " + fabricateAccessToken("disp_test", "dispatcher"))
        .when().get("/drivers").then().statusCode(200);
    }

    /** Registra y edita servicios, asi que elige conductor. */
    @Test
    void listDrivers_withSalesRole_returns200() {
        String token = login("lcampos", "Sales1234");
        given().header("Authorization", "Bearer " + token).when().get("/drivers")
        .then().statusCode(200);
    }

    /** Finanzas y almacen NO estan en la lista de este endpoint: el conductor no es asunto suyo. */
    @Test
    void listDrivers_withFinanceManagerRole_returns403_COM003() {
        given().header("Authorization", "Bearer " + fabricateAccessToken("fm_test", "finance_manager"))
        .when().get("/drivers").then().statusCode(403).body("code", equalTo("COM-003"));
    }

    @Test
    void listDrivers_withWarehouseKeeperRole_returns403_COM003() {
        given().header("Authorization", "Bearer " + fabricateAccessToken("wk_test", "warehouse_keeper"))
        .when().get("/drivers").then().statusCode(403).body("code", equalTo("COM-003"));
    }
}
