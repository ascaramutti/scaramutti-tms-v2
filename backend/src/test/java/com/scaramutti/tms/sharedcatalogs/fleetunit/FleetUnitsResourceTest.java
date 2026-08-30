package com.scaramutti.tms.sharedcatalogs.fleetunit;

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
 * Integration tests de GET /fleet-units. Hermetico: la flota de test se limpia en
 * {@code @AfterEach} por los ids que sembro el fixture, con un barrido de respaldo por el
 * rango de placas reservado ({@code public.tractors}/etc son COMPARTIDAS con v1). Placas en
 * el rango PROPIO {@code ZF00xx} (los retiros usan {@code ZT0xxx} y los reportes
 * {@code ZR00xx}: rangos disjuntos para no colisionar en la BD compartida). Aserciones por
 * PRESENCIA.
 */
@QuarkusTest
class FleetUnitsResourceTest {

    @Inject WarehouseTestData fixtures;

    @AfterEach
    void cleanupFixtures() {
        QuarkusTransaction.requiringNew().run(() -> fixtures.deleteTestFleet());
    }

    // ---------- happy path -------------------------------------------------------

    @Test
    void listFleetUnits_mixedKindsWithBrandModelAndTrailerNulls() {
        int tractor = fixtures.seedTractor("ZF0001", true, "Volvo", "FH");
        int trailer = fixtures.seedTrailer("ZF0002", true);
        int escort = fixtures.seedEscortVehicle("ZF0003", true, "Toyota", "Hilux");
        String token = adminToken();

        given().header("Authorization", "Bearer " + token).when().get("/fleet-units")
        .then().statusCode(200)
            .body("find { it.kind == 'TRACTOR' && it.id == " + tractor + " }.plate", equalTo("ZF0001"))
            .body("find { it.kind == 'TRACTOR' && it.id == " + tractor + " }.brand", equalTo("Volvo"))
            .body("find { it.kind == 'TRACTOR' && it.id == " + tractor + " }.model", equalTo("FH"))
            .body("find { it.kind == 'TRACTOR' && it.id == " + tractor + " }.isActive", equalTo(true))
            .body("find { it.kind == 'TRAILER' && it.id == " + trailer + " }.plate", equalTo("ZF0002"))
            .body("find { it.kind == 'TRAILER' && it.id == " + trailer + " }.brand", nullValue())
            .body("find { it.kind == 'TRAILER' && it.id == " + trailer + " }.model", nullValue())
            .body("find { it.kind == 'ESCORT' && it.id == " + escort + " }.brand", equalTo("Toyota"))
            .body("find { it.kind == 'ESCORT' && it.id == " + escort + " }.model", equalTo("Hilux"));
    }

    // ---------- disponibilidad ---------------------------------------------------

    /**
     * El estado sale del catalogo {@code public.resource_statuses} POR NOMBRE (sus ids
     * difieren entre ambientes) y llega a la API en mayusculas.
     */
    @Test
    void listFleetUnits_tractorAndTrailerCarryTheirAvailability() {
        int available = fixtures.seedTractor("ZF0021", true, "Volvo", "FH", WarehouseTestData.STATUS_AVAILABLE);
        int maintenance = fixtures.seedTractor("ZF0022", true, "Scania", "R450",
            WarehouseTestData.STATUS_MAINTENANCE);
        int notAvailable = fixtures.seedTrailer("ZF0023", true, WarehouseTestData.STATUS_NOT_AVAILABLE);
        String token = adminToken();

        given().header("Authorization", "Bearer " + token).when().get("/fleet-units")
        .then().statusCode(200)
            .body("find { it.kind == 'TRACTOR' && it.id == " + available + " }.status", equalTo("AVAILABLE"))
            .body("find { it.kind == 'TRACTOR' && it.id == " + maintenance + " }.status", equalTo("MAINTENANCE"))
            .body("find { it.kind == 'TRAILER' && it.id == " + notAvailable + " }.status",
                equalTo("NOT_AVAILABLE"));
    }

