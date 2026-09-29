package com.scaramutti.tms.operations;

import com.scaramutti.tms.support.HermeticTestData;
import com.scaramutti.tms.support.OperationsTestData;
import com.scaramutti.tms.support.TestAuth;
import com.scaramutti.tms.support.WarehouseTestData;
import com.scaramutti.tms.shared.util.DateUtils;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import jakarta.inject.Inject;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Las alertas de reasignacion de un viaje pendiente de inicio o en ruta: la del conductor principal
 * (trabajador dado de baja, ficha apagada o cargo distinto de conductor), la de cada refuerzo (solo en
 * el detalle) y la del viaje, que junta las dos. Se derivan en cada lectura; la del viaje viaja en el
 * listado y en el detalle, y la del principal y las de los refuerzos, solo en el detalle.
 */
@QuarkusTest
class ServiceDriverAlertTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Inject HermeticTestData fixtures;
    @Inject OperationsTestData operationsFixtures;
    @Inject WarehouseTestData warehouseFixtures;

    private int clientId;
    private int cargoTypeId;
    private int currencyId;
    private String adminToken;

    @BeforeEach
    void setUp() {
        clientId = fixtures.seedClient();
        cargoTypeId = fixtures.seedCargoType();
        currencyId = fixtures.currencyId("PEN");
        adminToken = TestAuth.adminToken();
    }

    @AfterEach
    void cleanup() {
        operationsFixtures.deleteTestServices();
        QuarkusTransaction.requiringNew().run(() -> {
            operationsFixtures.deleteTestDrivers();
            warehouseFixtures.deleteTestFleet();
            warehouseFixtures.deleteTestWorkers();
        });
        fixtures.cleanup();
    }

    /** Un conductor sano, o uno ya no asignable por el motivo pedido. */
    private int driverWith(String motive) {
        if ("escolta".equals(motive)) {
            return operationsFixtures.seedDriverOfRole("ZTEST Alerta", "Escolta", "escort", true);
        }
        int driver = operationsFixtures.seedDriver("ZTEST Alerta", "Conductor");
        disable(motive, driver);
        return driver;
    }

    private void disable(String motive, int driver) {
        switch (motive) {
            case "trabajador_de_baja" -> warehouseFixtures.setWorkerActive(operationsFixtures.workerIdOfDriver(driver), false);
            case "ficha_apagada" -> operationsFixtures.setDriverActive(driver, false);
            case "cargo_distinto" -> warehouseFixtures.setWorkerRole(operationsFixtures.workerIdOfDriver(driver), "operator");
            case "ninguno" -> { }
            default -> throw new IllegalArgumentException(motive);
        }
    }

    /** Un viaje en el estado pedido, con ese conductor principal (o ninguno). */
    private long trip(String status, Integer driver) {
        long id = createService("ZTEST Alerta " + SEQ.incrementAndGet());
        if (!"PENDING_ASSIGNMENT".equals(status)) {
            operationsFixtures.forceServiceStatus(id, status);
        }
        if (driver != null) {
            operationsFixtures.forceServiceResources(id, driver, operationsFixtures.seedTractor(), null);
        }
        return id;
    }

    private JsonPath detail(long id) {
        return given().header("Authorization", "Bearer " + adminToken).when().get("/services/" + id)
            .then().statusCode(200).extract().jsonPath();
    }

    /**
     * La alerta del viaje en el listado, pidiendo la fila por su origen y su estado (los eliminados,
     * solo asi). Sin refuerzos, es la del conductor principal: el listado no trae la marca aparte.
     */
    private boolean listedAlert(long id, String status) {
        return listed(id, status, "needsReassignment", adminToken);
    }

    private boolean listed(long id, String status, String field, String token) {
        JsonPath page = given().header("Authorization", "Bearer " + token)
            .queryParam("q", origin(id)).queryParam("status", status)
            .when().get("/services").then().statusCode(200).extract().jsonPath();
        // Por id y no por posicion: la busqueda por origen puede traer tambien otro viaje de la corrida
        List<Integer> ids = page.getList("content.id", Integer.class);
        assertEquals(1, ids.stream().filter(listedId -> listedId == id).count(), "el viaje sale una vez en su estado");
        // La marca del conductor es del detalle: el listado no la trae, ni siquiera en false
        Map<String, Object> row = page.getMap("content.find { it.id == " + id + " }");
        assertEquals(false, row.containsKey("driverNeedsReassignment"), "el listado no trae la marca del conductor");
        return page.getBoolean("content.find { it.id == " + id + " }." + field);
    }

    private String origin(long id) {
        return detail(id).getString("origin");
    }

    static Stream<Arguments> openStatusAndMotive() {
        return Stream.of("PENDING_START", "IN_PROGRESS").flatMap(status ->
            Stream.of("trabajador_de_baja", "ficha_apagada", "cargo_distinto", "escolta", "ninguno")
                .map(motive -> Arguments.of(status, motive)));
    }

    /**
     * Pendiente de inicio y en ruta: la alerta sale con cada motivo y no sale con un conductor sano,
     * la del conductor en el detalle y la del viaje en el listado.
     */
    @ParameterizedTest(name = "{0}, {1}")
    @MethodSource("openStatusAndMotive")
    void anOpenTrip_carriesTheAlert_exactlyWhenItsDriverIsNoLongerAssignable(String status, String motive) {
        long id = trip(status, driverWith(motive));
        boolean expected = !"ninguno".equals(motive);

        assertEquals(expected, detail(id).getBoolean("driverNeedsReassignment"), "en el detalle");
        assertEquals(expected, listedAlert(id, status), "en el listado");
    }

    /** Completado, cancelado o eliminado son historia: no se reasignan, aunque el conductor se haya ido. */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"COMPLETED", "CANCELLED", "DELETED"})
    void aClosedTrip_neverCarriesTheAlert(String status) {
        long id = trip(status, driverWith("trabajador_de_baja"));

        assertEquals(false, detail(id).getBoolean("driverNeedsReassignment"), "en el detalle");
        assertEquals(false, listedAlert(id, status), "en el listado");
    }

    /** Pendiente de asignacion: no tiene conductor, asi que no hay nada que reasignar. Presente y false. */
    @Test
    void aTripWithoutDriver_carriesAFalseAlert() {
        long id = trip("PENDING_ASSIGNMENT", null);

        assertEquals(false, detail(id).getBoolean("driverNeedsReassignment"));
        assertEquals(false, listedAlert(id, "PENDING_ASSIGNMENT"));
    }

    /** Ida y vuelta: se deriva en cada lectura, asi que dar de baja la prende y reactivar la apaga. */
    @Test
    void theAlert_followsTheWorker_there_andBack() {
        int driver = driverWith("ninguno");
        long id = trip("IN_PROGRESS", driver);
        int worker = operationsFixtures.workerIdOfDriver(driver);
        assertEquals(false, detail(id).getBoolean("driverNeedsReassignment"), "sano");

        warehouseFixtures.setWorkerActive(worker, false);
        assertEquals(true, detail(id).getBoolean("driverNeedsReassignment"), "dado de baja");
        assertEquals(true, listedAlert(id, "IN_PROGRESS"));

        warehouseFixtures.setWorkerActive(worker, true);
        assertEquals(false, detail(id).getBoolean("driverNeedsReassignment"), "reactivado");
        assertEquals(false, listedAlert(id, "IN_PROGRESS"));
    }

    /**
     * La alerta llega igual a despacho y a ventas, que leen viajes sin leer trabajadores: es a
     * despacho a quien le sirve, y la rama de precios por rol no puede tocarla.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"dispatcher", "sales"})
    void theAlert_reachesTheRolesThatReadTrips(String role) {
        long id = trip("IN_PROGRESS", driverWith("trabajador_de_baja"));
        String token = TestAuth.fabricateAccessToken("ztest" + role, role);

        assertEquals(true, given().header("Authorization", "Bearer " + token).when().get("/services/" + id)
            .then().statusCode(200).extract().jsonPath().getBoolean("driverNeedsReassignment"), "en el detalle");
        assertEquals(true, listed(id, "IN_PROGRESS", "needsReassignment", token), "en el listado, la del viaje");
    }

    /**
     * Reabrir restaura el conductor sin revalidarlo: un viaje en ruta que se cancela y se reabre con
     * su conductor ya dado de baja vuelve con la alerta, en la misma respuesta de la reapertura.
     */
    @Test
    void reopeningATripWhoseDriverLeft_answersWithTheAlert() {
        long id = trip("IN_PROGRESS", driverWith("trabajador_de_baja"));
        operationsFixtures.forceServiceDates(id, java.time.OffsetDateTime.parse("2026-07-01T10:00:00Z"), null);

        transition(id, "CANCELLED", "El cliente reprogramó el embarque para octubre");
        JsonPath reopened = transition(id, "REOPENED", "El cliente retomó el embarque que había reprogramado");

        assertEquals("IN_PROGRESS", reopened.getString("status"));
        assertEquals(true, reopened.getBoolean("driverNeedsReassignment"), "en la respuesta de la reapertura");
    }

    private JsonPath transition(long id, String target, String note) {
        String etag = given().header("Authorization", "Bearer " + adminToken).when().get("/services/" + id)
            .then().statusCode(200).extract().header("ETag");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("target", target);
        body.put("note", note);
        return given().header("Authorization", "Bearer " + adminToken).header("If-Match", etag)
            .contentType(ContentType.JSON).body(body).when().post("/services/" + id + "/status")
            .then().statusCode(200).extract().jsonPath();
    }

    /**
     * Por refuerzo, en el detalle: el conductor de un refuerzo dado de baja lo marca a EL, no al
     * viaje; uno sano no; y un refuerzo sin conductor, tampoco. Con el viaje cerrado, ninguno.
     */
    @Test
    void eachReinforcement_carriesItsOwnAlert_whileTheTripIsOpen() {
        long id = trip("IN_PROGRESS", driverWith("ninguno"));
        // Los tres motivos, uno por refuerzo: la consulta de refuerzos tiene sus propias uniones.
        operationsFixtures.seedAdditionalAssignment(id, driverWith("trabajador_de_baja"), null, null, "ZTEST relevo 1");
        operationsFixtures.seedAdditionalAssignment(id, driverWith("ficha_apagada"), null, null, "ZTEST relevo 2");
        operationsFixtures.seedAdditionalAssignment(id, driverWith("cargo_distinto"), null, null, "ZTEST relevo 3");
        operationsFixtures.seedAdditionalAssignment(id, driverWith("ninguno"), null, null, "ZTEST relevo 4");
        operationsFixtures.seedAdditionalAssignment(id, null, operationsFixtures.seedTractor(), null, "ZTEST tracto");

        JsonPath open = detail(id);
        assertEquals(false, open.getBoolean("driverNeedsReassignment"), "el principal esta sano");
        assertEquals(List.of(true, true, true, false, false), open.getList("additionalResources.driverNeedsReassignment"));

        operationsFixtures.forceServiceStatus(id, "COMPLETED");
        assertEquals(List.of(false, false, false, false, false),
            detail(id).getList("additionalResources.driverNeedsReassignment"));
    }

    // ---------- La alerta del VIAJE: el principal o algun refuerzo ----------

    /** El campo del viaje, en el detalle y en el listado, con las dos lecturas que se esperan iguales. */
    private void assertTripAlert(boolean expected, long id, String status, String why) {
        assertEquals(expected, detail(id).getBoolean("needsReassignment"), why + ", en el detalle");
        assertEquals(expected, listed(id, status, "needsReassignment", adminToken), why + ", en el listado");
    }

    static Stream<Arguments> openStatusAndBadMotive() {
        return Stream.of("PENDING_START", "IN_PROGRESS").flatMap(status ->
            Stream.of("trabajador_de_baja", "ficha_apagada", "cargo_distinto", "escolta")
                .map(motive -> Arguments.of(status, motive)));
    }

    /** Solo el principal: el viaje se marca igual que su conductor, sin refuerzos de por medio. */
    @ParameterizedTest(name = "{0}, {1}")
    @MethodSource("openStatusAndBadMotive")
    void theTrip_isFlagged_byItsMainDriver(String status, String motive) {
        long id = trip(status, driverWith(motive));

        assertTripAlert(true, id, status, "principal no asignable");
    }

    /**
     * Solo un refuerzo, con el principal sano: el viaje se marca aunque su conductor no (la marca del
     * principal, en el detalle, sigue en false).
     */
    @ParameterizedTest(name = "{0}, {1}")
    @MethodSource("openStatusAndBadMotive")
    void theTrip_isFlagged_byOneReinforcementAlone(String status, String motive) {
        long id = trip(status, driverWith("ninguno"));
        operationsFixtures.seedAdditionalAssignment(id, driverWith("ninguno"), null, null, "ZTEST relevo sano");
        operationsFixtures.seedAdditionalAssignment(id, driverWith(motive), null, null, "ZTEST relevo marcado");

        assertTripAlert(true, id, status, "un refuerzo no asignable");
        assertEquals(false, detail(id).getBoolean("driverNeedsReassignment"), "el principal sigue sano");
    }

    @Test
    void theTrip_isFlagged_whenBothTheMainDriverAndAReinforcementAreNot() {
        long id = trip("IN_PROGRESS", driverWith("trabajador_de_baja"));
        operationsFixtures.seedAdditionalAssignment(id, driverWith("ficha_apagada"), null, null, "ZTEST relevo");

        assertTripAlert(true, id, "IN_PROGRESS", "los dos");
    }

    /** Todos sanos, y un refuerzo sin conductor (solo tracto), que no cuenta: el viaje no se marca. */
    @Test
    void theTrip_isNotFlagged_whenEveryDriverIsAssignable() {
        long id = trip("IN_PROGRESS", driverWith("ninguno"));
        operationsFixtures.seedAdditionalAssignment(id, driverWith("ninguno"), null, null, "ZTEST relevo sano");
        operationsFixtures.seedAdditionalAssignment(id, null, operationsFixtures.seedTractor(), null, "ZTEST tracto");

        assertTripAlert(false, id, "IN_PROGRESS", "todos sanos");
    }

    /** Pendiente de asignacion: sin recursos, presente y false (tambien en el 201 del alta). */
    @Test
    void aTripWithoutResources_isNotFlagged() {
        long id = trip("PENDING_ASSIGNMENT", null);

        assertTripAlert(false, id, "PENDING_ASSIGNMENT", "sin recursos");
    }

    /**
     * Pendiente de asignacion con recursos puestos a mano (la API no llega ahi): el estado decide solo,
     * y no se marca aunque el principal y un refuerzo ya no sean asignables.
     */
    @Test
    void aTripPendingAssignment_isNotFlagged_evenWithResourcesThatAreNotAssignable() {
        long id = trip("PENDING_ASSIGNMENT", driverWith("trabajador_de_baja"));
        operationsFixtures.seedAdditionalAssignment(id, driverWith("ficha_apagada"), null, null, "ZTEST relevo");

        assertTripAlert(false, id, "PENDING_ASSIGNMENT", "pendiente de asignacion");
    }

    /** Cerrado: historia, aunque el principal y un refuerzo ya no sean asignables. */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"COMPLETED", "CANCELLED", "DELETED"})
    void aClosedTrip_isNeverFlagged_evenWithAReinforcementThatIsNotAssignable(String status) {
        long id = trip("IN_PROGRESS", driverWith("trabajador_de_baja"));
        operationsFixtures.seedAdditionalAssignment(id, driverWith("ficha_apagada"), null, null, "ZTEST relevo");
        operationsFixtures.forceServiceStatus(id, status);

        assertTripAlert(false, id, status, "cerrado");
    }

    /**
     * La subconsulta mira los refuerzos de SU viaje: un refuerzo no asignable de otro viaje no marca
     * a este, y el viaje que si lo tiene sale marcado en la misma pagina.
     */
    @Test
    void aReinforcementOfAnotherTrip_doesNotFlagThisOne() {
        long healthy = trip("IN_PROGRESS", driverWith("ninguno"));
        long other = trip("IN_PROGRESS", driverWith("ninguno"));
        operationsFixtures.seedAdditionalAssignment(other, driverWith("trabajador_de_baja"), null, null, "ZTEST relevo");

        assertTripAlert(false, healthy, "IN_PROGRESS", "el viaje sano");
        assertTripAlert(true, other, "IN_PROGRESS", "el viaje con el refuerzo");
    }

    /** Llega igual a todos los roles que leen viajes, en el listado y en el detalle. */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"dispatcher", "sales", "general_manager", "operations_manager"})
    void theTripAlert_reachesTheRolesThatReadTrips(String role) {
        long id = trip("IN_PROGRESS", driverWith("ninguno"));
        operationsFixtures.seedAdditionalAssignment(id, driverWith("trabajador_de_baja"), null, null, "ZTEST relevo");
        String token = TestAuth.fabricateAccessToken("ztest" + role, role);

        assertEquals(true, given().header("Authorization", "Bearer " + token).when().get("/services/" + id)
            .then().statusCode(200).extract().jsonPath().getBoolean("needsReassignment"), "en el detalle");
        assertEquals(true, listed(id, "IN_PROGRESS", "needsReassignment", token), "en el listado");
    }

    private long createService(String origin) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("clientId", clientId);
        payload.put("tripScope", "PROVINCIA");
        payload.put("tentativeDate", LocalDate.now(DateUtils.LIMA).plusDays(3).toString());
        payload.put("origin", origin);
        payload.put("destination", "Lima");
        payload.put("cargoTypeId", cargoTypeId);
        payload.put("weightKg", 12000);
        payload.put("price", 3200);
        payload.put("currencyId", currencyId);
        JsonPath created = given().header("Authorization", "Bearer " + adminToken).contentType(ContentType.JSON)
            .body(payload).when().post("/services").then().statusCode(201).extract().jsonPath();
        // El alta es la unica salida que no se relee: nace pendiente de asignacion, sin conductor.
        assertEquals(false, created.getBoolean("driverNeedsReassignment"), "el 201 del alta");
        assertEquals(false, created.getBoolean("needsReassignment"), "el 201 del alta, el viaje");
        return created.getLong("id");
    }
}
