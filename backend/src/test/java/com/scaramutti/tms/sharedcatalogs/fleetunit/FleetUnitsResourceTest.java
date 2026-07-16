package com.scaramutti.tms.sharedcatalogs.fleetunit;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.jwt.build.Jwt;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests de GET /fleet-units. Hermetico: la flota de test se siembra con un
 * estado de recurso propio ({@code ZTEST_STATUS}) y se limpia por ese status en
 * {@code @AfterEach} (NUNCA por prefijo de placa: {@code public.tractors}/etc son
 * COMPARTIDAS con v1 y un prefijo corto podria matchear una placa real). Placas {@code ZR00xx}
 * (distintas de las {@code ZT00xx}/{@code ZR000x} de otros tests). Aserciones por PRESENCIA.
 */
@QuarkusTest
class FleetUnitsResourceTest {

    @Inject EntityManager entityManager;

    @AfterEach
    void cleanupFixtures() {
        QuarkusTransaction.requiringNew().run(() -> {
            String byTestStatus = "WHERE status_id = (SELECT id FROM public.resource_statuses WHERE name = 'ZTEST_STATUS')";
            entityManager.createNativeQuery("DELETE FROM public.tractors " + byTestStatus).executeUpdate();
            entityManager.createNativeQuery("DELETE FROM public.trailers " + byTestStatus).executeUpdate();
            entityManager.createNativeQuery("DELETE FROM public.escort_vehicles " + byTestStatus).executeUpdate();
            entityManager.createNativeQuery("DELETE FROM public.resource_statuses WHERE name = 'ZTEST_STATUS'")
                .executeUpdate();
        });
    }

    // ---------- fixtures --------------------------------------------------------

    private int resourceStatusId() {
        var rows = entityManager.createNativeQuery(
            "SELECT id FROM public.resource_statuses WHERE name = 'ZTEST_STATUS'").getResultList();
        if (!rows.isEmpty()) {
            return ((Number) rows.get(0)).intValue();
        }
        entityManager.createNativeQuery(
            "INSERT INTO public.resource_statuses (name, is_active) VALUES ('ZTEST_STATUS', true)")
            .executeUpdate();
        return ((Number) entityManager.createNativeQuery(
            "SELECT id FROM public.resource_statuses WHERE name = 'ZTEST_STATUS'")
            .getSingleResult()).intValue();
    }

    private int seedTractor(String plate, boolean isActive, String brand, String model) {
        return QuarkusTransaction.requiringNew().call(() -> ((Number) entityManager.createNativeQuery(
            "INSERT INTO public.tractors (plate, brand, model, status_id, is_active) "
                + "VALUES (?1, ?2, ?3, ?4, ?5) RETURNING id")
            .setParameter(1, plate).setParameter(2, brand).setParameter(3, model)
            .setParameter(4, resourceStatusId()).setParameter(5, isActive).getSingleResult()).intValue());
    }

    private int seedTrailer(String plate, boolean isActive) {
        return QuarkusTransaction.requiringNew().call(() -> ((Number) entityManager.createNativeQuery(
            "INSERT INTO public.trailers (plate, type, status_id, is_active) VALUES (?1, 'ZTEST', ?2, ?3) RETURNING id")
            .setParameter(1, plate).setParameter(2, resourceStatusId()).setParameter(3, isActive)
            .getSingleResult()).intValue());
    }

    private int seedEscortVehicle(String plate, boolean isActive, String brand, String model) {
        return QuarkusTransaction.requiringNew().call(() -> ((Number) entityManager.createNativeQuery(
            "INSERT INTO public.escort_vehicles (plate, brand, model, status_id, is_active) "
                + "VALUES (?1, ?2, ?3, ?4, ?5) RETURNING id")
            .setParameter(1, plate).setParameter(2, brand).setParameter(3, model)
            .setParameter(4, resourceStatusId()).setParameter(5, isActive).getSingleResult()).intValue());
    }

    private String login(String username, String password) {
        return given().contentType("application/json")
            .body("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}")
        .when().post("/auth/login").then().statusCode(200).extract().jsonPath().getString("token");
    }

    private String fabricateAccessToken(String username, String role) {
        Instant now = Instant.now();
        return Jwt.subject("999").upn(username).groups(Set.of(role)).claim("typ", "access")
            .issuedAt(now).expiresAt(now.plusSeconds(3600)).sign();
    }

    private String adminToken() {
        return login("admin", "Admin1234");
    }

    // ---------- happy path -------------------------------------------------------

