package com.scaramutti.tms.operations;

import com.scaramutti.tms.support.HermeticTestData;
import com.scaramutti.tms.support.OperationsTestData;
import com.scaramutti.tms.support.TestAuth;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Integration tests de POST /services. Hermético: cliente y tipo de carga sintéticos
 * ({@code ZTEST_}) por test, y los servicios que cuelgan de ellos se borran en el
 * {@code @AfterEach} ANTES que sus FK.
 *
 * <p>Los tokens de los casos que llegan al service se fabrican para un usuario REAL: el alta
 * resuelve el usuario autenticado para firmar el viaje, la bitácora y la auditoría.
 */
@QuarkusTest
class ServicesResourceTest {

    @Inject HermeticTestData fixtures;
    @Inject OperationsTestData operationsFixtures;
    @Inject EntityManager entityManager;

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
        fixtures.cleanup();
    }

    // ---------- Alta ----------------------------------------------------------

    @Test
    void create_returns201WithCodeStatusAndLog() {
        JsonPath body = given()
            .header("Authorization", "Bearer " + adminToken)
            .contentType(ContentType.JSON)
            .body(validPayload())
        .when()
            .post("/services")
        .then()
            .statusCode(201)
            .header("ETag", notNullValue())
            .header("Location", matchesPattern("https?://[^/]+/api/v1/services/\\d+"))
            .body("id", notNullValue())
            // El código lo deriva el backend del id: SRV- + al menos 4 dígitos.
            .body("code", matchesPattern("SRV-\\d{4,}"))
            .body("status", equalTo("PENDING_ASSIGNMENT"))
            .body("client.id", equalTo(clientId))
            .body("client.ruc", notNullValue())
            .body("cargoType.id", equalTo(cargoTypeId))
            .body("currencyCode", equalTo("PEN"))
            .body("tripScope", equalTo("PROVINCIA"))
            .body("origin", equalTo("Piura"))
            .body("widthM", nullValue())
            .body("observations", equalTo("Coordinar ingreso al puerto"))
            // La bitácora nace con una sola línea, firmada por quien registró.
            .body("events", hasSize(1))
            .body("events[0].eventType", equalTo("CREATED"))
            .body("events[0].note", equalTo("Servicio registrado"))
            .body("events[0].createdBy.username", equalTo(TestAuth.ADMIN_USERNAME))
            .body("createdBy.username", equalTo(TestAuth.ADMIN_USERNAME))
            .body("createdAt", notNullValue())
            .body("updatedAt", notNullValue())
            .extract().jsonPath();

        // Los numeros se comparan por VALOR: el JSON los emite sin la escala de la columna
        // (28000, no 28000.00), asi que atarse al texto haria fragil la asercion.
        assertEquals(28000f, body.getFloat("weightKg"));
        assertEquals(12.5f, body.getFloat("lengthM"));
        assertEquals(5800f, body.getFloat("price"));
    }

    /** El alta deja su registro de auditoría, que todavía no se expone por la API. */
    @Test
    void create_writesItsAuditLog() {
        long serviceId = createService(validPayload());

        Number logs = (Number) entityManager.createNativeQuery(
                "SELECT count(*) FROM operaciones.service_audit_logs "
                    + "WHERE service_id = ?1 AND change_type = 'CREATED'")
            .setParameter(1, serviceId).getSingleResult();
        assertEquals(1, logs.intValue());
    }

    /** RN-OP9: la fecha tentativa pasada es legítima (registro retroactivo). */
    @Test
    void create_withPastTentativeDate_returns201() {
        Map<String, Object> payload = validPayload();
        payload.put("tentativeDate", LocalDate.now().minusMonths(2).toString());

        given()
            .header("Authorization", "Bearer " + adminToken)
            .contentType(ContentType.JSON)
            .body(payload)
        .when()
            .post("/services")
        .then()
            .statusCode(201);
    }

    /** El código sale del id, así que dos altas nunca comparten el mismo. */
    @Test
    void create_twoServices_getDistinctCodes() {
        Map<String, Object> first = validPayload();
        Map<String, Object> second = validPayload();
        second.put("destination", "Trujillo");

        String firstCode = createAndReturnCode(first);
        String secondCode = createAndReturnCode(second);

        assertNotEquals(firstCode, secondCode);
    }

    // ---------- Guarda anti doble-click (OPS-007) -----------------------------

    @Test
    void create_sameClientRouteAndUserTwice_returns409_OPS007() {
        Map<String, Object> payload = validPayload();
        createService(payload);

        given()
            .header("Authorization", "Bearer " + adminToken)
            .contentType(ContentType.JSON)
            .body(payload)
        .when()
            .post("/services")
        .then()
            .statusCode(409)
            .contentType("application/problem+json")
            .body("code", equalTo("OPS-007"))
            .body("detail", containsString("idéntico"));
    }

    /** No es unicidad: el mismo cliente con OTRA ruta es un viaje distinto, no un duplicado. */
    @Test
    void create_sameClientDifferentRoute_returns201() {
        createService(validPayload());

        Map<String, Object> otherRoute = validPayload();
        otherRoute.put("destination", "Arequipa");

        given()
            .header("Authorization", "Bearer " + adminToken)
            .contentType(ContentType.JSON)
            .body(otherRoute)
        .when()
            .post("/services")
        .then()
            .statusCode(201);
    }

    // ---------- Catálogos inexistentes o inactivos ----------------------------

    @Test
    void create_withUnknownClient_returns400_COM001() {
        Map<String, Object> payload = validPayload();
        payload.put("clientId", 999_999);

        postExpectingValidationError(payload, "cliente");
    }

    @Test
    void create_withInactiveClient_returns400_COM001() {
        QuarkusTransaction.requiringNew().run(() -> entityManager.createNativeQuery(
                "UPDATE public.clients SET is_active = false WHERE id = ?1")
            .setParameter(1, clientId).executeUpdate());

        postExpectingValidationError(validPayload(), "cliente");
    }

    /** El mismo formulario enviado por OTRO usuario no es un doble-click: es otro registro. */
    @Test
    void create_sameClientAndRouteByAnotherUser_returns201() {
        createService(validPayload());
        int salesUserId = fixtures.userId("lcampos");

        given()
            .header("Authorization", "Bearer " + TestAuth.fabricateTokenForUser(salesUserId, "lcampos", "sales"))
            .contentType(ContentType.JSON)
            .body(validPayload())
        .when()
            .post("/services")
        .then()
            .statusCode(201);
    }

    @Test
    void create_withUnknownCargoType_returns400_COM001() {
        Map<String, Object> payload = validPayload();
        payload.put("cargoTypeId", 999_999);

        postExpectingValidationError(payload, "tipo de carga");
    }

    @Test
    void create_withUnknownCurrency_returns400_COM001() {
        Map<String, Object> payload = validPayload();
        payload.put("currencyId", 999_999);

        postExpectingValidationError(payload, "moneda");
    }

    @Test
    void create_withInactiveCargoType_returns400_COM001() {
        QuarkusTransaction.requiringNew().run(() -> entityManager.createNativeQuery(
                "UPDATE public.cargo_types SET is_active = false WHERE id = ?1")
            .setParameter(1, cargoTypeId).executeUpdate());

        postExpectingValidationError(validPayload(), "tipo de carga");
    }

    @Test
    void create_withInactiveCurrency_returns400_COM001() {
        QuarkusTransaction.requiringNew().run(() -> entityManager.createNativeQuery(
                "UPDATE public.currencies SET is_active = false WHERE id = ?1")
            .setParameter(1, currencyId).executeUpdate());
        try {
            postExpectingValidationError(validPayload(), "moneda");
        } finally {
            // La moneda es un catálogo compartido y sembrado: se restituye siempre.
            QuarkusTransaction.requiringNew().run(() -> entityManager.createNativeQuery(
                    "UPDATE public.currencies SET is_active = true WHERE id = ?1")
                .setParameter(1, currencyId).executeUpdate());
        }
    }

    // ---------- Validación del body ------------------------------------------

    @Test
    void create_withoutRequiredFields_returns400WithFieldErrors() {
        Map<String, Object> payload = validPayload();
        payload.remove("origin");
        payload.remove("clientId");

        given()
            .header("Authorization", "Bearer " + adminToken)
            .contentType(ContentType.JSON)
            .body(payload)
        .when()
            .post("/services")
        .then()
            .statusCode(400)
            .contentType("application/problem+json")
            .body("code", equalTo("COM-001"))
            .body("errors.size()", greaterThanOrEqualTo(2));
    }

    @Test
    void create_withNonPositiveWeight_returns400() {
        Map<String, Object> payload = validPayload();
        payload.put("weightKg", 0);

        given()
            .header("Authorization", "Bearer " + adminToken)
            .contentType(ContentType.JSON)
            .body(payload)
        .when()
            .post("/services")
        .then()
            .statusCode(400)
            .body("code", equalTo("COM-001"));
    }

    /** El origen y el destino se guardan sin espacios de sobra: alimentan búsqueda y duplicados. */
    @Test
    void create_trimsOriginAndDestination() {
        Map<String, Object> payload = validPayload();
        payload.put("origin", "  Piura  ");
        payload.put("destination", " Lima ");

        given()
            .header("Authorization", "Bearer " + adminToken)
            .contentType(ContentType.JSON)
            .body(payload)
        .when()
            .post("/services")
        .then()
            .statusCode(201)
            .body("origin", equalTo("Piura"))
            .body("destination", equalTo("Lima"));
    }

    /**
     * Un origen de solo espacios se rechaza ANTES del trim: si el campo dejara de ser
     * obligatorio, la normalización lo volvería null y reventaría contra la columna.
     */
    @Test
    void create_withBlankOrigin_returns400() {
        Map<String, Object> payload = validPayload();
        payload.put("origin", "   ");

        given()
            .header("Authorization", "Bearer " + adminToken)
            .contentType(ContentType.JSON)
            .body(payload)
        .when()
            .post("/services")
        .then()
            .statusCode(400)
            .body("code", equalTo("COM-001"));
    }

    /** Con los textos ya normalizados, el reenvío con espacios distintos sigue siendo el mismo. */
    @Test
    void create_sameRouteWithDifferentSpacing_returns409_OPS007() {
        createService(validPayload());

        Map<String, Object> spaced = validPayload();
        spaced.put("origin", "  Piura ");

        given()
            .header("Authorization", "Bearer " + adminToken)
            .contentType(ContentType.JSON)
            .body(spaced)
        .when()
            .post("/services")
        .then()
            .statusCode(409)
            .body("code", equalTo("OPS-007"));
    }

    /** Un viaje siempre tiene precio: el alta rechaza el cero. */
    @Test
    void create_withZeroPrice_returns400() {
        Map<String, Object> payload = validPayload();
        payload.put("price", 0);

        given()
            .header("Authorization", "Bearer " + adminToken)
            .contentType(ContentType.JSON)
            .body(payload)
        .when()
            .post("/services")
        .then()
            .statusCode(400)
            .body("code", equalTo("COM-001"));
    }

    @Test
    void create_withNullBody_returns400() {
        given()
            .header("Authorization", "Bearer " + adminToken)
            .contentType(ContentType.JSON)
            .body("null")
        .when()
            .post("/services")
        .then()
            .statusCode(400);
    }

    // ---------- Autorización --------------------------------------------------

    /** El despacho opera viajes ajenos, no los registra (misma regla que el sistema anterior). */
    @Test
    void create_asDispatcher_returns403_COM003() {
        given()
            .header("Authorization", "Bearer " + TestAuth.fabricateAccessToken("zdispatcher", "dispatcher"))
            .contentType(ContentType.JSON)
            .body(validPayload())
        .when()
            .post("/services")
        .then()
            .statusCode(403)
            .body("code", equalTo("COM-003"));
    }

    @Test
    void create_asWarehouseKeeper_returns403() {
        given()
            .header("Authorization", "Bearer " + TestAuth.fabricateAccessToken("zkeeper", "warehouse_keeper"))
            .contentType(ContentType.JSON)
            .body(validPayload())
        .when()
            .post("/services")
        .then()
            .statusCode(403);
    }

    @Test
    void create_asSales_returns201() {
        int salesUserId = fixtures.userId("lcampos");

        given()
            .header("Authorization", "Bearer " + TestAuth.fabricateTokenForUser(salesUserId, "lcampos", "sales"))
            .contentType(ContentType.JSON)
            .body(validPayload())
        .when()
            .post("/services")
        .then()
            .statusCode(201)
            .body("createdBy.username", equalTo("lcampos"));
    }

    @Test
    void create_withoutToken_returns401() {
        given()
            .contentType(ContentType.JSON)
            .body(validPayload())
        .when()
            .post("/services")
        .then()
            .statusCode(401);
    }

    // ---------- Helpers -------------------------------------------------------

    /** Payload mínimo válido; cada test lo ajusta a su caso. */
    private Map<String, Object> validPayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("clientId", clientId);
        payload.put("tripScope", "PROVINCIA");
        payload.put("tentativeDate", LocalDate.now().plusDays(3).toString());
        payload.put("origin", "Piura");
        payload.put("destination", "Lima");
        payload.put("cargoTypeId", cargoTypeId);
        payload.put("weightKg", 28000);
        payload.put("lengthM", 12.5);
        payload.put("price", 5800);
        payload.put("currencyId", currencyId);
        payload.put("observations", "Coordinar ingreso al puerto");
        return payload;
    }

    private long createService(Map<String, Object> payload) {
        return given()
            .header("Authorization", "Bearer " + adminToken)
            .contentType(ContentType.JSON)
            .body(payload)
        .when()
            .post("/services")
        .then()
            .statusCode(201)
            .extract().jsonPath().getLong("id");
    }

    private String createAndReturnCode(Map<String, Object> payload) {
        return given()
            .header("Authorization", "Bearer " + adminToken)
            .contentType(ContentType.JSON)
            .body(payload)
        .when()
            .post("/services")
        .then()
            .statusCode(201)
            .extract().jsonPath().getString("code");
    }

    private void postExpectingValidationError(Map<String, Object> payload, String detailFragment) {
        given()
            .header("Authorization", "Bearer " + adminToken)
            .contentType(ContentType.JSON)
            .body(payload)
        .when()
            .post("/services")
        .then()
            .statusCode(400)
            .contentType("application/problem+json")
            .body("code", equalTo("COM-001"))
            .body("detail", allOf(containsString("no existe"), containsString(detailFragment)));
    }
}
