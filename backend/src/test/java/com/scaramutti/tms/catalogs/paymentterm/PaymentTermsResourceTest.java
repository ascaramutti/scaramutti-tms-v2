package com.scaramutti.tms.catalogs.paymentterm;

import com.scaramutti.tms.shared.entity.PaymentTerm;
import com.scaramutti.tms.shared.repository.PaymentTermRepository;
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
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
class PaymentTermsResourceTest {

    @Inject PaymentTermRepository paymentTermRepository;

    private static final String INACTIVE_TEST_NAME = "__TEST_INACTIVE_TERM__";

    /** Inserta un payment term inactivo para tests del filtro. La commitea en una tx propia. */
    private void seedInactiveTestPaymentTerm() {
        QuarkusTransaction.requiringNew().run(() -> {
            if (paymentTermRepository.count("name", INACTIVE_TEST_NAME) > 0) {
                return;
            }
            PaymentTerm inactiveTestPaymentTerm = new PaymentTerm();
            inactiveTestPaymentTerm.name = INACTIVE_TEST_NAME;
            inactiveTestPaymentTerm.days = 999;
            inactiveTestPaymentTerm.isActive = false;
            paymentTermRepository.persist(inactiveTestPaymentTerm);
        });
    }

    private void cleanupInactiveTestPaymentTerm() {
        QuarkusTransaction.requiringNew().run(() ->
            paymentTermRepository.delete("name", INACTIVE_TEST_NAME)
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
    void list_withoutFilter_returns200AndIncludesSeedPaymentTerms() {
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/payment-terms")
        .then()
            .statusCode(200)
            .body("size()", greaterThanOrEqualTo(5))
            .body("name", hasItem("Contado"))
            .body("name", hasItem("15 días"))
            .body("name", hasItem("30 días"))
            .body("name", hasItem("60 días"))
            .body("name", hasItem("50% adelanto / 50% antes de descarga"));
    }

    @Test
    void list_responseShape_matchesContract() {
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/payment-terms")
        .then()
            .statusCode(200)
            .body("[0].id", greaterThanOrEqualTo(1))
            .body("name", everyItem(notNullValue()))
            .body("days", everyItem(greaterThanOrEqualTo(0)))
            .body("[0].isActive", equalTo(true));
    }

    @Test
    void list_returnsApplicationJsonContentType() {
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/payment-terms")
        .then()
            .statusCode(200)
            .contentType("application/json");
    }

    @Test
    void list_resultsOrderedByNameAscending() {
        String token = login("admin", "Admin1234");

        // Filtramos a un subset robusto: '15 días' y 'Contado' son los dos
        // extremos del orden alfabético entre los seeds (1 < C). Asi el test
        // sigue verificando el orden RELATIVO si en el futuro se agregan otros
        // valores intermedios (ej. '45 días') sin romper la asserción.
        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/payment-terms?isActive=true")
        .then()
            .statusCode(200)
            .body("name.findAll { it in ['15 días','Contado'] }", contains("15 días", "Contado"));
    }

    // ---------- Filtro isActive ----------------------------------------------

    @Test
    void list_withIsActiveTrue_returnsOnlyActivePaymentTerms() {
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/payment-terms?isActive=true")
        .then()
            .statusCode(200)
            .body("isActive", everyItem(equalTo(true)))
            .body("name", hasItem("Contado"))
            .body("name", hasItem("30 días"));
    }

    @Test
    void list_withIsActiveFalse_returnsOnlyInactivePaymentTerms() {
        seedInactiveTestPaymentTerm();
        try {
            String token = login("admin", "Admin1234");

            given()
                .header("Authorization", "Bearer " + token)
            .when()
                .get("/payment-terms?isActive=false")
            .then()
                .statusCode(200)
                .body("isActive", everyItem(equalTo(false)))
                .body("name", hasItem(INACTIVE_TEST_NAME))
                .body("name", not(hasItem("Contado")))
                .body("name", not(hasItem("30 días")));
        } finally {
            cleanupInactiveTestPaymentTerm();
        }
    }

    @Test
    void list_withIsActiveTrue_excludesInactivePaymentTerms() {
        seedInactiveTestPaymentTerm();
        try {
            String token = login("admin", "Admin1234");

            given()
                .header("Authorization", "Bearer " + token)
            .when()
                .get("/payment-terms?isActive=true")
            .then()
                .statusCode(200)
                .body("name", not(hasItem(INACTIVE_TEST_NAME)));
        } finally {
            cleanupInactiveTestPaymentTerm();
        }
    }

    @Test
    void list_withMalformedIsActive_treatsAsFalse() {
        // Lock-in del comportamiento actual del binder de RESTEasy:
        // Boolean.parseBoolean("maybe") devuelve false, asi que el endpoint
        // se comporta como "?isActive=false". El contrato no especifica que
        // hacer con valores malformados — este test documenta lo observado.
        seedInactiveTestPaymentTerm();
        try {
            String token = login("admin", "Admin1234");

            given()
                .header("Authorization", "Bearer " + token)
            .when()
                .get("/payment-terms?isActive=maybe")
            .then()
                .statusCode(200)
                .body("isActive", everyItem(equalTo(false)))
                .body("name", hasItem(INACTIVE_TEST_NAME));
        } finally {
            cleanupInactiveTestPaymentTerm();
        }
    }

    // ---------- Auth ---------------------------------------------------------

    @Test
    void list_withoutToken_returns401() {
        given()
        .when()
            .get("/payment-terms")
        .then()
            .statusCode(401);
    }

    @Test
    void list_withMalformedToken_returns401_tokenInvalid() {
        given()
            .header("Authorization", "Bearer eyJ.malformed.token")
        .when()
            .get("/payment-terms")
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
            .get("/payment-terms")
        .then()
            .statusCode(401)
            .contentType("application/problem+json")
            .body("code", equalTo("AUTH-007"));
    }

    // ---------- Roles --------------------------------------------------------

    @Test
    void list_isAccessibleBySalesRole() {
        // El admin ya esta cubierto en los happy path. Aca validamos que un rol
        // distinto (sales) tambien puede consultar — confirma que el endpoint
        // no discrimina por rol, solo exige autenticacion.
        String token = login("lcampos", "Sales1234");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/payment-terms")
        .then()
            .statusCode(200)
            .body("name", hasItem("Contado"));
    }
}
