package com.scaramutti.tms.quotations;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.smallrye.jwt.build.Jwt;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.matchesRegex;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests del endpoint GET /quotations/{id}/pdf (downloadQuotationPdf):
 * 200 con bytes PDF + content-type, {@code ?preview} inline vs attachment,
 * ETag/Last-Modified, 304 con If-None-Match, 404, y la matriz de roles.
 *
 * <p>El render del PDF en si (formato, monto en letras, jerarquia) se valida en
 * {@link com.scaramutti.tms.quotations.pdf.QuotationPdfServiceTest}; aca el foco
 * es el contrato HTTP del endpoint.
 */
@QuarkusTest
@TestProfile(QuotationPdfResourceTest.AntiDupDisabledProfile.class)
class QuotationPdfResourceTest {

    /** Igual que QuotationResourceTest: desactiva el anti-duplicate window para crear fixtures. */
    public static class AntiDupDisabledProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("app.quotations.anti-duplicate-window-seconds", "0");
        }
    }

    @Inject EntityManager entityManager;

    private static final int CLIENT_ID = 1;
    private static final int CURRENCY_ID = 1;
    private static final int PAYMENT_TERM_ID = 1;
    private static final int ST_SCB = 1;

    @AfterEach
    void cleanupQuotations() {
        QuarkusTransaction.requiringNew().run(() -> {
            entityManager.createNativeQuery(
                "DELETE FROM cotizaciones.quotations "
                + "WHERE contact_name LIKE 'ZTEST_%' "
                + "   OR origin LIKE 'ZTEST_%' "
                + "   OR destination LIKE 'ZTEST_%'"
            ).executeUpdate();
        });
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

    private String fabricateAccessToken(String username, String role) {
        Instant now = Instant.now();
        return Jwt.subject("999")
            .upn(username)
            .groups(Set.of(role))
            .claim("typ", "access")
            .issuedAt(now)
            .expiresAt(now.plusSeconds(3600))
            .sign();
    }

    private String transporteBody(String origin) {
        return String.format("""
            {
              "quotationType": "TRANSPORTE",
              "clientId": %d,
              "contactName": "ZTEST_PDF",
              "contactPhone": "987654321",
              "currencyId": %d,
              "paymentTermId": %d,
              "validityDays": 15,
              "origin": "%s",
              "destination": "ZTEST_DEST",
              "items": [
                { "serviceTypeId": %d, "cargoTypeId": 1, "weightKg": 10.00, "quantity": 1, "unitPrice": 1000.00 }
              ]
            }
            """, CLIENT_ID, CURRENCY_ID, PAYMENT_TERM_ID, origin, ST_SCB);
    }

    private long createQuotation(String token, String origin) {
        return given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(transporteBody(origin))
        .when()
            .post("/quotations")
        .then()
            .statusCode(201)
            .extract().jsonPath().getLong("id");
    }

    // ---------- 200 + PDF bytes ----------------------------------------------

    @Test
    void download_existingQuotation_returns200WithPdfBytes() {
        String token = loginAdmin();
        long id = createQuotation(token, "ZTEST_PDF_OK");

        byte[] pdf = given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/quotations/" + id + "/pdf")
        .then()
            .statusCode(200)
            .contentType("application/pdf")
            .header("Content-Disposition", containsString("cotizacion-"))
            .extract().asByteArray();

        assertTrue(pdf.length > 1000, "el PDF deberia tener contenido");
        assertEquals("%PDF", new String(pdf, 0, 4), "deberia empezar con la firma %PDF");
    }

    // ---------- preview: inline vs attachment --------------------------------

    @Test
    void download_default_returnsAttachmentDisposition() {
        String token = loginAdmin();
        long id = createQuotation(token, "ZTEST_PDF_ATT");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/quotations/" + id + "/pdf")
        .then()
            .statusCode(200)
            .header("Content-Disposition", containsString("attachment"));
    }

    @Test
    void download_withPreviewTrue_returnsInlineDisposition() {
        String token = loginAdmin();
        long id = createQuotation(token, "ZTEST_PDF_PREV");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/quotations/" + id + "/pdf?preview=true")
        .then()
            .statusCode(200)
            .contentType("application/pdf")
            .header("Content-Disposition", containsString("inline"));
    }

    // ---------- ETag / Last-Modified / 304 -----------------------------------

    @Test
    void download_includesETagAndLastModifiedHeaders() {
        String token = loginAdmin();
        long id = createQuotation(token, "ZTEST_PDF_ETAG");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/quotations/" + id + "/pdf")
        .then()
            .statusCode(200)
            .header("ETag", notNullValue())
            .header("ETag", matchesRegex("\".+\""))
            .header("Last-Modified", notNullValue())
            // Sin no-cache el browser sirve el PDF viejo sin revalidar el ETag
            // (preview desactualizado tras editar la cotización).
            .header("Cache-Control", equalTo("private, no-cache"));
    }

    @Test
    void download_withMatchingIfNoneMatch_returns304() {
        String token = loginAdmin();
        long id = createQuotation(token, "ZTEST_PDF_304");

        String etag = given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/quotations/" + id + "/pdf")
        .then()
            .statusCode(200)
            .extract().header("ETag");

        given()
            .header("Authorization", "Bearer " + token)
            .header("If-None-Match", etag)
        .when()
            .get("/quotations/" + id + "/pdf")
        .then()
            .statusCode(304)
            .header("ETag", equalTo(etag))
            .header("Cache-Control", equalTo("private, no-cache"));
    }

    @Test
    void download_withStaleIfNoneMatch_returns200AndRegenerates() {
        String token = loginAdmin();
        long id = createQuotation(token, "ZTEST_PDF_STALE");

        given()
            .header("Authorization", "Bearer " + token)
            .header("If-None-Match", "\"2020-01-01T00:00:00Z\"")
        .when()
            .get("/quotations/" + id + "/pdf")
        .then()
            .statusCode(200)
            .contentType("application/pdf");
    }

    // ---------- 404 ----------------------------------------------------------

    @Test
    void download_nonExistentId_returns404_QUO003() {
        String token = loginAdmin();

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/quotations/999999999/pdf")
        .then()
            .statusCode(404)
            .contentType("application/problem+json")
            .body("code", equalTo("QUO-003"));
    }

    // ---------- Auth: 401 / 403 ----------------------------------------------

    @Test
    void download_withoutToken_returns401() {
        given()
        .when()
            .get("/quotations/1/pdf")
        .then()
            .statusCode(401);
    }

    @Test
    void download_withDispatcherRole_returns403_COM003() {
        String token = fabricateAccessToken("disp_test", "dispatcher");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/quotations/1/pdf")
        .then()
            .statusCode(403)
            .contentType("application/problem+json")
            .body("code", equalTo("COM-003"));
    }

    // ---------- Roles permitidos ---------------------------------------------

    @Test
    void download_withSalesRole_returns200() {
        String adminToken = loginAdmin();
        long id = createQuotation(adminToken, "ZTEST_PDF_SALES");

        String salesToken = given()
            .contentType(ContentType.JSON)
            .body("{\"username\":\"lcampos\",\"password\":\"Sales1234\"}")
        .when()
            .post("/auth/login")
        .then()
            .statusCode(200)
            .extract().jsonPath().getString("token");

        given()
            .header("Authorization", "Bearer " + salesToken)
        .when()
            .get("/quotations/" + id + "/pdf")
        .then()
            .statusCode(200)
            .contentType("application/pdf");
    }

    @Test
    void download_withOperationsManagerRole_returns200() {
        String adminToken = loginAdmin();
        long id = createQuotation(adminToken, "ZTEST_PDF_OPS");

        String token = fabricateAccessToken("ops_test", "operations_manager");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/quotations/" + id + "/pdf")
        .then()
            .statusCode(200);
    }
}
