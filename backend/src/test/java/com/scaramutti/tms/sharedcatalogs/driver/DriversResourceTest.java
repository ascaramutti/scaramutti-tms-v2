package com.scaramutti.tms.sharedcatalogs.driver;

import com.scaramutti.tms.support.OperationsTestData;
import com.scaramutti.tms.support.WarehouseTestData;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

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
            .body("find { it.id == " + driver + " }.isAssignable", equalTo(true))
            .extract().jsonPath().getMap("find { it.id == " + driver + " }");

        assertEquals(
            Set.of("id", "fullName", "licenseNumber", "licenseCategory", "phone", "status", "isActive", "isAssignable"),
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

    // ---------- solo conductores ---------------------------------------------------

    /**
     * El escolta y el ayudante con licencia tambien tienen ficha, pero el dueno decidio que en los
     * servicios se asignan solo conductores: con y sin filtro, sus fichas no salen, activas o no. Y
     * la del conductor si, en la lista que le toca por su filtro.
     */
    @ParameterizedTest(name = "isActive={0}")
    @NullSource
    @ValueSource(booleans = {true, false})
    void listDrivers_onlyTheDriverRole_neverEscortsNorAssistants(Boolean isActive) {
        int driverOn = fixtures.seedDriver("ZTEST Conductor", "Activo");
        int driverOff = fixtures.seedDriver("ZTEST Conductor", "Apagado", null, null,
            WarehouseTestData.STATUS_AVAILABLE, false);
        int escortOn = fixtures.seedDriverOfRole("ZTEST Escolta", "Activo", "escort", true);
        int escortOff = fixtures.seedDriverOfRole("ZTEST Escolta", "Apagado", "escort", false);
        int assistantOn = fixtures.seedDriverOfRole("ZTEST Ayudante", "Activo", "assistant", true);
        int assistantOff = fixtures.seedDriverOfRole("ZTEST Ayudante", "Apagado", "assistant", false);

        var request = given().header("Authorization", "Bearer " + adminToken());
        if (isActive != null) {
            request = request.queryParam("isActive", isActive);
        }
        var ids = request.when().get("/drivers").then().statusCode(200).extract().<Integer>jsonPath().getList("id");

        for (int other : new int[] {escortOn, escortOff, assistantOn, assistantOff}) {
            assertTrue(!ids.contains(other), "una ficha que no es de conductor salio: " + other);
        }
        assertEquals(!Boolean.FALSE.equals(isActive), ids.contains(driverOn), "el conductor activo");
        assertEquals(!Boolean.TRUE.equals(isActive), ids.contains(driverOff), "el conductor apagado");
    }

    /**
     * La ficha de alguien que DEJO de ser conductor no sale ni como inactiva, apagada (como la deja
     * la edicion) o activa (si quedo asi por fuera de la API): lo que decide es el cargo de hoy, no
     * la bandera de la ficha. El cargo se cambia por SQL; la edicion la mide la bateria.
     */
    @ParameterizedTest(name = "ficha activa={0}")
    @ValueSource(booleans = {false, true})
    void listDrivers_aFormerDriver_doesNotAppear_withAnyFilter(boolean profileStaysActive) {
        int former = fixtures.seedDriver("ZTEST Ex", "Conductor", null, null,
            WarehouseTestData.STATUS_AVAILABLE, profileStaysActive);
        warehouseFixtures.setWorkerRole(fixtures.workerIdOfDriver(former), "operator");

        // isAssignable=false es el filtro que el ex conductor SI cumple: lo deja afuera solo el cargo
        String[] filters = {null, "isActive=true", "isActive=false", "isAssignable=true", "isAssignable=false"};
        for (String filter : filters) {
            var request = given().header("Authorization", "Bearer " + adminToken());
            if (filter != null) {
                request = request.queryParam(filter.split("=")[0], filter.split("=")[1]);
            }
            request.when().get("/drivers").then().statusCode(200).body("id", not(hasItem(former)));
        }
    }

    /**
     * Las dos banderas, cada una con su valor y su filtro: isActive es la ficha, tal cual, y
     * isAssignable, ficha encendida y trabajador activo. La ficha encendida de un trabajador dado de
     * baja es la que las separa (activa y no asignable); ninguna puede tomar el valor de la otra.
     */
    @Test
    void listDrivers_isActiveIsTheProfile_assignableIsProfileAndWorker() {
        int healthy = fixtures.seedDriver("ZTEST Sano", "Conductor");
        int off = fixtures.seedDriver("ZTEST Ficha", "Apagada", null, null, WarehouseTestData.STATUS_AVAILABLE, false);
        int left = fixtures.seedDriverWithInactiveWorker("ZTEST Baja", "Conductor");
        int both = fixtures.seedDriverWithInactiveWorker("ZTEST Baja", "Apagada");
        fixtures.setDriverActive(both, false);

        // id -> {isActive, isAssignable} en la fila
        Map<Integer, List<Boolean>> expected = Map.of(
            healthy, List.of(true, true), off, List.of(false, false),
            left, List.of(true, false), both, List.of(false, false));
        List<Map<String, Object>> rows = given().header("Authorization", "Bearer " + adminToken())
            .when().get("/drivers").then().statusCode(200).extract().jsonPath().getList("$");
        expected.forEach((id, flags) -> {
            Map<String, Object> row = rows.stream().filter(r -> id.equals(r.get("id"))).findFirst().orElseThrow();
            assertEquals(flags, List.of(row.get("isActive"), row.get("isAssignable")), "banderas de " + id);
        });

        // filtros -> quienes salen, de los cuatro sembrados
        Map<String, Set<Integer>> byFilter = new LinkedHashMap<>();
        byFilter.put("isActive=true", Set.of(healthy, left));
        byFilter.put("isActive=false", Set.of(off, both));
        byFilter.put("isAssignable=true", Set.of(healthy));
        byFilter.put("isAssignable=false", Set.of(off, left, both));
        byFilter.put("isActive=true&isAssignable=false", Set.of(left));
        byFilter.forEach((filters, ids) -> {
            var request = given().header("Authorization", "Bearer " + adminToken());
            for (String filter : filters.split("&")) {
                String[] pair = filter.split("=");
                request = request.queryParam(pair[0], pair[1]);
            }
            List<Integer> found = request.when().get("/drivers").then().statusCode(200)
                .extract().jsonPath().getList("id", Integer.class);
            Set<Integer> seeded = Set.of(healthy, off, left, both);
            assertEquals(ids, found.stream().filter(seeded::contains).collect(Collectors.toSet()), filters);
        });
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
        String token = login("sales", "Sales1234");
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
