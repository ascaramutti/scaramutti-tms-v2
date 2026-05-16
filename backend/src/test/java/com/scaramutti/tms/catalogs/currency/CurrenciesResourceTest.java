package com.scaramutti.tms.catalogs.currency;

import com.scaramutti.tms.shared.entity.Currency;
import com.scaramutti.tms.shared.repository.CurrencyRepository;
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

@QuarkusTest
class CurrenciesResourceTest {

    @Inject CurrencyRepository currencyRepository;

    private static final String INACTIVE_TEST_CODE = "XTS";

    /** Inserta una moneda inactiva para tests del filtro. La commitea en una tx propia. */
    private void seedInactiveTestCurrency() {
        QuarkusTransaction.requiringNew().run(() -> {
            if (currencyRepository.count("code", INACTIVE_TEST_CODE) > 0) {
                return;
            }
            Currency inactiveTestCurrency = new Currency();
            inactiveTestCurrency.code = INACTIVE_TEST_CODE;
            inactiveTestCurrency.symbol = "*";
            inactiveTestCurrency.name = "Inactive Test Currency";
            inactiveTestCurrency.isActive = false;
            currencyRepository.persist(inactiveTestCurrency);
        });
    }

    private void cleanupInactiveTestCurrency() {
        QuarkusTransaction.requiringNew().run(() ->
            currencyRepository.delete("code", INACTIVE_TEST_CODE)
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
    void list_withoutFilter_returns200AndIncludesSeedCurrencies() {
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/currencies")
        .then()
            .statusCode(200)
            .body("size()", greaterThanOrEqualTo(2))
            .body("code", hasItem("USD"))
            .body("code", hasItem("PEN"));
    }

    @Test
    void list_responseShape_matchesContract() {
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/currencies")
        .then()
            .statusCode(200)
            .body("[0].id", greaterThanOrEqualTo(1))
            .body("code", everyItem(matchesPattern("^[A-Z]{3}$")))
            .body("[0].symbol", not(equalTo("")))
            .body("[0].isActive", equalTo(true));
    }

    @Test
    void list_returnsApplicationJsonContentType() {
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/currencies")
        .then()
            .statusCode(200)
            .contentType("application/json");
    }

    @Test
    void list_resultsOrderedByCodeAscending() {
        String token = login("admin", "Admin1234");

        // Filtramos a solo PEN/USD para que el test sea robusto si en el futuro
        // se agrega otra currency activa al seed (EUR, ARS, etc.). Verifica el
        // orden RELATIVO entre las dos seed currencies: P < U alfabéticamente.
        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/currencies?isActive=true")
        .then()
            .statusCode(200)
            .body("code.findAll { it in ['PEN','USD'] }", contains("PEN", "USD"));
    }

    // ---------- Filtro isActive ----------------------------------------------

    @Test
    void list_withIsActiveTrue_returnsOnlyActiveCurrencies() {
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/currencies?isActive=true")
        .then()
            .statusCode(200)
            .body("isActive", everyItem(equalTo(true)))
            .body("code", hasItem("USD"))
            .body("code", hasItem("PEN"));
    }

    @Test
    void list_withIsActiveFalse_returnsOnlyInactiveCurrencies() {
        seedInactiveTestCurrency();
        try {
            String token = login("admin", "Admin1234");

            given()
                .header("Authorization", "Bearer " + token)
            .when()
                .get("/currencies?isActive=false")
            .then()
                .statusCode(200)
                .body("isActive", everyItem(equalTo(false)))
                .body("code", hasItem(INACTIVE_TEST_CODE))
                .body("code", not(hasItem("USD")))
                .body("code", not(hasItem("PEN")));
        } finally {
            cleanupInactiveTestCurrency();
        }
    }

    @Test
    void list_withIsActiveTrue_excludesInactiveCurrencies() {
        seedInactiveTestCurrency();
        try {
            String token = login("admin", "Admin1234");

            given()
                .header("Authorization", "Bearer " + token)
            .when()
                .get("/currencies?isActive=true")
            .then()
                .statusCode(200)
                .body("code", not(hasItem(INACTIVE_TEST_CODE)));
        } finally {
            cleanupInactiveTestCurrency();
        }
    }

    @Test
    void list_withMalformedIsActive_treatsAsFalse() {
        // Lock-in del comportamiento actual del binder de RESTEasy:
        // Boolean.parseBoolean("maybe") devuelve false, asi que el endpoint
        // se comporta como "?isActive=false". El contrato no especifica que
        // hacer con valores malformados — este test documenta lo observado.
        seedInactiveTestCurrency();
        try {
            String token = login("admin", "Admin1234");

            given()
                .header("Authorization", "Bearer " + token)
            .when()
                .get("/currencies?isActive=maybe")
            .then()
                .statusCode(200)
                .body("isActive", everyItem(equalTo(false)))
                .body("code", hasItem(INACTIVE_TEST_CODE));
        } finally {
            cleanupInactiveTestCurrency();
        }
    }

    // ---------- Auth ---------------------------------------------------------

    @Test
    void list_withoutToken_returns401() {
        given()
        .when()
            .get("/currencies")
        .then()
            .statusCode(401);
    }

    @Test
    void list_withMalformedToken_returns401_tokenInvalid() {
        given()
            .header("Authorization", "Bearer eyJ.malformed.token")
        .when()
            .get("/currencies")
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
            .get("/currencies")
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
            .get("/currencies")
        .then()
            .statusCode(200)
            .body("code", hasItem("USD"));
    }
}
