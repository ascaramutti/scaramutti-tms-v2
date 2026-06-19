package com.scaramutti.tms.catalogs.condition;

import com.scaramutti.tms.shared.entity.Condition;
import com.scaramutti.tms.shared.repository.ConditionRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.smallrye.jwt.build.Jwt;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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

/**
 * Tests del catálogo de condiciones (`GET /quotation-conditions`, US-002). Auto-siembra
 * condiciones de prueba con marcadores únicos (`__TEST_COND_*`) y display_order altos (90001+)
 * para no chocar con la semilla real ni depender de ella; las limpia en @AfterEach.
 */
@QuarkusTest
class ConditionsResourceTest {

    @Inject ConditionRepository conditionRepository;

    private static final String MARKER_PREFIX = "__TEST_COND_";
    private static final String ACTIVE_A = "__TEST_COND_ACTIVE_A__";
    private static final String ACTIVE_B = "__TEST_COND_ACTIVE_B__";
    private static final String INACTIVE = "__TEST_COND_INACTIVE__";

    @BeforeEach
    void seedTestConditions() {
        QuarkusTransaction.requiringNew().run(() -> {
            if (conditionRepository.count("text like ?1", MARKER_PREFIX + "%") > 0) {
                return;
            }
            conditionRepository.persist(condition(ACTIVE_A, 90001, true));
            conditionRepository.persist(condition(ACTIVE_B, 90002, true));
            conditionRepository.persist(condition(INACTIVE, 90003, false));
        });
    }

    @AfterEach
    void cleanupTestConditions() {
        QuarkusTransaction.requiringNew().run(() ->
            conditionRepository.delete("text like ?1", MARKER_PREFIX + "%")
        );
    }

    private static Condition condition(String text, int displayOrder, boolean isActive) {
        Condition condition = new Condition();
        condition.text = text;
        condition.displayOrder = displayOrder;
        condition.isActive = isActive;
        return condition;
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
    void list_withoutFilter_returns200AndIncludesActiveAndInactive() {
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/quotation-conditions")
        .then()
            .statusCode(200)
            .body("text", hasItem(ACTIVE_A))
            .body("text", hasItem(ACTIVE_B))
            .body("text", hasItem(INACTIVE));
    }

    @Test
    void list_responseShape_matchesContract() {
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/quotation-conditions")
        .then()
            .statusCode(200)
            .body("[0].id", greaterThanOrEqualTo(1))
            .body("text", everyItem(notNullValue()))
            .body("displayOrder", everyItem(greaterThanOrEqualTo(1)))
            .body("isActive", everyItem(notNullValue()));
    }

    @Test
    void list_returnsApplicationJsonContentType() {
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/quotation-conditions")
        .then()
            .statusCode(200)
            .contentType("application/json");
    }

    @Test
    void list_resultsOrderedByDisplayOrderAscending() {
        String token = login("admin", "Admin1234");

        // A (90001) debe venir antes que B (90002). Filtramos a los marcadores para que el
        // orden RELATIVO se verifique aunque haya otras condiciones intercaladas.
        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/quotation-conditions?isActive=true")
        .then()
            .statusCode(200)
            .body("text.findAll { it in ['" + ACTIVE_A + "','" + ACTIVE_B + "'] }",
                contains(ACTIVE_A, ACTIVE_B));
    }

    @Test
    void list_withoutFilter_resultsOrderedByDisplayOrderAscending() {
        String token = login("admin", "Admin1234");

        // Sin filtro lista TODAS (activas + inactivas) ordenadas por display_order ASC:
        // A (90001) < B (90002) < INACTIVE (90003). Ejercita listAllOrderedByDisplayOrder
        // (la rama sin filtro), complementando el test de orden con isActive=true.
        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/quotation-conditions")
        .then()
            .statusCode(200)
            .body("text.findAll { it in ['" + ACTIVE_A + "','" + ACTIVE_B + "','" + INACTIVE + "'] }",
                contains(ACTIVE_A, ACTIVE_B, INACTIVE));
    }

    // ---------- Filtro isActive ----------------------------------------------

    @Test
    void list_withIsActiveTrue_returnsOnlyActiveConditions() {
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/quotation-conditions?isActive=true")
        .then()
            .statusCode(200)
            .body("isActive", everyItem(equalTo(true)))
            .body("text", hasItem(ACTIVE_A))
            .body("text", not(hasItem(INACTIVE)));
    }

    @Test
    void list_withIsActiveFalse_returnsOnlyInactiveConditions() {
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/quotation-conditions?isActive=false")
        .then()
            .statusCode(200)
            .body("isActive", everyItem(equalTo(false)))
            .body("text", hasItem(INACTIVE))
            .body("text", not(hasItem(ACTIVE_A)));
    }

    @Test
    void list_withMalformedIsActive_treatsAsFalse() {
        // Lock-in del binder de RESTEasy: Boolean.parseBoolean("maybe") = false, así que se
        // comporta como ?isActive=false. El contrato no especifica malformados; documenta lo observado.
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/quotation-conditions?isActive=maybe")
        .then()
            .statusCode(200)
            .body("isActive", everyItem(equalTo(false)))
            .body("text", hasItem(INACTIVE));
    }

    // ---------- Auth ---------------------------------------------------------

    @Test
    void list_withoutToken_returns401() {
        given()
        .when()
            .get("/quotation-conditions")
        .then()
            .statusCode(401);
    }

    @Test
    void list_withMalformedToken_returns401_tokenInvalid() {
        given()
            .header("Authorization", "Bearer eyJ.malformed.token")
        .when()
            .get("/quotation-conditions")
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
            .get("/quotation-conditions")
        .then()
            .statusCode(401)
            .contentType("application/problem+json")
            .body("code", equalTo("AUTH-007"));
    }

    // ---------- Roles --------------------------------------------------------

    @Test
    void list_isAccessibleBySalesRole() {
        // Confirma que el endpoint no discrimina por rol, solo exige autenticación
        // (catálogo de lectura abierta a autenticados, como payment-terms/currencies).
        String token = login("lcampos", "Sales1234");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/quotation-conditions")
        .then()
            .statusCode(200)
            .body("text", hasItem(ACTIVE_A));
    }
}