    @Test
    void listFleetUnits_mixedKindsWithBrandModelAndTrailerNulls() {
        int tractor = seedTractor("ZR0001", true, "Volvo", "FH");
        int trailer = seedTrailer("ZR0002", true);
        int escort = seedEscortVehicle("ZR0003", true, "Toyota", "Hilux");
        String token = adminToken();

        given().header("Authorization", "Bearer " + token).when().get("/fleet-units")
        .then().statusCode(200)
            .body("find { it.kind == 'TRACTOR' && it.id == " + tractor + " }.plate", equalTo("ZR0001"))
            .body("find { it.kind == 'TRACTOR' && it.id == " + tractor + " }.brand", equalTo("Volvo"))
            .body("find { it.kind == 'TRACTOR' && it.id == " + tractor + " }.model", equalTo("FH"))
            .body("find { it.kind == 'TRACTOR' && it.id == " + tractor + " }.isActive", equalTo(true))
            .body("find { it.kind == 'TRAILER' && it.id == " + trailer + " }.plate", equalTo("ZR0002"))
            .body("find { it.kind == 'TRAILER' && it.id == " + trailer + " }.brand", nullValue())
            .body("find { it.kind == 'TRAILER' && it.id == " + trailer + " }.model", nullValue())
            .body("find { it.kind == 'ESCORT' && it.id == " + escort + " }.brand", equalTo("Toyota"))
            .body("find { it.kind == 'ESCORT' && it.id == " + escort + " }.model", equalTo("Hilux"));
    }

    // ---------- kind filter ------------------------------------------------------

    @Test
    void listFleetUnits_kindTractorReturnsOnlyTractors() {
        int tractor = seedTractor("ZR0004", true, "Scania", "R450");
        int trailer = seedTrailer("ZR0005", true);
        int escort = seedEscortVehicle("ZR0006", true, "Nissan", "Frontier");
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
        int trailer = seedTrailer("ZR0007", true);
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
        int escort = seedEscortVehicle("ZR0008", true, "Nissan", "X-Trail");
        String token = adminToken();

        given().header("Authorization", "Bearer " + token).queryParam("kind", "ESCORT")
        .when().get("/fleet-units")
        .then().statusCode(200)
            .body("find { it.id == " + escort + " }.kind", equalTo("ESCORT"))
            .body("find { it.id == " + escort + " }.plate", equalTo("ZR0008"))
            .body("find { it.id == " + escort + " }.brand", equalTo("Nissan"));
    }

    @Test
    void listFleetUnits_withoutKindReturnsAllThreeSubtypes() {
        int tractor = seedTractor("ZR0009", true, "Volvo", "FM");
        int trailer = seedTrailer("ZR0010", true);
        int escort = seedEscortVehicle("ZR0011", true, "Toyota", "Land Cruiser");
        String token = adminToken();

        given().header("Authorization", "Bearer " + token).when().get("/fleet-units")
        .then().statusCode(200)
            .body("find { it.kind == 'TRACTOR' && it.id == " + tractor + " }.plate", equalTo("ZR0009"))
            .body("find { it.kind == 'TRAILER' && it.id == " + trailer + " }.plate", equalTo("ZR0010"))
            .body("find { it.kind == 'ESCORT' && it.id == " + escort + " }.plate", equalTo("ZR0011"));
    }

    // ---------- isActive filter --------------------------------------------------

    @Test
    void listFleetUnits_isActiveFalseIncludesInactive() {
        int tractor = seedTractor("ZR0012", false, "Volvo", "FH");
        String token = adminToken();

        given().header("Authorization", "Bearer " + token).queryParam("isActive", false)
        .when().get("/fleet-units")
        .then().statusCode(200)
            .body("find { it.kind == 'TRACTOR' && it.id == " + tractor + " }.isActive", equalTo(false));
    }

    @Test
    void listFleetUnits_isActiveTrueExcludesInactive() {
        int trailer = seedTrailer("ZR0013", false);
        String token = adminToken();

        given().header("Authorization", "Bearer " + token).queryParam("isActive", true)
        .when().get("/fleet-units")
        .then().statusCode(200).body("id", not(hasItem(trailer)));
    }

    @Test
    void listFleetUnits_kindAndIsActiveCombined() {
        int active = seedTractor("ZR0014", true, "Volvo", "FH");
        int inactive = seedTractor("ZR0015", false, "Volvo", "FH");
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
        int high = seedTractor("ZR0020", true, "Volvo", "FH");
        int low = seedTractor("ZR0016", true, "Volvo", "FH");
        String token = adminToken();

        List<Integer> ids = given().header("Authorization", "Bearer " + token).queryParam("kind", "TRACTOR")
        .when().get("/fleet-units")
        .then().statusCode(200).extract().jsonPath().getList("id", Integer.class);

        assertTrue(ids.indexOf(low) < ids.indexOf(high),
            "ZR0016 debe venir antes que ZR0020 (plate ASC); ids=" + ids);
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

    @Test
    void listFleetUnits_withSalesRole_returns403_COM003() {
        String token = login("lcampos", "Sales1234");
        given().header("Authorization", "Bearer " + token).when().get("/fleet-units")
        .then().statusCode(403).body("code", equalTo("COM-003"));
    }

    @Test
    void listFleetUnits_withDispatcherRole_returns403_COM003() {
        given().header("Authorization", "Bearer " + fabricateAccessToken("disp_test", "dispatcher"))
        .when().get("/fleet-units").then().statusCode(403).body("code", equalTo("COM-003"));
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
