package com.scaramutti.tms.warehouse.productcategory;

import com.scaramutti.tms.shared.entity.ProductCategory;
import com.scaramutti.tms.shared.repository.ProductCategoryRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.smallrye.jwt.build.Jwt;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsInRelativeOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * Integration tests de GET/POST /warehouse/product-categories. Calco
 * estructural de CargoTypesResourceTest. Los seeds de A1 (7 categorias
 * activas) ya estan en la BD de test — no se re-siembran acá.
 */
@QuarkusTest
class WarehouseProductCategoriesResourceTest {

    @Inject ProductCategoryRepository productCategoryRepository;

    private static final String TEST_NAME_PREFIX = "ZTEST_";

    @AfterEach
    void cleanupFixtures() {
        QuarkusTransaction.requiringNew().run(() ->
            productCategoryRepository.delete("name like ?1", TEST_NAME_PREFIX + "%")
        );
    }

    private void seedProductCategory(String name, boolean isActive) {
        QuarkusTransaction.requiringNew().run(() -> {
            ProductCategory productCategory = new ProductCategory();
            productCategory.name = name;
            productCategory.isActive = isActive;
            productCategoryRepository.persist(productCategory);
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

    /** Genera un JWT valido para un rol sin usuario seedeado. */
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

    private String body(String name, String description) {
        StringBuilder sb = new StringBuilder("{\"name\":");
        sb.append(name == null ? "null" : "\"" + name + "\"");
        sb.append(",\"description\":");
        sb.append(description == null ? "null" : "\"" + description + "\"");
        sb.append("}");
        return sb.toString();
    }

    // ---------- GET: happy path / contrato ------------------------------------

    @Test
    void list_seedCategories_includesAllSevenActiveNames() {
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/warehouse/product-categories")
        .then()
            .statusCode(200)
            .body("size()", greaterThanOrEqualTo(7))
            .body("name", hasItem("Repuestos"))
            .body("name", hasItem("Lubricantes"))
            .body("name", hasItem("Neumáticos"))
            .body("name", hasItem("Filtros"))
            .body("name", hasItem("EPP"))
            .body("name", hasItem("Herramientas"))
            .body("name", hasItem("Consumibles"));
    }

    @Test
    void list_responseShape_matchesContract() {
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/warehouse/product-categories")
        .then()
            .statusCode(200)
            .body("[0].id", notNullValue())
            .body("[0].name", notNullValue())
            .body("[0].isActive", notNullValue());
    }

    @Test
    void list_returnsApplicationJsonContentType() {
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/warehouse/product-categories")
        .then()
            .statusCode(200)
            .contentType("application/json");
    }

    @Test
    void list_resultsOrderedByNameAscending() {
        seedProductCategory("ZTEST_ZZZ", true);
        seedProductCategory("ZTEST_AAA", true);
        seedProductCategory("ZTEST_MMM", true);
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/warehouse/product-categories")
        .then()
            .statusCode(200)
            .body("name", containsInRelativeOrder("ZTEST_AAA", "ZTEST_MMM", "ZTEST_ZZZ"));
    }

    // ---------- GET: filtro isActive ------------------------------------------

    @Test
    void list_withIsActiveTrue_returnsOnlyActiveCategories() {
        seedProductCategory("ZTEST_ACT", true);
        seedProductCategory("ZTEST_INA", false);
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/warehouse/product-categories?isActive=true")
        .then()
            .statusCode(200)
            .body("isActive", everyItem(equalTo(true)))
            .body("name", hasItem("ZTEST_ACT"))
            .body("name", not(hasItem("ZTEST_INA")));
    }

    @Test
    void list_withIsActiveFalse_returnsOnlyInactiveCategories() {
        seedProductCategory("ZTEST_ACT2", true);
        seedProductCategory("ZTEST_INA2", false);
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/warehouse/product-categories?isActive=false")
        .then()
            .statusCode(200)
            .body("isActive", everyItem(equalTo(false)))
            .body("name", hasItem("ZTEST_INA2"))
            .body("name", not(hasItem("ZTEST_ACT2")));
    }

    // ---------- GET: auth / roles ----------------------------------------------

    @Test
    void list_withoutToken_returns401() {
        given()
        .when()
            .get("/warehouse/product-categories")
        .then()
            .statusCode(401);
    }

    @Test
    void list_withMalformedToken_returns401_AUTH008() {
        given()
            .header("Authorization", "Bearer eyJ.malformed.token")
        .when()
            .get("/warehouse/product-categories")
        .then()
            .statusCode(401)
            .body("code", equalTo("AUTH-008"));
    }

    @Test
    void list_withAdminRole_returns200() {
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/warehouse/product-categories")
        .then()
            .statusCode(200);
    }

    @Test
    void list_withGeneralManagerRole_returns200() {
        given()
            .header("Authorization", "Bearer " + fabricateAccessToken("gm_test", "general_manager"))
        .when()
            .get("/warehouse/product-categories")
        .then()
            .statusCode(200);
    }

    @Test
    void list_withFinanceManagerRole_returns200() {
        given()
            .header("Authorization", "Bearer " + fabricateAccessToken("fm_test", "finance_manager"))
        .when()
            .get("/warehouse/product-categories")
        .then()
            .statusCode(200);
    }

    @Test
    void list_withWarehouseKeeperRole_returns200() {
        given()
            .header("Authorization", "Bearer " + fabricateAccessToken("wk_test", "warehouse_keeper"))
        .when()
            .get("/warehouse/product-categories")
        .then()
            .statusCode(200);
    }

    @Test
    void list_withSalesRole_returns403_COM003() {
        String token = login("lcampos", "Sales1234");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/warehouse/product-categories")
        .then()
            .statusCode(403)
            .body("code", equalTo("COM-003"));
    }

    @Test
    void list_withDispatcherRole_returns403_COM003() {
        given()
            .header("Authorization", "Bearer " + fabricateAccessToken("disp_test", "dispatcher"))
        .when()
            .get("/warehouse/product-categories")
        .then()
            .statusCode(403)
            .body("code", equalTo("COM-003"));
    }

    // ---------- POST: happy path ------------------------------------------------

    @Test
    void create_withValidPayload_returns201AndPersists() {
        String name = "ZTEST_Empaques";
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(body(name, "Cajas y embalajes"))
        .when()
            .post("/warehouse/product-categories")
        .then()
            .statusCode(201)
            .contentType("application/json")
            .body("id", greaterThanOrEqualTo(1))
            .body("name", equalTo(name))
            .body("description", equalTo("Cajas y embalajes"))
            .body("isActive", equalTo(true));

        ProductCategory persisted = productCategoryRepository.find("name", name).firstResult();
        org.junit.jupiter.api.Assertions.assertNotNull(persisted);
    }

    @Test
    void create_withOnlyRequiredFields_returns201WithNullDescription() {
        String name = "ZTEST_SoloNombre";
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(body(name, null))
        .when()
            .post("/warehouse/product-categories")
        .then()
            .statusCode(201)
            .body("description", nullValue())
            .body("isActive", equalTo(true));
    }

    @Test
    void create_preservesSubmittedCasing_returns201WithExactName() {
        // Sin uppercase (a diferencia de cargo-types): los seeds de A1 son
        // Title Case y el contrato solo exige unicidad case-insensitive, no
        // normalizar casing.
        String name = "ZTEST_Mixed Case Name";
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(body(name, null))
        .when()
            .post("/warehouse/product-categories")
        .then()
            .statusCode(201)
            .body("name", equalTo(name));
    }

    @Test
    void create_trimsWhitespaceInNameAndDescription() {
        String stored = "ZTEST_Trim";
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(body("  " + stored + "  ", "   "))
        .when()
            .post("/warehouse/product-categories")
        .then()
            .statusCode(201)
            .body("name", equalTo(stored))
            .body("description", nullValue());
    }

    // ---------- POST: duplicados (WH-010) ---------------------------------------

    @Test
    void create_withDuplicateName_returns409_WH010() {
        String name = "ZTEST_Dup";
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(body(name, null))
        .when()
            .post("/warehouse/product-categories")
        .then()
            .statusCode(201);

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(body(name, "otra descripcion"))
        .when()
            .post("/warehouse/product-categories")
        .then()
            .statusCode(409)
            .contentType("application/problem+json")
            .body("code", equalTo("WH-010"));
    }

    @Test
    void create_withDuplicateNameCaseInsensitive_returns409_WH010() {
        // Cierra la brecha DDL-vs-contrato (V003, indice funcional lower(name)).
        String stored = "ZTEST_DupCase";
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(body(stored, null))
        .when()
            .post("/warehouse/product-categories")
        .then()
            .statusCode(201);

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(body("ztest_dupcase", null))
        .when()
            .post("/warehouse/product-categories")
        .then()
            .statusCode(409)
            .body("code", equalTo("WH-010"));
    }

    @Test
    void create_withDuplicateAgainstSeedCategory_returns409_WH010() {
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(body("filtros", null))
        .when()
            .post("/warehouse/product-categories")
        .then()
            .statusCode(409)
            .body("code", equalTo("WH-010"));
    }

    // ---------- POST: validacion 400 (COM-001) -----------------------------------

    @Test
    void create_withMissingName_returns400_COM001() {
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body("{\"description\":\"x\"}")
        .when()
            .post("/warehouse/product-categories")
        .then()
            .statusCode(400)
            .body("code", equalTo("COM-001"));
    }

    @Test
    void create_withBlankName_returns400_COM001() {
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(body("   ", null))
        .when()
            .post("/warehouse/product-categories")
        .then()
            .statusCode(400)
            .body("code", equalTo("COM-001"));
    }

    @Test
    void create_withNameTooShort_returns400_COM001() {
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(body("AB", null))
        .when()
            .post("/warehouse/product-categories")
        .then()
            .statusCode(400)
            .body("code", equalTo("COM-001"));
    }

    @Test
    void create_withNameTooLong_returns400_COM001() {
        String token = login("admin", "Admin1234");
        String tooLong = "ZTEST_" + "X".repeat(100);

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(body(tooLong, null))
        .when()
            .post("/warehouse/product-categories")
        .then()
            .statusCode(400)
            .body("code", equalTo("COM-001"));
    }

    @Test
    void create_withEmptyBody_returns400_COM001() {
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
        .when()
            .post("/warehouse/product-categories")
        .then()
            .statusCode(400)
            .body("code", equalTo("COM-001"));
    }

    // ---------- POST: auth / roles -----------------------------------------------

    @Test
    void create_withoutToken_returns401() {
        given()
            .contentType(ContentType.JSON)
            .body(body("ZTEST_NoToken", null))
        .when()
            .post("/warehouse/product-categories")
        .then()
            .statusCode(401);
    }

    @Test
    void create_withSalesRole_returns403_COM003() {
        String token = login("lcampos", "Sales1234");

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(body("ZTEST_Sales", null))
        .when()
            .post("/warehouse/product-categories")
        .then()
            .statusCode(403)
            .body("code", equalTo("COM-003"));
    }

    @Test
    void create_withDispatcherRole_returns403_COM003() {
        given()
            .header("Authorization", "Bearer " + fabricateAccessToken("disp_test", "dispatcher"))
            .contentType(ContentType.JSON)
            .body(body("ZTEST_Dispatcher", null))
        .when()
            .post("/warehouse/product-categories")
        .then()
            .statusCode(403)
            .body("code", equalTo("COM-003"));
    }

    @Test
    void create_withFinanceManagerRole_returns201() {
        given()
            .header("Authorization", "Bearer " + fabricateAccessToken("fm_test", "finance_manager"))
            .contentType(ContentType.JSON)
            .body(body("ZTEST_FinanceManager", null))
        .when()
            .post("/warehouse/product-categories")
        .then()
            .statusCode(201);
    }

    @Test
    void create_withWarehouseKeeperRole_returns201() {
        given()
            .header("Authorization", "Bearer " + fabricateAccessToken("wk_test", "warehouse_keeper"))
            .contentType(ContentType.JSON)
            .body(body("ZTEST_WarehouseKeeper", null))
        .when()
            .post("/warehouse/product-categories")
        .then()
            .statusCode(201);
    }
}
