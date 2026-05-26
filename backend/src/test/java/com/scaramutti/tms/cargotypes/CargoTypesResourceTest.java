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
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

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

    // =========================================================================
    // POST /cargo-types (createCargoType)
    // =========================================================================
    //
    // Calco de POST /clients (PR #15) con 3 simplificaciones:
    //  - 1 solo codigo de duplicado (CGT-001 sobre name)
    //  - Sin patterns regex
    //  - Numericos con @DecimalMin("0") + @Digits(8,2)

    private void cleanupCargoTypeByName(String nameUpper) {
        QuarkusTransaction.requiringNew().run(() ->
            cargoTypeRepository.delete("name", nameUpper)
        );
    }

    /** Arma JSON con solo los campos no-null. Numericos sin comillas. */
    private String body(String name, String description, String standardWeight,
                        String standardLength, String standardWidth, String standardHeight) {
        StringBuilder sb = new StringBuilder("{");
        if (name != null) sb.append("\"name\":\"").append(name).append("\",");
        if (description != null) sb.append("\"description\":\"").append(description).append("\",");
        if (standardWeight != null) sb.append("\"standardWeight\":").append(standardWeight).append(",");
        if (standardLength != null) sb.append("\"standardLength\":").append(standardLength).append(",");
        if (standardWidth != null) sb.append("\"standardWidth\":").append(standardWidth).append(",");
        if (standardHeight != null) sb.append("\"standardHeight\":").append(standardHeight).append(",");
        if (sb.charAt(sb.length() - 1) == ',') sb.deleteCharAt(sb.length() - 1);
        sb.append("}");
        return sb.toString();
    }

    // ---------- Happy path / contrato --------------------------------------

    @Test
    void create_withValidPayload_returns201AndPersists() {
        String name = "ZTEST_EXCAVADORA_320";
        try {
            String token = login("admin", "Admin1234");

            given()
                .header("Authorization", "Bearer " + token)
                .contentType(ContentType.JSON)
                .body(body(name, "Excavadora oruga 30t", "30.50", "12.00", "3.00", "3.20"))
            .when()
                .post("/cargo-types")
            .then()
                .statusCode(201)
                .contentType("application/json")
                .body("id", greaterThanOrEqualTo(1))
                .body("name", equalTo(name))
                .body("description", equalTo("Excavadora oruga 30t"))
                .body("standardWeight", equalTo(30.50f))
                .body("standardLength", equalTo(12.00f))
                .body("standardWidth", equalTo(3.00f))
                .body("standardHeight", equalTo(3.20f))
                .body("isActive", equalTo(true));

            CargoType persisted = cargoTypeRepository.find("name", name).firstResult();
            assertNotNull(persisted);
            assertEquals(name, persisted.name);
        } finally {
            cleanupCargoTypeByName(name);
        }
    }

    @Test
    void create_withOnlyRequiredFields_returns201AndPersistsNullsForOptionals() {
        String name = "ZTEST_MINCARGO";
        try {
            String token = login("admin", "Admin1234");

            given()
                .header("Authorization", "Bearer " + token)
                .contentType(ContentType.JSON)
                .body(body(name, null, "1.00", null, null, null))
            .when()
                .post("/cargo-types")
            .then()
                .statusCode(201)
                .body("name", equalTo(name))
                .body("description", nullValue())
                .body("standardLength", nullValue())
                .body("standardWidth", nullValue())
                .body("standardHeight", nullValue())
                .body("isActive", equalTo(true));
        } finally {
            cleanupCargoTypeByName(name);
        }
    }

    @Test
    void create_responseShape_omitsCreatedAt() {
        // Lock-in: CargoTypeResponse NO expone createdAt (a diferencia de ClientResponse).
        String name = "ZTEST_SHAPECHECK";
        try {
            String token = login("admin", "Admin1234");

            String createdAt = given()
                .header("Authorization", "Bearer " + token)
                .contentType(ContentType.JSON)
                .body(body(name, null, "1.00", null, null, null))
            .when()
                .post("/cargo-types")
            .then()
                .statusCode(201)
                .extract().jsonPath().getString("createdAt");

            assertEquals(null, createdAt);
        } finally {
            cleanupCargoTypeByName(name);
        }
    }

    @Test
    void create_returnsApplicationJsonContentType() {
        String name = "ZTEST_CONTENTTYPE";
        try {
            String token = login("admin", "Admin1234");

            given()
                .header("Authorization", "Bearer " + token)
                .contentType(ContentType.JSON)
                .body(body(name, null, "1.00", null, null, null))
            .when()
                .post("/cargo-types")
            .then()
                .statusCode(201)
                .contentType("application/json");
        } finally {
            cleanupCargoTypeByName(name);
        }
    }

    // ---------- Trim + normalizacion ---------------------------------------

    @Test
    void create_trimsAndUppercasesName() {
        String stored = "ZTEST_TRIMNAME";
        try {
            String token = login("admin", "Admin1234");

            given()
                .header("Authorization", "Bearer " + token)
                .contentType(ContentType.JSON)
                .body(body("  ztest_trimname  ", null, "1.00", null, null, null))
            .when()
                .post("/cargo-types")
            .then()
                .statusCode(201)
                .body("name", equalTo(stored));

            CargoType persisted = cargoTypeRepository.find("name", stored).firstResult();
            assertNotNull(persisted);
            assertEquals(stored, persisted.name);
        } finally {
            cleanupCargoTypeByName(stored);
        }
    }

    @Test
    void create_trimsDescription_andEmptyToNull() {
        String name = "ZTEST_DESCTRIM";
        try {
            String token = login("admin", "Admin1234");

            given()
                .header("Authorization", "Bearer " + token)
                .contentType(ContentType.JSON)
                .body(body(name, "   ", "1.00", null, null, null))
            .when()
                .post("/cargo-types")
            .then()
                .statusCode(201)
                .body("description", nullValue());
        } finally {
            cleanupCargoTypeByName(name);
        }
    }

    @Test
    void create_withNameOnlyWhitespace_returns400_COM001() {
        // El name "   " puede pasar @NotBlank en versiones donde @NotBlank
        // valida non-empty-after-trim (depende del implementor), o fallar antes.
        // En ambos casos: 400 COM-001 (no se persiste).
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(body("   ", null, "1.00", null, null, null))
        .when()
            .post("/cargo-types")
        .then()
            .statusCode(400)
            .body("code", equalTo("COM-001"));
    }

    // ---------- Duplicados -------------------------------------------------

    @Test
    void create_withDuplicateName_returns409_CGT001() {
        String name = "ZTEST_DUPNAME";
        try {
            String token = login("admin", "Admin1234");

            // 1er POST OK
            given()
                .header("Authorization", "Bearer " + token)
                .contentType(ContentType.JSON)
                .body(body(name, null, "1.00", null, null, null))
            .when()
                .post("/cargo-types")
            .then()
                .statusCode(201);

            // 2do POST con mismo name → 409 CGT-001
            given()
                .header("Authorization", "Bearer " + token)
                .contentType(ContentType.JSON)
                .body(body(name, null, "2.00", null, null, null))
            .when()
                .post("/cargo-types")
            .then()
                .statusCode(409)
                .contentType("application/problem+json")
                .body("code", equalTo("CGT-001"));
        } finally {
            cleanupCargoTypeByName(name);
        }
    }

    @Test
    void create_withDuplicateNameCaseInsensitive_returns409_CGT001() {
        // Almacenado UPPER por el mapper. Reintento en lowercase debe normalizar
        // a UPPER y encontrar el duplicado.
        String stored = "ZTEST_DUPCASE";
        try {
            String token = login("admin", "Admin1234");

            given()
                .header("Authorization", "Bearer " + token)
                .contentType(ContentType.JSON)
                .body(body(stored, null, "1.00", null, null, null))
            .when()
                .post("/cargo-types")
            .then()
                .statusCode(201);

            given()
                .header("Authorization", "Bearer " + token)
                .contentType(ContentType.JSON)
                .body(body("ztest_dupcase", null, "1.00", null, null, null))
            .when()
                .post("/cargo-types")
            .then()
                .statusCode(409)
                .body("code", equalTo("CGT-001"));
        } finally {
            cleanupCargoTypeByName(stored);
        }
    }

    // ---------- Validation 400 — required + boundaries ---------------------

    @Test
    void create_withMissingName_returns400_COM001() {
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(body(null, null, "1.00", null, null, null))
        .when()
            .post("/cargo-types")
        .then()
            .statusCode(400)
            .contentType("application/problem+json")
            .body("code", equalTo("COM-001"))
            .body("errors.field", hasItem("name"));
    }

    @Test
    void create_withEmptyName_returns400() {
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(body("", null, "1.00", null, null, null))
        .when()
            .post("/cargo-types")
        .then()
            .statusCode(400)
            .body("code", equalTo("COM-001"));
    }

    @Test
    void create_withNameTooLong_returns400() {
        String token = login("admin", "Admin1234");
        String longName = "X".repeat(101);

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(body(longName, null, "1.00", null, null, null))
        .when()
            .post("/cargo-types")
        .then()
            .statusCode(400)
            .body("code", equalTo("COM-001"));
    }

    @Test
    void create_withMissingStandardWeight_returns400_COM001() {
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(body("ZTEST_NOWEIGHT", null, null, null, null, null))
        .when()
            .post("/cargo-types")
        .then()
            .statusCode(400)
            .body("code", equalTo("COM-001"))
            .body("errors.field", hasItem("standardWeight"));
    }

    // ---------- Validation 400 — campos numericos --------------------------

    @Test
    void create_withNegativeStandardWeight_returns400_COM001() {
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(body("ZTEST_NEGWEIGHT", null, "-1.00", null, null, null))
        .when()
            .post("/cargo-types")
        .then()
            .statusCode(400)
            .body("code", equalTo("COM-001"))
            .body("errors.field", hasItem("standardWeight"));
    }

    @Test
    void create_withNegativeStandardLength_returns400_COM001() {
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(body("ZTEST_NEGLEN", null, "1.00", "-2.00", null, null))
        .when()
            .post("/cargo-types")
        .then()
            .statusCode(400)
            .body("code", equalTo("COM-001"))
            .body("errors.field", hasItem("standardLength"));
    }

    @Test
    void create_withNegativeStandardWidth_returns400_COM001() {
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(body("ZTEST_NEGWIDTH", null, "1.00", null, "-2.00", null))
        .when()
            .post("/cargo-types")
        .then()
            .statusCode(400)
            .body("code", equalTo("COM-001"))
            .body("errors.field", hasItem("standardWidth"));
    }

    @Test
    void create_withNegativeStandardHeight_returns400_COM001() {
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(body("ZTEST_NEGHEIGHT", null, "1.00", null, null, "-2.00"))
        .when()
            .post("/cargo-types")
        .then()
            .statusCode(400)
            .body("code", equalTo("COM-001"))
            .body("errors.field", hasItem("standardHeight"));
    }

    // ---------- Boundary numericos (decisiones distintivas del PR) ---------

    @Test
    void create_withZeroStandardWeight_returns201() {
        // Lock-in de @DecimalMin(value="0", inclusive=true): un cargo type con
        // standardWeight=0 es valido. Si en el futuro se cambia a inclusive=false
        // este test debe fallar (decision explicita del PR).
        String name = "ZTEST_ZEROWEIGHT";
        try {
            String token = login("admin", "Admin1234");

            given()
                .header("Authorization", "Bearer " + token)
                .contentType(ContentType.JSON)
                .body(body(name, null, "0", null, null, null))
            .when()
                .post("/cargo-types")
            .then()
                .statusCode(201)
                // BigDecimal "0" se serializa sin decimal → JSON parser lo lee como Integer 0.
                .body("standardWeight", equalTo(0));
        } finally {
            cleanupCargoTypeByName(name);
        }
    }

    @Test
    void create_withStandardWeightOverflow_returns400_COM001() {
        // Lock-in de @Digits(integer=8, fraction=2): valores con > 8 enteros
        // serian rechazados por Bean Validation con 400 limpio, en vez de
        // explotar como 500 (SQL overflow del NUMERIC(10,2)).
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(body("ZTEST_OVERFLOW", null, "100000000.00", null, null, null))
        .when()
            .post("/cargo-types")
        .then()
            .statusCode(400)
            .body("code", equalTo("COM-001"))
            .body("errors.field", hasItem("standardWeight"));
    }

    @Test
    void create_withStandardWeightMaxInteger_returns201() {
        // Boundary superior valido: 99999999.99 = 8 enteros + 2 decimales = max NUMERIC(10,2).
        String name = "ZTEST_MAXWEIGHT";
        try {
            String token = login("admin", "Admin1234");

            given()
                .header("Authorization", "Bearer " + token)
                .contentType(ContentType.JSON)
                .body(body(name, null, "99999999.99", null, null, null))
            .when()
                .post("/cargo-types")
            .then()
                .statusCode(201)
                .body("standardWeight", equalTo(99999999.99f));
        } finally {
            cleanupCargoTypeByName(name);
        }
    }

    // ---------- Auth 401 ---------------------------------------------------

    @Test
    void create_withoutToken_returns401() {
        given()
            .contentType(ContentType.JSON)
            .body(body("ZTEST_NOAUTH", null, "1.00", null, null, null))
        .when()
            .post("/cargo-types")
        .then()
            .statusCode(401);
    }

    @Test
    void create_withMalformedToken_returns401_AUTH008() {
        given()
            .header("Authorization", "Bearer eyJ.malformed.token")
            .contentType(ContentType.JSON)
            .body(body("ZTEST_MALFORMED", null, "1.00", null, null, null))
        .when()
            .post("/cargo-types")
        .then()
            .statusCode(401)
            .contentType("application/problem+json")
            .body("code", equalTo("AUTH-008"));
    }

    @Test
    void create_withExpiredToken_returns401_AUTH007() {
        Instant past = Instant.now().minusSeconds(120);
        String expiredToken = Jwt.subject("1")
            .upn("admin")
            .groups(Set.of("admin"))
            .claim("typ", "access")
            .issuedAt(past.minusSeconds(3600))
            .expiresAt(past)
            .sign();

        given()
            .header("Authorization", "Bearer " + expiredToken)
            .contentType(ContentType.JSON)
            .body(body("ZTEST_EXPIRED", null, "1.00", null, null, null))
        .when()
            .post("/cargo-types")
        .then()
            .statusCode(401)
            .contentType("application/problem+json")
            .body("code", equalTo("AUTH-007"));
    }

    // ---------- Authorization: dispatcher 403, otros 201 -------------------

    @Test
    void create_withDispatcherRole_returns403_COM003() {
        // Dispatcher excluido del @RolesAllowed → 403 COM-003.
        String dispatcherToken = fabricateAccessToken("disp_test", "dispatcher");

        given()
            .header("Authorization", "Bearer " + dispatcherToken)
            .contentType(ContentType.JSON)
            .body(body("ZTEST_DISPATCHER_FORBID", null, "1.00", null, null, null))
        .when()
            .post("/cargo-types")
        .then()
            .statusCode(403)
            .contentType("application/problem+json")
            .body("code", equalTo("COM-003"));
    }

    @Test
    void create_withAdminRole_returns201() {
        String name = "ZTEST_ADMIN_OK";
        try {
            String token = login("admin", "Admin1234");
            given()
                .header("Authorization", "Bearer " + token)
                .contentType(ContentType.JSON)
                .body(body(name, null, "1.00", null, null, null))
            .when()
                .post("/cargo-types")
            .then()
                .statusCode(201);
        } finally {
            cleanupCargoTypeByName(name);
        }
    }

    @Test
    void create_withSalesRole_returns201() {
        String name = "ZTEST_SALES_OK";
        try {
            String token = login("lcampos", "Sales1234");
            given()
                .header("Authorization", "Bearer " + token)
                .contentType(ContentType.JSON)
                .body(body(name, null, "1.00", null, null, null))
            .when()
                .post("/cargo-types")
            .then()
                .statusCode(201);
        } finally {
            cleanupCargoTypeByName(name);
        }
    }

    @Test
    void create_withOperationsManagerRole_returns201() {
        String name = "ZTEST_OPSMGR_OK";
        try {
            String token = fabricateAccessToken("ops_test", "operations_manager");
            given()
                .header("Authorization", "Bearer " + token)
                .contentType(ContentType.JSON)
                .body(body(name, null, "1.00", null, null, null))
            .when()
                .post("/cargo-types")
            .then()
                .statusCode(201);
        } finally {
            cleanupCargoTypeByName(name);
        }
    }

    // ---------- @NotNull en body (regresion: body vacio NO debe ser 500) -----

    @Test
    void create_withEmptyBody_returns400() {
        // POST sin body — debe rechazarse por @NotNull en la frontera (Bean Validation)
        // ANTES de tocar el service. Sin esto, el null se propagaba al service y
        // crasheaba con NPE 500.
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
        .when()
            .post("/cargo-types")
        .then()
            .statusCode(400)
            .body("code", equalTo("COM-001"));
    }

    @Test
    void create_withExplicitNullBody_returns400() {
        // POST con body "null" literal — Jackson parsea como null. Mismo path
        // que el caso anterior pero por una via diferente.
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body("null")
        .when()
            .post("/cargo-types")
        .then()
            .statusCode(400)
            .body("code", equalTo("COM-001"));
    }
}