    /** Las escoltas no se asignan a viajes: su disponibilidad no significa nada y viaja en null. */
    @Test
    void listFleetUnits_escortCarriesNullAvailability() {
        int escort = fixtures.seedEscortVehicle("ZF0024", true, "Toyota", "Hilux");
        String token = adminToken();

        given().header("Authorization", "Bearer " + token).queryParam("kind", "ESCORT")
        .when().get("/fleet-units")
        .then().statusCode(200)
            .body("find { it.id == " + escort + " }.status", nullValue());
    }

    /** El shape es el del contrato: ni un campo de mas ni uno de menos. */
    @Test
    void listFleetUnits_responseShapeMatchesTheContract() {
        int tractor = fixtures.seedTractor("ZF0025", true, "Volvo", "FH");
        String token = adminToken();

        Map<String, Object> unit = given().header("Authorization", "Bearer " + token)
            .queryParam("kind", "TRACTOR")
        .when().get("/fleet-units")
        .then().statusCode(200)
            .extract().jsonPath().getMap("find { it.id == " + tractor + " }");

        assertEquals(Set.of("kind", "id", "plate", "brand", "model", "status", "isActive"), unit.keySet());
    }

    // ---------- kind filter ------------------------------------------------------

    @Test
    void listFleetUnits_kindTractorReturnsOnlyTractors() {
        int tractor = fixtures.seedTractor("ZF0004", true, "Scania", "R450");
        int trailer = fixtures.seedTrailer("ZF0005", true);
        int escort = fixtures.seedEscortVehicle("ZF0006", true, "Nissan", "Frontier");
        String token = adminToken();

        var kinds = given().header("Authorization", "Bearer " + token).queryParam("kind", "TRACTOR")
        .when().get("/fleet-units")
        .then().statusCode(200)
            .body("id", hasItem(tractor))
            .body("id", not(hasItem(trailer)))
            .body("id", not(hasItem(escort)))
            .extract().jsonPath().getList("kind", String.class);
        assertTrue(kinds.stream().allMatch("TRACTOR"::equals), "solo TRACTOR; fue " + kinds);
    }

    @Test
    void listFleetUnits_kindTrailerHasNullBrandModel() {
        int trailer = fixtures.seedTrailer("ZF0007", true);
        String token = adminToken();

        given().header("Authorization", "Bearer " + token).queryParam("kind", "TRAILER")
        .when().get("/fleet-units")
        .then().statusCode(200)
            .body("find { it.id == " + trailer + " }.kind", equalTo("TRAILER"))
            .body("find { it.id == " + trailer + " }.brand", nullValue())
            .body("find { it.id == " + trailer + " }.model", nullValue());
    }

    @Test
    void listFleetUnits_kindEscortReturnsEscortWithBrandModel() {
        int escort = fixtures.seedEscortVehicle("ZF0008", true, "Nissan", "X-Trail");
        String token = adminToken();

        given().header("Authorization", "Bearer " + token).queryParam("kind", "ESCORT")
        .when().get("/fleet-units")
        .then().statusCode(200)
            .body("find { it.id == " + escort + " }.kind", equalTo("ESCORT"))
            .body("find { it.id == " + escort + " }.plate", equalTo("ZF0008"))
            .body("find { it.id == " + escort + " }.brand", equalTo("Nissan"));
    }

    @Test
    void listFleetUnits_withoutKindReturnsAllThreeSubtypes() {
        int tractor = fixtures.seedTractor("ZF0009", true, "Volvo", "FM");
        int trailer = fixtures.seedTrailer("ZF0010", true);
        int escort = fixtures.seedEscortVehicle("ZF0011", true, "Toyota", "Land Cruiser");
        String token = adminToken();

        given().header("Authorization", "Bearer " + token).when().get("/fleet-units")
        .then().statusCode(200)
            .body("find { it.kind == 'TRACTOR' && it.id == " + tractor + " }.plate", equalTo("ZF0009"))
            .body("find { it.kind == 'TRAILER' && it.id == " + trailer + " }.plate", equalTo("ZF0010"))
            .body("find { it.kind == 'ESCORT' && it.id == " + escort + " }.plate", equalTo("ZF0011"));
    }

