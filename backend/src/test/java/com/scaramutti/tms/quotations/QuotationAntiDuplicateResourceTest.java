package com.scaramutti.tms.quotations;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * Test del anti-duplicado (QUO-002) AISLADO en su propia clase porque necesita
 * el {@code app.quotations.anti-duplicate-window-seconds} ACTIVO (30s).
 *
 * <p>{@code QuotationResourceTest} lo deshabilita (=0) via su propio
 * {@code @TestProfile(AntiDupDisabledProfile)} para que los tests de listado
 * puedan crear multiples cotizaciones identicas como fixtures sin chocar con el
 * 409. Aca lo reactivamos via {@link AntiDupWindowProfile}.
 */
@QuarkusTest
@TestProfile(QuotationAntiDuplicateResourceTest.AntiDupWindowProfile.class)
class QuotationAntiDuplicateResourceTest {

    public static class AntiDupWindowProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("app.quotations.anti-duplicate-window-seconds", "30");
        }
    }

    private static final int CLIENT_ID = 1;
    private static final int CURRENCY_ID = 1;
    private static final int PAYMENT_TERM_ID = 1;
    private static final int ST_SCB = 1;

    @Inject EntityManager entityManager;

    @AfterEach
    void cleanupQuotations() {
        QuarkusTransaction.requiringNew().run(() ->
            entityManager.createNativeQuery(
                "DELETE FROM cotizaciones.quotations "
                + "WHERE contact_name LIKE 'ZTEST_%' OR origin LIKE 'ZTEST_%' OR destination LIKE 'ZTEST_%'"
            ).executeUpdate()
        );
    }

    private String loginAdmin() {
        return given()
            .contentType(ContentType.JSON)
            .body("{\"username\":\"admin\",\"password\":\"Admin1234\"}")
        .when()
            .post("/auth/login")
        .then()
            .statusCode(200)
            .extract().jsonPath().getString("token");
    }

    private String transporteBody(String origin, String destination, int serviceTypeId, String unitPrice) {
        return String.format("""
            {
              "quotationType": "TRANSPORTE",
              "clientId": %d,
              "contactName": "ZTEST_CONTACT",
              "currencyId": %d,
              "paymentTermId": %d,
              "validityDays": 15,
              "origin": "%s",
              "destination": "%s",
              "items": [
                { "serviceTypeId": %d, "cargoTypeId": 1, "weightKg": 10.00, "quantity": 1, "unitPrice": %s }
              ]
            }
            """, CLIENT_ID, CURRENCY_ID, PAYMENT_TERM_ID, origin, destination, serviceTypeId, unitPrice);
    }

    @Test
    void create_sameClientAndServiceTypesWithin30s_returns409_QUO002() {
        String token = loginAdmin();
        String body = transporteBody("ZTEST_DUP1", "ZTEST_DUP2", ST_SCB, "100.00");

        // Primer POST OK.
        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(body)
        .when()
            .post("/quotations")
        .then()
            .statusCode(201);

        // Segundo POST inmediato con mismo client + mismo serviceType → 409.
        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(body)
        .when()
            .post("/quotations")
        .then()
            .statusCode(409)
            .body("code", equalTo("QUO-002"));
    }
}
