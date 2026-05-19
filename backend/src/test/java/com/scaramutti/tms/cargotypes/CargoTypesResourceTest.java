package com.scaramutti.tms.cargotypes;

import com.scaramutti.tms.shared.entity.CargoType;
import com.scaramutti.tms.shared.repository.CargoTypeRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.smallrye.jwt.build.Jwt;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.containsInRelativeOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
class CargoTypesResourceTest {

    @Inject CargoTypeRepository cargoTypeRepository;

    // Prefijo ZTEST_ en name para identificar fixtures de tests; Z al final del
    // orden alfabetico no perturba los 69 seeds prod reales.
    private static final String TEST_NAME_PREFIX = "ZTEST_";

    @AfterEach
    void cleanupListingFixtures() {
        QuarkusTransaction.requiringNew().run(() ->
            cargoTypeRepository.delete("name like ?1", TEST_NAME_PREFIX + "%")
        );
    }

    /** Inserta un cargo type fixture con standardWeight=1.00 (NOT NULL en BD). */
    private void seedCargoType(String name, boolean isActive) {
        QuarkusTransaction.requiringNew().run(() -> {
            CargoType c = new CargoType();
            c.name = name;
            c.standardWeight = BigDecimal.ONE;
            c.isActive = isActive;
            cargoTypeRepository.persist(c);
        });
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

    /** Genera un JWT valido para un rol sin user seedeado (dispatcher, operations_manager). */
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

    // ---------- Happy path / shape del response ------------------------------

    @Test
    void list_withoutQueryParams_returnsFirstPageWithDefaults() {
        // BD prod tiene 69 cargo_types reales. Verifica defaults page=0, size=20.
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/cargo-types")
        .then()
            .statusCode(200)
            .contentType("application/json")
            .body("page", equalTo(0))
            .body("size", equalTo(20))
            .body("numberOfElements", equalTo(20))
            .body("totalElements", greaterThanOrEqualTo(69))
            .body("first", equalTo(true))
            .body("last", equalTo(false))
            .body("empty", equalTo(false))
            .body("content.size()", equalTo(20));
    }

    @Test
    void list_responseShape_matchesPageMetaAndCargoTypeResponseContract() {
        seedCargoType(TEST_NAME_PREFIX + "SHAPE", true);
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/cargo-types?q=" + TEST_NAME_PREFIX + "SHAPE")
        .then()
            .statusCode(200)
            .body("page", notNullValue())
            .body("size", notNullValue())
            .body("totalElements", notNullValue())
            .body("totalPages", notNullValue())
            .body("numberOfElements", notNullValue())
            .body("first", notNullValue())
            .body("last", notNullValue())
            .body("empty", notNullValue())
            .body("content", notNullValue())
            .body("content[0].id", notNullValue())
            .body("content[0].name", equalTo(TEST_NAME_PREFIX + "SHAPE"))
            .body("content[0].standardWeight", notNullValue())
            .body("content[0].isActive", equalTo(true));
    }

    @Test
    void list_returnsApplicationJsonContentType() {
        String token = login("admin", "Admin1234");
        given().header("Authorization", "Bearer " + token)
        .when().get("/cargo-types")
        .then().statusCode(200).contentType("application/json");
    }

    // ---------- Busqueda fuzzy q ---------------------------------------------

    @Test
    void list_withQMatchingName_returnsMatchingCargoTypes() {
        seedCargoType(TEST_NAME_PREFIX + "EXCAVADORA_320", true);
        seedCargoType(TEST_NAME_PREFIX + "GRUA", true);
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/cargo-types?q=EXCAVADORA")
        .then()
            .statusCode(200)
            .body("content.name", hasItem(TEST_NAME_PREFIX + "EXCAVADORA_320"))
            .body("content.name", not(hasItem(TEST_NAME_PREFIX + "GRUA")));
    }

    @Test
    void list_withShortQAsSubstring_matchesLongName_viaIlike() {
        // Regresion guard: q corto contra name largo debe matchear via ILIKE substring.
        seedCargoType(TEST_NAME_PREFIX + "EXCAVADORA_LONG_NAME_OPT", true);
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/cargo-types?q=EXCA")
        .then()
            .statusCode(200)
            .body("content.name", hasItem(TEST_NAME_PREFIX + "EXCAVADORA_LONG_NAME_OPT"));
    }

    @Test
    void list_withQLowerCase_matchesUppercaseStoredName() {
        // El mapper uppercasea q antes de pasarlo al repo. ILIKE es case-insensitive
        // de todas formas, pero la verificacion confirma el flow del mapper.
        seedCargoType(TEST_NAME_PREFIX + "CASETEST", true);
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/cargo-types?q=ztest_casetest")
        .then()
            .statusCode(200)
            .body("content.name", hasItem(TEST_NAME_PREFIX + "CASETEST"));
    }

    @Test
    void list_withQNoMatch_returnsEmptyPage() {
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/cargo-types?q=ZZZNADAEXISTE_xyzzy_999")
        .then()
            .statusCode(200)
            .body("content.size()", equalTo(0))
            .body("totalElements", equalTo(0))
            .body("totalPages", equalTo(0))
            .body("numberOfElements", equalTo(0))
            .body("first", equalTo(true))
            .body("last", equalTo(true))
            .body("empty", equalTo(true));
    }

    @Test
    void list_withQEmptyString_returns400_COM001() {
        // Con minLength=3, q="" no es valido. Para no filtrar el cliente debe
        // OMITIR el param, no enviarlo vacio.
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/cargo-types?q=")
        .then()
            .statusCode(400)
            .contentType("application/problem+json")
            .body("code", equalTo("COM-001"))
            .body("errors.size()", greaterThanOrEqualTo(1));
    }

    // ---------- minLength validation (NUEVO vs clients) ----------------------

    @Test
    void list_withQ2Chars_returns400_COM001() {
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/cargo-types?q=ab")
        .then()
            .statusCode(400)
            .contentType("application/problem+json")
            .body("code", equalTo("COM-001"))
            .body("errors.size()", greaterThanOrEqualTo(1));
    }

    @Test
    void list_withQ1Char_returns400_COM001() {
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/cargo-types?q=a")
        .then()
            .statusCode(400)
            .body("code", equalTo("COM-001"));
    }

    // ---------- Filtro isActive ----------------------------------------------

    @Test
    void list_withIsActiveTrue_returnsOnlyActiveCargoTypes() {
        seedCargoType(TEST_NAME_PREFIX + "ACT", true);
        seedCargoType(TEST_NAME_PREFIX + "INA", false);
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/cargo-types?q=" + TEST_NAME_PREFIX + "&isActive=true")
        .then()
            .statusCode(200)
            .body("content.isActive", everyItem(is(true)))
            .body("content.name", hasItem(TEST_NAME_PREFIX + "ACT"))
            .body("content.name", not(hasItem(TEST_NAME_PREFIX + "INA")));
    }

    @Test
    void list_withIsActiveFalse_returnsOnlyInactiveCargoTypes() {
        seedCargoType(TEST_NAME_PREFIX + "ACT", true);
        seedCargoType(TEST_NAME_PREFIX + "INA", false);
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/cargo-types?q=" + TEST_NAME_PREFIX + "&isActive=false")
        .then()
            .statusCode(200)
            .body("content.isActive", everyItem(is(false)))
            .body("content.name", hasItem(TEST_NAME_PREFIX + "INA"))
            .body("content.name", not(hasItem(TEST_NAME_PREFIX + "ACT")));
    }

    @Test
    void list_withoutIsActive_returnsBothActiveAndInactive() {
        seedCargoType(TEST_NAME_PREFIX + "ACT", true);
        seedCargoType(TEST_NAME_PREFIX + "INA", false);
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/cargo-types?q=" + TEST_NAME_PREFIX)
        .then()
            .statusCode(200)
            .body("content.name", hasItem(TEST_NAME_PREFIX + "ACT"))
            .body("content.name", hasItem(TEST_NAME_PREFIX + "INA"));
    }

    @Test
    void list_withMalformedIsActive_treatsAsFalse() {
        // Lock-in del binder JAX-RS Boolean.parseBoolean("maybe") == false.
        seedCargoType(TEST_NAME_PREFIX + "INA", false);
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/cargo-types?q=" + TEST_NAME_PREFIX + "INA&isActive=maybe")
        .then()
            .statusCode(200)
            .body("content.isActive", everyItem(is(false)));
    }

    // ---------- Paginacion ----------------------------------------------------

    @Test
    void list_withPage1Size5_returnsSecondPageOfFive() {
        for (int i = 1; i <= 12; i++) {
            seedCargoType(TEST_NAME_PREFIX + "P" + String.format("%02d", i), true);
        }
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/cargo-types?q=" + TEST_NAME_PREFIX + "P&page=1&size=5")
        .then()
            .statusCode(200)
            .body("page", equalTo(1))
            .body("size", equalTo(5))
            .body("numberOfElements", equalTo(5))
            .body("totalElements", equalTo(12))
            .body("totalPages", equalTo(3))
            .body("first", equalTo(false))
            .body("last", equalTo(false))
            .body("content.size()", equalTo(5));
    }

    @Test
    void list_withPage2Size5_returnsLastPartialPage() {
        for (int i = 1; i <= 12; i++) {
            seedCargoType(TEST_NAME_PREFIX + "P" + String.format("%02d", i), true);
        }
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/cargo-types?q=" + TEST_NAME_PREFIX + "P&page=2&size=5")
        .then()
            .statusCode(200)
            .body("page", equalTo(2))
            .body("numberOfElements", equalTo(2))
            .body("totalElements", equalTo(12))
            .body("first", equalTo(false))
            .body("last", equalTo(true))
            .body("content.size()", equalTo(2));
    }

    @Test
    void list_withSize100_boundaryMaxAccepted() {
        String token = login("admin", "Admin1234");
        given().header("Authorization", "Bearer " + token)
        .when().get("/cargo-types?size=100")
        .then().statusCode(200).body("size", equalTo(100));
    }

    @Test
    void list_withPage0Size1_boundaryMinSize() {
        seedCargoType(TEST_NAME_PREFIX + "S1", true);
        seedCargoType(TEST_NAME_PREFIX + "S2", true);
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/cargo-types?q=" + TEST_NAME_PREFIX + "S&page=0&size=1")
        .then()
            .statusCode(200)
            .body("size", equalTo(1))
            .body("numberOfElements", equalTo(1))
            .body("totalElements", equalTo(2))
            .body("totalPages", equalTo(2))
            .body("first", equalTo(true))
            .body("last", equalTo(false));
    }

    @Test
    void list_pageBeyondTotalPages_returnsEmptyContent() {
        seedCargoType(TEST_NAME_PREFIX + "OVERFLOW1", true);
        seedCargoType(TEST_NAME_PREFIX + "OVERFLOW2", true);
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/cargo-types?q=" + TEST_NAME_PREFIX + "OVERFLOW&page=99&size=20")
        .then()
            .statusCode(200)
            .body("content.size()", equalTo(0))
            .body("numberOfElements", equalTo(0))
            .body("totalElements", equalTo(2))
            .body("empty", equalTo(true))
            .body("last", equalTo(true));
    }

    // ---------- Orden ASC por name (sin q) -----------------------------------

    @Test
    void list_resultsOrderedByNameAscending() {
        // Sin q: order primario es name ASC.
        seedCargoType(TEST_NAME_PREFIX + "ZZZ", true);
        seedCargoType(TEST_NAME_PREFIX + "AAA", true);
        seedCargoType(TEST_NAME_PREFIX + "MMM", true);
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/cargo-types?size=100")
        .then()
            .statusCode(200)
            .body("content.name", containsInRelativeOrder(
                TEST_NAME_PREFIX + "AAA", TEST_NAME_PREFIX + "MMM", TEST_NAME_PREFIX + "ZZZ"
            ));
    }

    // ---------- Validacion 400 (Bean Validation en query params) -------------

    @Test
    void list_withPageNegative_returns400_COM001() {
        String token = login("admin", "Admin1234");
        given().header("Authorization", "Bearer " + token)
        .when().get("/cargo-types?page=-1")
        .then().statusCode(400).contentType("application/problem+json")
            .body("code", equalTo("COM-001"))
            .body("errors.size()", greaterThanOrEqualTo(1));
    }

    @Test
    void list_withSizeZero_returns400_COM001() {
        String token = login("admin", "Admin1234");
        given().header("Authorization", "Bearer " + token)
        .when().get("/cargo-types?size=0")
        .then().statusCode(400).body("code", equalTo("COM-001"));
    }

    @Test
    void list_withSizeAboveMax_returns400_COM001() {
        String token = login("admin", "Admin1234");
        given().header("Authorization", "Bearer " + token)
        .when().get("/cargo-types?size=101")
        .then().statusCode(400).body("code", equalTo("COM-001"));
    }

    @Test
    void list_withPageNotANumber_returns400Or404() {
        String token = login("admin", "Admin1234");
        given().header("Authorization", "Bearer " + token)
        .when().get("/cargo-types?page=abc")
        .then().statusCode(anyOf(equalTo(400), equalTo(404)));
    }

    @Test
    void list_withSizeNotANumber_returns400Or404() {
        String token = login("admin", "Admin1234");
        given().header("Authorization", "Bearer " + token)
        .when().get("/cargo-types?size=xyz")
        .then().statusCode(anyOf(equalTo(400), equalTo(404)));
    }

    // ---------- Auth 401 ------------------------------------------------------

    @Test
    void list_withoutToken_returns401() {
        given().when().get("/cargo-types").then().statusCode(401);
    }

    @Test
    void list_withMalformedToken_returns401_AUTH008() {
        given().header("Authorization", "Bearer eyJ.malformed.token")
        .when().get("/cargo-types")
        .then().statusCode(401).contentType("application/problem+json")
            .body("code", equalTo("AUTH-008"));
    }

    @Test
    void list_withExpiredToken_returns401_AUTH007() {
        Instant past = Instant.now().minusSeconds(120);
        String expiredToken = Jwt.subject("1")
            .upn("admin")
            .groups(Set.of("admin"))
            .claim("typ", "access")
            .issuedAt(past.minusSeconds(3600))
            .expiresAt(past)
            .sign();

        given().header("Authorization", "Bearer " + expiredToken)
        .when().get("/cargo-types")
        .then().statusCode(401).contentType("application/problem+json")
            .body("code", equalTo("AUTH-007"));
    }

    // ---------- Authorization: cualquier rol autenticado puede listar --------

    @Test
    void list_withAdminRole_returns200() {
        String token = login("admin", "Admin1234");
        given().header("Authorization", "Bearer " + token)
        .when().get("/cargo-types").then().statusCode(200);
    }

    @Test
    void list_withSalesRole_returns200() {
        String token = login("lcampos", "Sales1234");
        given().header("Authorization", "Bearer " + token)
        .when().get("/cargo-types").then().statusCode(200);
    }

    @Test
    void list_withDispatcherRole_returns200() {
        String token = fabricateAccessToken("disp_test", "dispatcher");
        given().header("Authorization", "Bearer " + token)
        .when().get("/cargo-types").then().statusCode(200);
    }

    @Test
    void list_withOperationsManagerRole_returns200() {
        String token = fabricateAccessToken("ops_test", "operations_manager");
        given().header("Authorization", "Bearer " + token)
        .when().get("/cargo-types").then().statusCode(200);
    }
}