    // ---------- isActive filter --------------------------------------------------

    @Test
    void listFleetUnits_isActiveFalseIncludesInactive() {
        int tractor = fixtures.seedTractor("ZF0012", false, "Volvo", "FH");
        String token = adminToken();

        given().header("Authorization", "Bearer " + token).queryParam("isActive", false)
        .when().get("/fleet-units")
        .then().statusCode(200)
            .body("find { it.kind == 'TRACTOR' && it.id == " + tractor + " }.isActive", equalTo(false));
    }

    @Test
    void listFleetUnits_isActiveTrueExcludesInactive() {
        int trailer = fixtures.seedTrailer("ZF0013", false);
        String token = adminToken();

        given().header("Authorization", "Bearer " + token).queryParam("isActive", true)
        .when().get("/fleet-units")
        .then().statusCode(200).body("id", not(hasItem(trailer)));
    }

    @Test
    void listFleetUnits_kindAndIsActiveCombined() {
        int active = fixtures.seedTractor("ZF0014", true, "Volvo", "FH");
        int inactive = fixtures.seedTractor("ZF0015", false, "Volvo", "FH");
        String token = adminToken();

        given().header("Authorization", "Bearer " + token)
            .queryParam("kind", "TRACTOR").queryParam("isActive", true)
        .when().get("/fleet-units")
        .then().statusCode(200)
            .body("id", hasItem(active))
            .body("id", not(hasItem(inactive)));
    }

    @Test
    void listFleetUnits_orderedByPlateAscWithinKind() {
        int high = fixtures.seedTractor("ZF0020", true, "Volvo", "FH");
        int low = fixtures.seedTractor("ZF0016", true, "Volvo", "FH");
        String token = adminToken();

        List<Integer> ids = given().header("Authorization", "Bearer " + token).queryParam("kind", "TRACTOR")
        .when().get("/fleet-units")
        .then().statusCode(200).extract().jsonPath().getList("id", Integer.class);

        assertTrue(ids.indexOf(low) < ids.indexOf(high),
            "ZF0016 debe venir antes que ZF0020 (plate ASC); ids=" + ids);
    }

    // ---------- kind invalido ----------------------------------------------------

    @Test
    void listFleetUnits_invalidKindEnum_returns404() {
        String token = adminToken();
        given().header("Authorization", "Bearer " + token).queryParam("kind", "INVALIDO")
        .when().get("/fleet-units").then().statusCode(404);
    }

    // ---------- roles ------------------------------------------------------------

    @Test
    void listFleetUnits_withoutToken_returns401() {
        given().when().get("/fleet-units").then().statusCode(401);
    }

    /** Registra y edita servicios: elige tracto y carreta como quien despacha. */
    @Test
    void listFleetUnits_withSalesRole_returns200() {
        String token = login("lcampos", "Sales1234");
        given().header("Authorization", "Bearer " + token).when().get("/fleet-units")
        .then().statusCode(200);
    }

    @Test
    void listFleetUnits_withDispatcherRole_returns200() {
        given().header("Authorization", "Bearer " + fabricateAccessToken("disp_test", "dispatcher"))
        .when().get("/fleet-units").then().statusCode(200);
    }

    @Test
    void listFleetUnits_withWarehouseKeeperRole_returns200() {
        given().header("Authorization", "Bearer " + fabricateAccessToken("wk_test", "warehouse_keeper"))
        .when().get("/fleet-units").then().statusCode(200);
    }

    @Test
    void listFleetUnits_withFinanceManagerRole_returns200() {
        given().header("Authorization", "Bearer " + fabricateAccessToken("fm_test", "finance_manager"))
        .when().get("/fleet-units").then().statusCode(200);
    }
}
