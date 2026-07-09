package com.scaramutti.tms.warehouse.product;

import com.scaramutti.tms.shared.entity.User;
import com.scaramutti.tms.shared.repository.ProductRepository;
import com.scaramutti.tms.shared.repository.UserRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.smallrye.jwt.build.Jwt;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests de POST /warehouse/products. Calco estructural de
 * WarehouseSuppliersResourceTest. Dos diferencias propias del producto:
 *  - created_by es FK NOT NULL a public.users(id): los tests de rol→201 fabrican
 *    el token con el id de un usuario REAL (fabricateTokenForUser), no subject 999.
 *  - identidad compuesta (name, brand, partNumber) con COALESCE — se cubren el
 *    choque de genericos sin marca/parte y el control negativo (mismo name+brand,
 *    distinto partNumber = variante legitima).
 * Categorias/unidades vienen sembradas por V002 (categoryId=4 "Filtros",
 * unitOfMeasureId=1 "UND"). La tabla products nace vacia.
 */
@QuarkusTest
class WarehouseProductsResourceTest {

    @Inject ProductRepository productRepository;
    @Inject UserRepository userRepository;

    private static final String TEST_NAME_PREFIX = "ZTEST_";
    private static final int CATEGORY_FILTROS = 4;
    private static final int UNIT_UND = 1;

