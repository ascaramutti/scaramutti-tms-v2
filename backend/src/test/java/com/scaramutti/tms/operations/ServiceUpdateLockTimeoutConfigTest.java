package com.scaramutti.tms.operations;

import com.scaramutti.tms.support.HermeticTestData;
import com.scaramutti.tms.support.OperationsTestData;
import com.scaramutti.tms.support.TestAuth;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * El tope de espera por el lock configurado en CERO tiene que fallar ruidosamente.
 *
 * <p>Existe por una trampa concreta: PostgreSQL lee {@code lock_timeout = 0} como "sin tope", o
 * sea que poner cero DESACTIVA la protección en vez de endurecerla — justo al revés de lo que
 * sugiere el número. Sin esta guarda, un cero en la configuración devolvía en silencio la espera
 * infinita que la propiedad existe para evitar, y el síntoma habría aparecido recién en
 * producción, como requests colgadas comiéndose el pool de conexiones.
 *
 * <p>Vive en su propia clase porque necesita levantar la aplicación con otra configuración.
 */
@QuarkusTest
@TestProfile(ServiceUpdateLockTimeoutConfigTest.ZeroLockTimeoutProfile.class)
class ServiceUpdateLockTimeoutConfigTest {

    public static class ZeroLockTimeoutProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("app.operations.edit-lock-timeout-seconds", "0");
        }
    }

    @Inject HermeticTestData fixtures;
    @Inject OperationsTestData operationsFixtures;

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

    /**
     * Falla, y falla como error del servidor: es un problema de configuración, no del pedido. Lo
     * que este caso descarta es el modo de falla peligroso — responder 200 con la protección
     * apagada sin que nadie se entere.
     */
    @Test
    void update_withTheLockTimeoutDisabled_failsLoudlyInsteadOfSilentlyWaitingForever() {
        long id = createService();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tentativeDate", LocalDate.now().plusDays(5).toString());
        payload.put("origin", "Piura");
        payload.put("destination", "Destino corregido");
        payload.put("weightKg", 12000);
        payload.put("price", 3200);
        payload.put("currencyId", currencyId);
        payload.put("justification", "El cliente cambió el punto de entrega");

        String etagBefore = etagOf(id);

        given()
            .header("Authorization", "Bearer " + adminToken)
            .header("If-Match", etagBefore)
            .contentType(ContentType.JSON)
            .body(payload)
        .when()
            .put("/services/" + id)
        .then()
            .statusCode(500);

        // Lo que de verdad importa: falló ANTES de tocar nada. Un 500 a secas lo daría también un
        // fallo posterior a la escritura, que dejaría el viaje editado con la protección apagada.
        given()
            .header("Authorization", "Bearer " + adminToken)
        .when()
            .get("/services/" + id)
        .then()
            .statusCode(200)
            .body("destination", equalTo("Lima"))
            .header("ETag", equalTo(etagBefore));
    }

    private long createService() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("clientId", clientId);
        payload.put("tripScope", "PROVINCIA");
        payload.put("tentativeDate", LocalDate.now().plusDays(3).toString());
        payload.put("origin", "Piura");
        payload.put("destination", "Lima");
        payload.put("cargoTypeId", cargoTypeId);
        payload.put("weightKg", 12000);
        payload.put("price", 3200);
        payload.put("currencyId", currencyId);

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

    private String etagOf(long serviceId) {
        return given()
            .header("Authorization", "Bearer " + adminToken)
        .when()
            .get("/services/" + serviceId)
        .then()
            .statusCode(200)
            .extract().header("ETag");
    }
}
