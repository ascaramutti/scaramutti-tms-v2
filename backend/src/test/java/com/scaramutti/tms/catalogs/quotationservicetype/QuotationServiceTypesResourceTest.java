package com.scaramutti.tms.catalogs.quotationservicetype;

import com.scaramutti.tms.shared.entity.QuotationServiceType;
import com.scaramutti.tms.shared.repository.QuotationServiceTypeRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.smallrye.jwt.build.Jwt;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
class QuotationServiceTypesResourceTest {

    @Inject QuotationServiceTypeRepository quotationServiceTypeRepository;

    private static final String INACTIVE_TEST_CODE = "STT";

    /** Inserta un service type inactivo (prefijo S = SERVICIO) para tests del filtro. */
    private void seedInactiveTestQuotationServiceType() {
        QuarkusTransaction.requiringNew().run(() -> {
            if (quotationServiceTypeRepository.count("code", INACTIVE_TEST_CODE) > 0) {
                return;
            }
            QuotationServiceType inactiveTest = new QuotationServiceType();
            inactiveTest.code = INACTIVE_TEST_CODE;
            inactiveTest.name = "Servicio de Test Inactivo";
            inactiveTest.description = "Sentinel para tests del filtro isActive=false";
            inactiveTest.isActive = false;
            quotationServiceTypeRepository.persist(inactiveTest);
        });
    }

    private void cleanupInactiveTestQuotationServiceType() {
        QuarkusTransaction.requiringNew().run(() ->
            quotationServiceTypeRepository.delete("code", INACTIVE_TEST_CODE)
        );
    }

    private String login(String username, String password) {
        return given()
            .contentType(ContentType.JSON)
            .body("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}")
        .when()
            .post("/auth/login")
        .then()
            .statusCode(200)
            .extract().jsonPath().getString("token");
    }

    // ---------- Happy path / contrato ----------------------------------------

    @Test
    void list_withoutFilter_returns200AndIncludesSeedTypes() {
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/quotation-service-types")
        .then()
            .statusCode(200)
            .body("size()", greaterThanOrEqualTo(24))
            .body("code", hasItem("SCB"))
            .body("code", hasItem("ACB"))
            .body("code", hasItem("CES"))
            .body("code", hasItem("INT"));
    }

    @Test
    void list_responseShape_matchesContract() {
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/quotation-service-types")
        .then()
            .statusCode(200)
            .body("[0].id", greaterThanOrEqualTo(1))
            .body("code", everyItem(matchesPattern("^[A-Z]{2,10}$")))
            .body("name", everyItem(notNullValue()))
            .body("kind", everyItem(notNullValue()))
            .body("[0].description", notNullValue())
            .body("[0].isActive", equalTo(true));
    }

    @Test
    void list_responseIncludesKindComputedFromCodePrefix() {
        String token = login("admin", "Admin1234");

        // SCB empieza con S → kind=SERVICIO; ACB con A → ALQUILER; CES con C → COMPLEMENTARIO; INT con I → INTEGRAL.
        // Es el contrato del campo computado y se valida explícitamente.
        // El frontend depende de este campo para filtrar en memoria según el quotation_type.
        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/quotation-service-types")
        .then()
            .statusCode(200)
            .body("find { it.code == 'SCB' }.kind", equalTo("SERVICIO"))
            .body("find { it.code == 'ACB' }.kind", equalTo("ALQUILER"))
            .body("find { it.code == 'CES' }.kind", equalTo("COMPLEMENTARIO"))
            .body("find { it.code == 'INT' }.kind", equalTo("INTEGRAL"));
    }

    @Test
    void list_returnsApplicationJsonContentType() {
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/quotation-service-types")
        .then()
            .statusCode(200)
            .contentType("application/json");
    }

    @Test
    void list_resultsOrderedByCodeAscending() {
        String token = login("admin", "Admin1234");

        // Orden relativo: ACB ("A") < CES ("C") < INT ("I") < SCB ("S"). Sirve
        // como sanity check del ordering sin acoplarse a la lista completa.
        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/quotation-service-types?isActive=true")
        .then()
            .statusCode(200)
            .body("code.findAll { it in ['ACB','CES','INT','SCB'] }",
                contains("ACB", "CES", "INT", "SCB"));
    }

    // ---------- Filtro isActive ----------------------------------------------

    @Test
    void list_withIsActiveTrue_returnsOnlyActiveTypes() {
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/quotation-service-types?isActive=true")
        .then()
            .statusCode(200)
            .body("isActive", everyItem(equalTo(true)))
            .body("code", hasItem("SCB"))
            .body("code", hasItem("INT"));
    }

    @Test
    void list_withIsActiveFalse_returnsOnlyInactiveTypes() {
        seedInactiveTestQuotationServiceType();
        try {
            String token = login("admin", "Admin1234");

            given()
                .header("Authorization", "Bearer " + token)
            .when()
                .get("/quotation-service-types?isActive=false")
            .then()
                .statusCode(200)
                .body("isActive", everyItem(equalTo(false)))
                .body("code", hasItem(INACTIVE_TEST_CODE))
                .body("code", not(hasItem("SCB")))
                .body("code", not(hasItem("INT")));
        } finally {
            cleanupInactiveTestQuotationServiceType();
        }
    }

    @Test
    void list_withIsActiveTrue_excludesInactiveTypes() {
        seedInactiveTestQuotationServiceType();
        try {
            String token = login("admin", "Admin1234");

            given()
                .header("Authorization", "Bearer " + token)
            .when()
                .get("/quotation-service-types?isActive=true")
            .then()
                .statusCode(200)
                .body("code", not(hasItem(INACTIVE_TEST_CODE)));
        } finally {
            cleanupInactiveTestQuotationServiceType();
        }
    }

    // ---------- Auth ---------------------------------------------------------

    @Test
    void list_withoutToken_returns401() {
        given()
        .when()
            .get("/quotation-service-types")
        .then()
            .statusCode(401);
    }

    @Test
    void list_withMalformedToken_returns401_tokenInvalid() {
        given()
            .header("Authorization", "Bearer eyJ.malformed.token")
        .when()
            .get("/quotation-service-types")
        .then()
            .statusCode(401)
            .contentType("application/problem+json")
            .body("code", equalTo("AUTH-008"));
    }

    @Test
    void list_withExpiredToken_returns401_tokenExpired() {
        Instant past = Instant.now().minusSeconds(60);
        String expiredAccessToken = Jwt.subject("1")
            .upn("admin")
            .groups(java.util.Set.of("admin"))
            .claim("typ", "access")
            .issuedAt(past.minusSeconds(3600))
            .expiresAt(past)
            .sign();

        given()
            .header("Authorization", "Bearer " + expiredAccessToken)
        .when()
            .get("/quotation-service-types")
        .then()
            .statusCode(401)
            .contentType("application/problem+json")
            .body("code", equalTo("AUTH-007"));
    }

    @Test
    void list_isAccessibleBySalesRole() {
        String token = login("lcampos", "Sales1234");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/quotation-service-types")
        .then()
            .statusCode(200)
            .body("code", hasItem("SCB"));
    }
}