    @AfterEach
    void cleanupFixtures() {
        QuarkusTransaction.requiringNew().run(() ->
            productRepository.delete("name like ?1", TEST_NAME_PREFIX + "%")
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

    /** Token con subject ficticio (999): sirve para los 403 (el gate de rol dispara antes del persist). */
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

    /**
     * Token con el id de un usuario REAL: obligatorio en los 201 porque
     * products.created_by es FK NOT NULL (un subject inexistente reventaria el
     * INSERT con FK violation, no daria 201).
     */
    private String fabricateTokenForUser(int userId, String username, String role) {
        Instant now = Instant.now();
        return Jwt.subject(String.valueOf(userId))
            .upn(username)
            .groups(Set.of(role))
            .claim("typ", "access")
            .issuedAt(now)
            .expiresAt(now.plusSeconds(3600))
            .sign();
    }

    private int adminId() {
        User admin = userRepository.findByUsername("admin").orElseThrow();
        return admin.id;
    }

    private String productJson(
        String name, Integer categoryId, Integer unitOfMeasureId,
        String code, String brand, String partNumber,
        String minStock, String attributesJson, Boolean isActive
    ) {
        List<String> fields = new ArrayList<>();
        if (name != null)            fields.add("\"name\":\"" + name + "\"");
        if (categoryId != null)      fields.add("\"categoryId\":" + categoryId);
        if (unitOfMeasureId != null) fields.add("\"unitOfMeasureId\":" + unitOfMeasureId);
        if (code != null)            fields.add("\"code\":\"" + code + "\"");
        if (brand != null)           fields.add("\"brand\":\"" + brand + "\"");
        if (partNumber != null)      fields.add("\"partNumber\":\"" + partNumber + "\"");
        if (minStock != null)        fields.add("\"minStock\":" + minStock);
        if (attributesJson != null)  fields.add("\"attributes\":" + attributesJson);
        if (isActive != null)        fields.add("\"isActive\":" + isActive);
        return "{" + String.join(",", fields) + "}";
    }

    /** Producto minimo valido (solo required). */
    private String minimalProduct(String name) {
        return productJson(name, CATEGORY_FILTROS, UNIT_UND, null, null, null, null, null, null);
    }

    // ---------- POST: happy path -------------------------------------------------

    @Test
    void create_withFullPayload_returns201_generatedCode_stockZero_lowStockTrue() {
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(productJson("ZTEST_Filtro XYZ", CATEGORY_FILTROS, UNIT_UND, null,
                "Bosch", "F026407123", "4", "{\"rosca\":\"3/4-16\"}", null))
        .when()
            .post("/warehouse/products")
        .then()
            .statusCode(201)
            .contentType("application/json")
            .body("id", notNullValue())
            .body("code", matchesPattern("^PRO-\\d{4,}$"))
            .body("name", equalTo("ZTEST_Filtro XYZ"))
            .body("category.id", equalTo(CATEGORY_FILTROS))
            .body("category.name", equalTo("Filtros"))
            .body("unitOfMeasure.id", equalTo(UNIT_UND))
            .body("unitOfMeasure.code", equalTo("UND"))
            .body("brand", equalTo("Bosch"))
            .body("partNumber", equalTo("F026407123"))
            .body("attributes.rosca", equalTo("3/4-16"))
            .body("minStock", equalTo(4))
            .body("stock", equalTo(0))
            .body("lowStock", equalTo(true))
            .body("isActive", equalTo(true))
            .body("createdBy.id", notNullValue())
            .body("createdBy.username", equalTo("admin"))
            .body("createdAt", notNullValue())
            .body("updatedAt", notNullValue());
    }

    @Test
    void create_ignoresClientProvidedCode_autogeneratesInstead() {
        // El SKU es system-owned: si un cliente stale manda `code`, se ignora
        // (Jackson descarta props desconocidas) y el backend autogenera igual.
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(productJson("ZTEST_ClientCode", CATEGORY_FILTROS, UNIT_UND, "CLIENT-HACK-001",
                null, null, null, null, null))
        .when()
            .post("/warehouse/products")
        .then()
            .statusCode(201)
            .body("code", matchesPattern("^PRO-\\d{4,}$"));
    }

    @Test
    void create_withoutAttributes_defaultsEmptyObject() {
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(minimalProduct("ZTEST_SinAttrs"))
        .when()
            .post("/warehouse/products")
        .then()
            .statusCode(201)
            .body("attributes.size()", equalTo(0));
    }

    @Test
    void create_withMinStockOmitted_returns201_lowStockFalse() {
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(minimalProduct("ZTEST_MinZero"))
        .when()
            .post("/warehouse/products")
        .then()
            .statusCode(201)
            .body("minStock", equalTo(0))
            .body("stock", equalTo(0))
            .body("lowStock", equalTo(false));
    }

    @Test
    void create_withOnlyRequiredFields_nullableOptionalsNull() {
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(minimalProduct("ZTEST_SoloReq"))
        .when()
            .post("/warehouse/products")
        .then()
            .statusCode(201)
            .body("brand", nullValue())
            .body("partNumber", nullValue())
            .body("observations", nullValue())
            .body("isActive", equalTo(true));
    }

    @Test
    void create_normalizesBrandAndPartNumber_emptyToNull() {
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(productJson("  ZTEST_TrimNorm  ", CATEGORY_FILTROS, UNIT_UND, null,
                "   ", "", null, null, null))
        .when()
            .post("/warehouse/products")
        .then()
            .statusCode(201)
            .body("name", equalTo("ZTEST_TrimNorm"))
            .body("brand", nullValue())
            .body("partNumber", nullValue());
    }

    @Test
    void create_generatesSequentialCodes_secondGreaterThanFirst() {
        String token = login("admin", "Admin1234");

        String firstCode = given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(minimalProduct("ZTEST_Seq1"))
        .when()
            .post("/warehouse/products")
        .then()
            .statusCode(201)
            .extract().jsonPath().getString("code");

        String secondCode = given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(minimalProduct("ZTEST_Seq2"))
        .when()
            .post("/warehouse/products")
        .then()
            .statusCode(201)
            .extract().jsonPath().getString("code");

        int firstNumber = Integer.parseInt(firstCode.substring(4));
        int secondNumber = Integer.parseInt(secondCode.substring(4));
        assertTrue(secondNumber > firstNumber, "El correlativo debe ser monotonico: " + firstCode + " -> " + secondCode);
    }

    @Test
    void create_ignoresIsActiveInBody_alwaysActive() {
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(productJson("ZTEST_ForzarInactivo", CATEGORY_FILTROS, UNIT_UND, null,
                null, null, null, null, false))
        .when()
            .post("/warehouse/products")
        .then()
            .statusCode(201)
            .body("isActive", equalTo(true));

        assertNotNull(productRepository.find("name", "ZTEST_ForzarInactivo").firstResult());
    }

    // ---------- POST: validacion 400 (COM-001) -----------------------------------

    @Test
    void create_withMissingName_returns400_COM001() {
        expectBadRequest(productJson(null, CATEGORY_FILTROS, UNIT_UND, null, null, null, null, null, null));
    }

    @Test
    void create_withNameTooShort_returns400_COM001() {
        expectBadRequest(minimalProduct("AB"));
    }

    @Test
    void create_withMissingCategoryId_returns400_COM001() {
        expectBadRequest(productJson("ZTEST_SinCat", null, UNIT_UND, null, null, null, null, null, null));
    }

    @Test
    void create_withMissingUnitOfMeasureId_returns400_COM001() {
        expectBadRequest(productJson("ZTEST_SinUnit", CATEGORY_FILTROS, null, null, null, null, null, null, null));
    }

    @Test
    void create_withNegativeMinStock_returns400_COM001() {
        expectBadRequest(productJson("ZTEST_MinNeg", CATEGORY_FILTROS, UNIT_UND, null, null, null, "-1", null, null));
    }

    @Test
    void create_withEmptyBody_returns400_COM001() {
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
        .when()
            .post("/warehouse/products")
        .then()
            .statusCode(400)
            .body("code", equalTo("COM-001"));
    }

    private void expectBadRequest(String body) {
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(body)
        .when()
            .post("/warehouse/products")
        .then()
            .statusCode(400)
            .body("code", equalTo("COM-001"));
    }

    // ---------- POST: conflicto 409 (WH-010) -------------------------------------

    @Test
    void create_withDuplicateIdentity_caseInsensitive_returns409_WH010() {
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(productJson("ZTEST_Dup Identidad", CATEGORY_FILTROS, UNIT_UND, null, "Bosch", "P1", null, null, null))
        .when()
            .post("/warehouse/products")
        .then()
            .statusCode(201);

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(productJson("ztest_dup identidad", CATEGORY_FILTROS, UNIT_UND, null, "BOSCH", "p1", null, null, null))
        .when()
            .post("/warehouse/products")
        .then()
            .statusCode(409)
            .contentType("application/problem+json")
            .body("code", equalTo("WH-010"));
    }

    @Test
    void create_twoGenericsNoBrandNoPart_collideViaCoalesce_returns409_WH010() {
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(minimalProduct("ZTEST_Generico"))
        .when()
            .post("/warehouse/products")
        .then()
            .statusCode(201);

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(minimalProduct("ZTEST_Generico"))
        .when()
            .post("/warehouse/products")
        .then()
            .statusCode(409)
            .body("code", equalTo("WH-010"));
    }

    @Test
    void create_sameNameAndBrand_differentPartNumber_doesNotConflict_returns201() {
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(productJson("ZTEST_Variante", CATEGORY_FILTROS, UNIT_UND, null, "Bosch", "AAA", null, null, null))
        .when()
            .post("/warehouse/products")
        .then()
            .statusCode(201);

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(productJson("ZTEST_Variante", CATEGORY_FILTROS, UNIT_UND, null, "Bosch", "BBB", null, null, null))
        .when()
            .post("/warehouse/products")
        .then()
            .statusCode(201);
    }

    // ---------- POST: FK inexistente (400 WH-004) --------------------------------

    @Test
    void create_withNonexistentCategoryId_returns400_WH004() {
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(productJson("ZTEST_BadCat", 999999, UNIT_UND, null, null, null, null, null, null))
        .when()
            .post("/warehouse/products")
        .then()
            .statusCode(400)
            .body("code", equalTo("WH-004"));
    }

    @Test
    void create_withNonexistentUnitOfMeasureId_returns400_WH004() {
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(productJson("ZTEST_BadUnit", CATEGORY_FILTROS, 999999, null, null, null, null, null, null))
        .when()
            .post("/warehouse/products")
        .then()
            .statusCode(400)
            .body("code", equalTo("WH-004"));
    }

    // ---------- POST: auth / roles -----------------------------------------------

    @Test
    void create_withoutToken_returns401() {
        given()
            .contentType(ContentType.JSON)
            .body(minimalProduct("ZTEST_NoToken"))
        .when()
            .post("/warehouse/products")
        .then()
            .statusCode(401);
    }

    @Test
    void create_withSalesRole_returns403_COM003() {
        String token = login("lcampos", "Sales1234");

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(minimalProduct("ZTEST_Sales"))
        .when()
            .post("/warehouse/products")
        .then()
            .statusCode(403)
            .body("code", equalTo("COM-003"));
    }

    @Test
    void create_withDispatcherRole_returns403_COM003() {
        given()
            .header("Authorization", "Bearer " + fabricateAccessToken("disp_test", "dispatcher"))
            .contentType(ContentType.JSON)
            .body(minimalProduct("ZTEST_Dispatcher"))
        .when()
            .post("/warehouse/products")
        .then()
            .statusCode(403)
            .body("code", equalTo("COM-003"));
    }

    @Test
    void create_withWarehouseKeeperRole_returns201() {
        // created_by es FK real → token con el id de un usuario existente (admin).
        String token = fabricateTokenForUser(adminId(), "wk_test", "warehouse_keeper");

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(minimalProduct("ZTEST_WarehouseKeeper"))
        .when()
            .post("/warehouse/products")
        .then()
            .statusCode(201)
            .body("createdBy.id", equalTo(adminId()));
    }
}
