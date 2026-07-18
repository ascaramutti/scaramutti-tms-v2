package com.scaramutti.tms.warehouse.product;

import com.scaramutti.tms.shared.entity.Product;
import com.scaramutti.tms.shared.repository.ProductRepository;
import com.scaramutti.tms.support.WarehouseTestData;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import static com.scaramutti.tms.support.TestAuth.fabricateAccessToken;
import static com.scaramutti.tms.support.TestAuth.fabricateTokenForUser;
import static com.scaramutti.tms.support.TestAuth.login;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsInRelativeOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests de GET/POST /warehouse/products. Calco estructural de
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
    @Inject EntityManager entityManager;
    @Inject WarehouseTestData fixtures;

    private static final int CATEGORY_FILTROS = 4;
    private static final int CATEGORY_OTHER = 5;
    private static final int UNIT_UND = 1;

    @AfterEach
    void cleanupFixtures() {
        QuarkusTransaction.requiringNew().run(() -> fixtures.deleteWarehouseTestData());
    }

    /**
     * Siembra un producto directamente (la entidad, no via POST) para controlar
     * name/code/categoria/minStock/isActive/marca/parte. Devuelve el id generado.
     */
    private int seedProduct(
        String name, String code, int categoryId, String minStock,
        boolean isActive, String brand, String partNumber
    ) {
        return QuarkusTransaction.requiringNew().call(() -> {
            Product product = new Product();
            product.code = code;
            product.name = name;
            product.categoryId = categoryId;
            product.unitOfMeasureId = UNIT_UND;
            product.brand = brand;
            product.partNumber = partNumber;
            product.attributes = new HashMap<>();
            product.minStock = new BigDecimal(minStock);
            product.isActive = isActive;
            product.createdBy = fixtures.adminId();
            productRepository.persist(product);
            return product.id;
        });
    }

    /** Producto activo minimo (categoria Filtros, sin marca/parte, minStock dado). */
    private int seedProduct(String name, String minStock) {
        return seedProduct(name, null, CATEGORY_FILTROS, minStock, true, null, null);
    }

    /**
     * Registra un corte inicial (opening_balance) por SQL nativo para darle stock a
     * un producto. Se queda local a proposito: la variante de support pasa por el
     * endpoint (que exige producto activo) y estos tests tambien dan stock a
     * productos que luego se inactivan.
     */
    private void seedOpeningBalance(int productId, String quantity) {
        QuarkusTransaction.requiringNew().run(() ->
            entityManager.createNativeQuery(
                "INSERT INTO almacen.opening_balances (product_id, quantity, registered_by) "
                    + "VALUES (?1, CAST(?2 AS NUMERIC), ?3)")
                .setParameter(1, productId)
                .setParameter(2, quantity)
                .setParameter(3, fixtures.adminId())
                .executeUpdate()
        );
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

    // ---------- GET: shape / paginacion -----------------------------------------

    @Test
    void list_responseShape_matchesContract() {
        int id = seedProduct("ZTEST_Shape Filtro", "ZTEST-PRO-SHAPE", CATEGORY_FILTROS, "5", true, "Bosch", "F123");
        seedOpeningBalance(id, "3");
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/warehouse/products?q=ZTEST_Shape")
        .then()
            .statusCode(200)
            .body("page", equalTo(0))
            .body("size", equalTo(20))
            .body("content[0].id", equalTo(id))
            .body("content[0].code", equalTo("ZTEST-PRO-SHAPE"))
            .body("content[0].name", equalTo("ZTEST_Shape Filtro"))
            .body("content[0].category.id", equalTo(CATEGORY_FILTROS))
            .body("content[0].category.name", equalTo("Filtros"))
            .body("content[0].unitOfMeasure.id", equalTo(UNIT_UND))
            .body("content[0].unitOfMeasure.code", equalTo("UND"))
            .body("content[0].brand", equalTo("Bosch"))
            .body("content[0].partNumber", equalTo("F123"))
            .body("content[0].attributes", notNullValue())
            // minStock/stock salen de columnas NUMERIC(12,2) -> float en el JSON.
            .body("content[0].minStock", equalTo(5.0f))
            .body("content[0].stock", equalTo(3.0f))
            .body("content[0].lowStock", equalTo(true))
            .body("content[0].isActive", equalTo(true))
            .body("content[0].createdBy.username", equalTo("admin"))
            .body("content[0].createdAt", notNullValue())
            .body("content[0].updatedAt", notNullValue());
    }

    @Test
    void list_returnsApplicationJsonContentType() {
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/warehouse/products")
        .then()
            .statusCode(200)
            .contentType("application/json");
    }

    @Test
    void list_withoutQ_orderedByNameAscending() {
        seedProduct("ZTEST_ZZZ Item", "0");
        seedProduct("ZTEST_AAA Item", "0");
        seedProduct("ZTEST_MMM Item", "0");
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/warehouse/products?size=100")
        .then()
            .statusCode(200)
            .body("content.name", containsInRelativeOrder("ZTEST_AAA Item", "ZTEST_MMM Item", "ZTEST_ZZZ Item"));
    }

    // ---------- GET: stock y lowStock leidos de la VIEW (RN-WH1/WH11) -----------

    @Test
    void list_readsStockFromView_lowStockStrictInequality() {
        // stock 3 < minStock 5 -> lowStock true; stock 5 == minStock 5 -> false (estricto).
        int low = seedProduct("ZTEST_LowStock", "5");
        seedOpeningBalance(low, "3");
        int exact = seedProduct("ZTEST_ExactStock", "5");
        seedOpeningBalance(exact, "5");
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/warehouse/products?q=ZTEST_LowStock")
        .then()
            .statusCode(200)
            .body("content[0].stock", equalTo(3.0f))
            .body("content[0].lowStock", equalTo(true));

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/warehouse/products?q=ZTEST_ExactStock")
        .then()
            .statusCode(200)
            .body("content[0].stock", equalTo(5.0f))
            .body("content[0].lowStock", equalTo(false));
    }

    @Test
    void list_productWithoutMovements_stockZero() {
        seedProduct("ZTEST_NoMoves", "2");
        String token = login("admin", "Admin1234");

        float stock = given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/warehouse/products?q=ZTEST_NoMoves")
        .then()
            .statusCode(200)
            .body("content[0].lowStock", equalTo(true)) // 0 < 2
            .extract().jsonPath().getFloat("content[0].stock");

        org.junit.jupiter.api.Assertions.assertEquals(0f, stock);
    }

    @Test
    void list_withLowOnly_returnsOnlyLowStockProducts() {
        int low = seedProduct("ZTEST_LowOnly Bajo", "10");
        seedOpeningBalance(low, "2");                     // 2 < 10 -> bajo
        int ok = seedProduct("ZTEST_LowOnly Ok", "1");
        seedOpeningBalance(ok, "50");                     // 50 >= 1 -> no bajo
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/warehouse/products?q=ZTEST_LowOnly&lowOnly=true")
        .then()
            .statusCode(200)
            .body("content.name", hasItem("ZTEST_LowOnly Bajo"))
            .body("content.name", not(hasItem("ZTEST_LowOnly Ok")))
            .body("content.lowStock", everyItem(equalTo(true)));
    }

    // ---------- GET: filtros --------------------------------------------------

    @Test
    void list_withCategoryId_filtersByCategory() {
        seedProduct("ZTEST_CatFiltros", null, CATEGORY_FILTROS, "0", true, null, null);
        seedProduct("ZTEST_CatOtra", null, CATEGORY_OTHER, "0", true, null, null);
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/warehouse/products?q=ZTEST_Cat&categoryId=" + CATEGORY_FILTROS)
        .then()
            .statusCode(200)
            .body("content.name", hasItem("ZTEST_CatFiltros"))
            .body("content.name", not(hasItem("ZTEST_CatOtra")))
            .body("content.category.id", everyItem(equalTo(CATEGORY_FILTROS)));
    }

    @Test
    void list_withIsActiveTrue_returnsOnlyActiveProducts() {
        seedProduct("ZTEST_Activo", null, CATEGORY_FILTROS, "0", true, null, null);
        seedProduct("ZTEST_Inactivo", null, CATEGORY_FILTROS, "0", false, null, null);
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/warehouse/products?q=ZTEST_&isActive=true")
        .then()
            .statusCode(200)
            .body("content.isActive", everyItem(equalTo(true)))
            .body("content.name", hasItem("ZTEST_Activo"))
            .body("content.name", not(hasItem("ZTEST_Inactivo")));
    }

    @Test
    void list_withCustomPageAndSize_returnsCorrectSlice() {
        for (int i = 0; i < 12; i++) {
            seedProduct(String.format("ZTEST_Page%02d", i), "0");
        }
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/warehouse/products?q=ZTEST_Page&page=1&size=5")
        .then()
            .statusCode(200)
            .body("page", equalTo(1))
            .body("size", equalTo(5))
            .body("totalElements", equalTo(12))
            .body("totalPages", equalTo(3))
            .body("content.size()", equalTo(5))
            .body("first", equalTo(false))
            .body("last", equalTo(false));
    }

    @Test
    void list_pageBeyondTotalPages_returnsEmptyContentAndLastTrue() {
        seedProduct("ZTEST_Overflow", "0");
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/warehouse/products?q=ZTEST_Overflow&page=99")
        .then()
            .statusCode(200)
            .body("content.size()", equalTo(0))
            .body("empty", equalTo(true))
            .body("last", equalTo(true));
    }

    @Test
    void list_withPageNegative_returns400_COM001() {
        expectListBadRequest("/warehouse/products?page=-1");
    }

    @Test
    void list_withSizeAboveMax_returns400_COM001() {
        expectListBadRequest("/warehouse/products?size=101");
    }

    @Test
    void list_withQTooShort_returns400_COM001() {
        expectListBadRequest("/warehouse/products?q=ab");
    }

    private void expectListBadRequest(String path) {
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get(path)
        .then()
            .statusCode(400)
            .body("code", equalTo("COM-001"));
    }

    // ---------- GET: RN-WH14 — busqueda multi-palabra tokenizada ----------------

    @Test
    void list_withQCrossFieldWords_matchesWhenWordsSplitAcrossNameAndBrand() {
        // "Filtro" en el name, "Bosch" en el brand — cada palabra en un campo distinto.
        seedProduct("ZTEST_Filtro Aceite", null, CATEGORY_FILTROS, "0", true, "Bosch", null);
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/warehouse/products?q=Filtro Bosch")
        .then()
            .statusCode(200)
            .body("content.name", hasItem("ZTEST_Filtro Aceite"));
    }

    @Test
    void list_withQWordMatchingCode_matches() {
        // una palabra matchea el name, la otra el code (RN-WH14: code es campo buscable).
        seedProduct("ZTEST_Correa", "ZTEST-PRO-8891", CATEGORY_FILTROS, "0", true, null, null);
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/warehouse/products?q=Correa 8891")
        .then()
            .statusCode(200)
            .body("content.name", hasItem("ZTEST_Correa"));
    }

    @Test
    void list_withQOneWordNotMatchingAnywhere_excludesProduct() {
        seedProduct("ZTEST_Filtro Motor", null, CATEGORY_FILTROS, "0", true, "Bosch", null);
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/warehouse/products?q=Filtro Inexistente999")
        .then()
            .statusCode(200)
            .body("content.name", not(hasItem("ZTEST_Filtro Motor")));
    }

    @Test
    void list_withQCaseInsensitiveMultiWord_matches() {
        seedProduct("ZTEST_Filtro Diesel", null, CATEGORY_FILTROS, "0", true, "Bosch", null);
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/warehouse/products?q=filtro bosch")
        .then()
            .statusCode(200)
            .body("content.name", hasItem("ZTEST_Filtro Diesel"));
    }

    @Test
    void list_withQMultipleAndEdgeSpaces_doesNotBreakAndMatching() {
        seedProduct("ZTEST_Filtro Norte", null, CATEGORY_FILTROS, "0", true, "Bosch", null);
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
            .queryParam("q", "  Filtro   Bosch  ")
        .when()
            .get("/warehouse/products")
        .then()
            .statusCode(200)
            .body("content.name", hasItem("ZTEST_Filtro Norte"));
    }

    @Test
    void list_withQAgainstNullBrandProduct_negativeControl_noSqlError() {
        // "Filtro" matchea name, "55512" no matchea (brand/partNumber null) -> AND falla,
        // y sobre todo: las columnas null no deben romper la query (500).
        seedProduct("ZTEST_Filtro Simple", null, CATEGORY_FILTROS, "0", true, null, null);
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/warehouse/products?q=Filtro 55512")
        .then()
            .statusCode(200)
            .body("content.name", not(hasItem("ZTEST_Filtro Simple")));
    }

    // ---------- GET: auth / roles ------------------------------------------------

    @Test
    void list_withoutToken_returns401() {
        given()
        .when()
            .get("/warehouse/products")
        .then()
            .statusCode(401);
    }

    @Test
    void list_withAdminRole_returns200() {
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/warehouse/products")
        .then()
            .statusCode(200);
    }

    @Test
    void list_withOperationsManagerRole_returns200() {
        given()
            .header("Authorization", "Bearer " + fabricateAccessToken("om_test", "operations_manager"))
        .when()
            .get("/warehouse/products")
        .then()
            .statusCode(200);
    }

    @Test
    void list_withFinanceManagerRole_returns200() {
        given()
            .header("Authorization", "Bearer " + fabricateAccessToken("fm_test", "finance_manager"))
        .when()
            .get("/warehouse/products")
        .then()
            .statusCode(200);
    }

    @Test
    void list_withWarehouseKeeperRole_returns200() {
        given()
            .header("Authorization", "Bearer " + fabricateAccessToken("wk_test", "warehouse_keeper"))
        .when()
            .get("/warehouse/products")
        .then()
            .statusCode(200);
    }

    @Test
    void list_withSalesRole_returns403_COM003() {
        String token = login("lcampos", "Sales1234");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/warehouse/products")
        .then()
            .statusCode(403)
            .body("code", equalTo("COM-003"));
    }

    @Test
    void list_withDispatcherRole_returns403_COM003() {
        given()
            .header("Authorization", "Bearer " + fabricateAccessToken("disp_test", "dispatcher"))
        .when()
            .get("/warehouse/products")
        .then()
            .statusCode(403)
            .body("code", equalTo("COM-003"));
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
        String token = fabricateTokenForUser(fixtures.adminId(), "wk_test", "warehouse_keeper");

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(minimalProduct("ZTEST_WarehouseKeeper"))
        .when()
            .post("/warehouse/products")
        .then()
            .statusCode(201)
            .body("createdBy.id", equalTo(fixtures.adminId()));
    }
}
