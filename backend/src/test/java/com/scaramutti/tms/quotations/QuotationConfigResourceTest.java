package com.scaramutti.tms.quotations;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.smallrye.jwt.build.Jwt;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Integration tests del endpoint GET /api/v1/quotations/config.
 *
 * Endpoint read-only sin side effects — no requiere {@code @AfterEach} cleanup.
 * No toca BD. Devuelve constantes leidas de {@code application.properties} +
 * {@code QuotationValidatorService.MAX_ROOT_ITEMS}.
 *
 * El "lock-in" test (get_returnsActualConfiguredValues) afirma los valores
 * exactos de la config — si cambia un valor en application.properties, este
 * test debe fallar y forzar al dev a revisar el cambio.
 */
@QuarkusTest
class QuotationConfigResourceTest {

    // ---------- Happy path + contrato ----------------------------------------

    @Test
    void get_withAdminToken_returns200AndContractShape() {
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/quotations/config")
        .then()
            .statusCode(200)
            .contentType(ContentType.JSON)
            .body("igvPercentage", notNullValue())
            .body("maxRootItems", notNullValue())
            .body("defaultValidityDays", notNullValue())
            .body("igvPercentage", greaterThanOrEqualTo(0f))
            .body("igvPercentage", lessThanOrEqualTo(100f))
            .body("maxRootItems", greaterThanOrEqualTo(1))
            .body("defaultValidityDays", greaterThanOrEqualTo(1));
    }

    @Test
    void get_returnsActualConfiguredValues() {
        // Lock-in: asume application.properties con default-igv-percentage=18.00,
        // default-validity-days=15, y MAX_ROOT_ITEMS=5 en el validator.
        // Si cambia algo, este test debe fallar y forzar revision.
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/quotations/config")
        .then()
            .statusCode(200)
            .body("igvPercentage", equalTo(18.00f))
            .body("maxRootItems", equalTo(5))
            .body("defaultValidityDays", equalTo(15));
    }

    @Test
    void get_responseHasOnlyContractFields() {
        // Anti-leak: el response debe contener EXACTAMENTE 3 campos. Si alguien
        // agrega un campo nuevo al record sin actualizar el contrato, este test
        // falla y obliga a documentar el cambio en api/openapi.yaml.
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/quotations/config")
        .then()
            .statusCode(200)
            .body("size()", is(3))
            .body("keySet()", containsInAnyOrder(
                "igvPercentage", "maxRootItems", "defaultValidityDays"
            ));
    }

    // ---------- Cache-Control header -----------------------------------------

    @Test
    void get_includesCacheControlMaxAge3600PrivateHeader() {
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/quotations/config")
        .then()
            .statusCode(200)
            .header("Cache-Control", containsString("max-age=3600"))
            .header("Cache-Control", containsString("private"));
    }

    // ---------- Auth — variaciones (401) -------------------------------------

    @Test
    void get_withoutToken_returns401() {
        // Sin Authorization header — el filtro de Quarkus Security responde 401
        // antes de tocar el endpoint. El response NO trae body Problem (Quarkus
        // no invoca el ExceptionMapper en este path). Mismo patron en el resto
        // del codebase (ver AuthResourceTest.changePassword_withoutToken).
        given()
        .when()
            .get("/quotations/config")
        .then()
            .statusCode(401);
    }

    @Test
    void get_withMalformedToken_returns401_AUTH008() {
        given()
            .header("Authorization", "Bearer eyJ.malformed.token")
        .when()
            .get("/quotations/config")
        .then()
            .statusCode(401)
            .contentType("application/problem+json")
            .body("code", equalTo("AUTH-008"));
    }

    @Test
    void get_withExpiredToken_returns401_AUTH007() {
        // Fabricamos un JWT con exp en el pasado, firmado con la clave del backend.
        Instant past = Instant.now().minusSeconds(60);
        String expiredToken = Jwt.subject("1")
            .upn("admin")
            .groups(Set.of("admin"))
            .claim("typ", "access")
            .issuedAt(past.minusSeconds(3600))
            .expiresAt(past)
            .sign();

        given()
            .header("Authorization", "Bearer " + expiredToken)
        .when()
            .get("/quotations/config")
        .then()
            .statusCode(401)
            .body("code", equalTo("AUTH-007"));
    }

    // ---------- Roles permitidos (200) ---------------------------------------

    @Test
    void get_withSalesRole_returns200() {
        // lcampos esta seeded como sales en DevDataSeeder.
        String token = login("lcampos", "Sales1234");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/quotations/config")
        .then()
            .statusCode(200)
            .body("igvPercentage", notNullValue());
    }

    @Test
    void get_withGeneralManagerRole_returns200() {
        // No hay seed para general_manager — fabricamos JWT.
        String token = fabricateAccessToken("gm_test", "general_manager");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/quotations/config")
        .then()
            .statusCode(200)
            .body("igvPercentage", notNullValue());
    }

    @Test
    void get_withOperationsManagerRole_returns200() {
        String token = fabricateAccessToken("ops_test", "operations_manager");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/quotations/config")
        .then()
            .statusCode(200)
            .body("igvPercentage", notNullValue());
    }

    // ---------- Rol no permitido (403) ---------------------------------------

    @Test
    void get_withDispatcherRole_returns403_COM003() {
        // dispatcher no opera el wizard de cotizaciones — debe rebotar.
        String token = fabricateAccessToken("disp_test", "dispatcher");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/quotations/config")
        .then()
            .statusCode(403)
            .contentType("application/problem+json")
            .body("code", equalTo("COM-003"))
            .body("traceId", notNullValue());
    }

    // ----- helpers -----------------------------------------------------------

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

    /** Genera un JWT valido para un rol sin user seedeado (gm, ops, dispatcher). */
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
}
