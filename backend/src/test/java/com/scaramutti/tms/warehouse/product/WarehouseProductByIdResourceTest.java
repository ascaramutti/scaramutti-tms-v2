package com.scaramutti.tms.warehouse.product;

import com.scaramutti.tms.shared.entity.Product;
import com.scaramutti.tms.shared.entity.User;
import com.scaramutti.tms.shared.repository.ProductRepository;
import com.scaramutti.tms.shared.repository.UserRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.smallrye.jwt.build.Jwt;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * Integration tests de GET/PUT /warehouse/products/{id} y GET .../stock. Calco
 * estructural de {@code WarehouseProductsResourceTest} (mismos helpers: ZTEST_,
 * seedProduct/seedOpeningBalance, login/fabricateAccessToken/fabricateTokenForUser,
 * cleanup @AfterEach).
 *
 * <p>Incluye la RED DE REGRESIÓN del bug D-12 (truncación MICROS de
 * {@code Product.onCreate/onUpdate}): sin el fix, el {@code updatedAt} en memoria
 * (nanos, JVM Linux) no coincide con el releído de Postgres (micros) y el ETag
 * del POST/GET no matchea → 412 espurio en el PUT siguiente.
 */
@QuarkusTest
class WarehouseProductByIdResourceTest {

    @Inject ProductRepository productRepository;
    @Inject UserRepository userRepository;
    @Inject EntityManager entityManager;

    private static final String TEST_NAME_PREFIX = "ZTEST_";
    private static final int CATEGORY_FILTROS = 4;
    private static final int CATEGORY_OTHER = 5;
    private static final int UNIT_UND = 1;

    @AfterEach
    void cleanupFixtures() {
        QuarkusTransaction.requiringNew().run(() -> {
            // Los opening_balances referencian products (FK) — se borran primero.
            entityManager.createNativeQuery(
                "DELETE FROM almacen.opening_balances WHERE product_id IN "
                    + "(SELECT id FROM almacen.products WHERE name LIKE ?1)")
                .setParameter(1, TEST_NAME_PREFIX + "%")
                .executeUpdate();
            productRepository.delete("name like ?1", TEST_NAME_PREFIX + "%");
        });
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
            product.createdBy = adminId();
            productRepository.persist(product);
            return product.id;
        });
    }

    /** Producto activo minimo (categoria Filtros, sin marca/parte, minStock dado). */
    private int seedProduct(String name, String minStock) {
        return seedProduct(name, null, CATEGORY_FILTROS, minStock, true, null, null);
    }

    /**
     * Registra un corte inicial (opening_balance) para darle stock a un producto:
     * es el unico movimiento sembrable hoy (entradas/retiros aun no tienen endpoint),
     * y la VIEW product_stock lo suma igual que cualquier ENTRADA.
     */
    private void seedOpeningBalance(int productId, String quantity) {
        QuarkusTransaction.requiringNew().run(() ->
            entityManager.createNativeQuery(
                "INSERT INTO almacen.opening_balances (product_id, quantity, registered_by) "
                    + "VALUES (?1, CAST(?2 AS NUMERIC), ?3)")
                .setParameter(1, productId)
                .setParameter(2, quantity)
                .setParameter(3, adminId())
                .executeUpdate()
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

    private int adminId() {
        User admin = userRepository.findByUsername("admin").orElseThrow();
        return admin.id;
    }

    /** Body de WarehouseProductUpdateRequest (sin unitOfMeasureId). */
    private String updateJson(
        String name, Integer categoryId, String brand, String partNumber,
        String minStock, String attributesJson, Boolean isActive
    ) {
        List<String> fields = new ArrayList<>();
        if (name != null)            fields.add("\"name\":\"" + name + "\"");
        if (categoryId != null)      fields.add("\"categoryId\":" + categoryId);
        if (brand != null)           fields.add("\"brand\":\"" + brand + "\"");
        if (partNumber != null)      fields.add("\"partNumber\":\"" + partNumber + "\"");
        if (minStock != null)        fields.add("\"minStock\":" + minStock);
        if (attributesJson != null)  fields.add("\"attributes\":" + attributesJson);
        if (isActive != null)        fields.add("\"isActive\":" + isActive);
        return "{" + String.join(",", fields) + "}";
    }

    /** Update minimo valido (solo required). */
    private String minimalUpdate(String name, int categoryId) {
        return updateJson(name, categoryId, null, null, null, null, null);
    }

    private String etagOf(int id, String token) {
        return given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/warehouse/products/" + id)
        .then()
            .statusCode(200)
            .extract().header("ETag");
    }

    // ---------- GET by id: happy path -------------------------------------------

    @Test
    void get_existingProduct_returns200_withStockAndEtagHeader() {
        int id = seedProduct("ZTEST_GetShape", "ZTEST-PRO-GETSHAPE", CATEGORY_FILTROS, "5", true, "Bosch", "F123");
        seedOpeningBalance(id, "3");
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/warehouse/products/" + id)
        .then()
            .statusCode(200)
            .header("ETag", notNullValue())
            .body("id", equalTo(id))
            .body("name", equalTo("ZTEST_GetShape"))
            .body("stock", equalTo(3.0f))
            .body("lowStock", equalTo(true))
            .body("minStock", equalTo(5.0f));
    }

    @Test
    void get_nonexistentProduct_returns404() {
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/warehouse/products/999999")
        .then()
            .statusCode(404)
            .body("code", equalTo("WH-003"));
    }

    @Test
    void get_withoutToken_returns401() {
        int id = seedProduct("ZTEST_GetNoToken", "0");

        given()
        .when()
            .get("/warehouse/products/" + id)
        .then()
            .statusCode(401);
    }

    @Test
    void get_withSalesRole_returns403_COM003() {
        int id = seedProduct("ZTEST_GetSales", "0");
        String token = login("lcampos", "Sales1234");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/warehouse/products/" + id)
        .then()
            .statusCode(403)
            .body("code", equalTo("COM-003"));
    }

    @Test
    void get_withWarehouseKeeperRole_returns200() {
        int id = seedProduct("ZTEST_GetWk", "0");

        given()
            .header("Authorization", "Bearer " + fabricateAccessToken("wk_test", "warehouse_keeper"))
        .when()
            .get("/warehouse/products/" + id)
        .then()
            .statusCode(200)
            .body("id", equalTo(id));
    }

    // ---------- RED DE REGRESION D-12 (truncacion MICROS del ETag) --------------

    @Test
    void etag_postThenGet_sameUpdatedAt_matchExactly() {
        // El POST no expone header ETag (fuera del contrato de ese endpoint), pero
        // SU updatedAt en memoria (truncado a MICROS por Product.onCreate) es la
        // fuente del ETag: sin el fix D-12, ese valor (nanos en JVM Linux) no
        // coincidiria con el mismo dato releido de Postgres (micros) en el GET.
        String token = login("admin", "Admin1234");

        Response postResponse = given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body("{\"name\":\"ZTEST_D12 Round Trip\",\"categoryId\":" + CATEGORY_FILTROS
                + ",\"unitOfMeasureId\":" + UNIT_UND + "}")
        .when()
            .post("/warehouse/products")
        .then()
            .statusCode(201)
            .extract().response();

        String updatedAtFromPost = postResponse.jsonPath().getString("updatedAt");
        int id = postResponse.jsonPath().getInt("id");
        String expectedEtag = "\"" + java.time.OffsetDateTime.parse(updatedAtFromPost) + "\"";

        String etagFromGet = given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/warehouse/products/" + id)
        .then()
            .statusCode(200)
            .extract().header("ETag");

        org.junit.jupiter.api.Assertions.assertEquals(expectedEtag, etagFromGet);

        // Sin el fix D-12, este PUT recibiria un 412 espurio: el If-Match viene
        // del GET, que a su vez debe coincidir con el updatedAt truncado del POST.
        given()
            .header("Authorization", "Bearer " + token)
            .header("If-Match", etagFromGet)
            .contentType(ContentType.JSON)
            .body(minimalUpdate("ZTEST_D12 Round Trip Edited", CATEGORY_FILTROS))
        .when()
            .put("/warehouse/products/" + id)
        .then()
            .statusCode(200);
    }

    @Test
    void etag_twoConsecutiveGets_withoutChanges_sameEtag() {
        int id = seedProduct("ZTEST_D12 StableGet", "0");
        String token = login("admin", "Admin1234");

        String firstEtag = given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/warehouse/products/" + id)
        .then()
            .statusCode(200)
            .extract().header("ETag");

        String secondEtag = given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/warehouse/products/" + id)
        .then()
            .statusCode(200)
            .extract().header("ETag");

        org.junit.jupiter.api.Assertions.assertEquals(firstEtag, secondEtag);
    }

    // ---------- PUT: happy path --------------------------------------------------

    @Test
    void update_withFullPayload_returns200_etagChanges_reGetPersists() {
        int id = seedProduct("ZTEST_PutFull", "ZTEST-PRO-PUTFULL", CATEGORY_FILTROS, "4", true, "Bosch", "F1");
        String token = login("admin", "Admin1234");
        String initialEtag = etagOf(id, token);

        String newEtag = given()
            .header("Authorization", "Bearer " + token)
            .header("If-Match", initialEtag)
            .contentType(ContentType.JSON)
            .body(updateJson("ZTEST_PutFull Edited", CATEGORY_OTHER, "Denso", "F2",
                "9", "{\"rosca\":\"1/2\"}", false))
        .when()
            .put("/warehouse/products/" + id)
        .then()
            .statusCode(200)
            .header("ETag", notNullValue())
            .body("id", equalTo(id))
            .body("name", equalTo("ZTEST_PutFull Edited"))
            .body("category.id", equalTo(CATEGORY_OTHER))
            .body("brand", equalTo("Denso"))
            .body("partNumber", equalTo("F2"))
            .body("attributes.rosca", equalTo("1/2"))
            .body("minStock", equalTo(9))
            .body("isActive", equalTo(false))
            // la unidad de medida NO cambia (P-1, inmutable, no viaja en el body).
            .body("unitOfMeasure.id", equalTo(UNIT_UND))
            .extract().header("ETag");

        org.junit.jupiter.api.Assertions.assertNotEquals(initialEtag, newEtag);

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/warehouse/products/" + id)
        .then()
            .statusCode(200)
            .header("ETag", equalTo(newEtag))
            .body("name", equalTo("ZTEST_PutFull Edited"))
            .body("isActive", equalTo(false));
    }

    @Test
    void update_omittingOptionals_setsThemNull() {
        int id = seedProduct("ZTEST_PutOmit", "ZTEST-PRO-PUTOMIT", CATEGORY_FILTROS, "4", true, "Bosch", "F1");
        String token = login("admin", "Admin1234");
        String etag = etagOf(id, token);

        given()
            .header("Authorization", "Bearer " + token)
            .header("If-Match", etag)
            .contentType(ContentType.JSON)
            .body(minimalUpdate("ZTEST_PutOmit Edited", CATEGORY_FILTROS))
        .when()
            .put("/warehouse/products/" + id)
        .then()
            .statusCode(200)
            .body("brand", nullValue())
            .body("partNumber", nullValue())
            .body("observations", nullValue())
            .body("minStock", equalTo(0))
            .body("isActive", equalTo(true));
    }

    @Test
    void update_raisingMinStockAboveStock_lowStockTrue() {
        int id = seedProduct("ZTEST_PutLowStock", "1");
        seedOpeningBalance(id, "5"); // stock 5 >= minStock 1 -> lowStock false al inicio
        String token = login("admin", "Admin1234");
        String etag = etagOf(id, token);

        given()
            .header("Authorization", "Bearer " + token)
            .header("If-Match", etag)
            .contentType(ContentType.JSON)
            .body(updateJson("ZTEST_PutLowStock Edited", CATEGORY_FILTROS, null, null, "10", null, null))
        .when()
            .put("/warehouse/products/" + id)
        .then()
            .statusCode(200)
            .body("stock", equalTo(5.0f))
            .body("minStock", equalTo(10))
            .body("lowStock", equalTo(true));
    }

    @Test
    void update_settingIsActiveFalse_persists() {
        int id = seedProduct("ZTEST_PutDeactivate", "0");
        String token = login("admin", "Admin1234");
        String etag = etagOf(id, token);

        given()
            .header("Authorization", "Bearer " + token)
            .header("If-Match", etag)
            .contentType(ContentType.JSON)
            .body(updateJson("ZTEST_PutDeactivate", CATEGORY_FILTROS, null, null, null, null, false))
        .when()
            .put("/warehouse/products/" + id)
        .then()
            .statusCode(200)
            .body("isActive", equalTo(false));
    }

    // ---------- PUT: identidad (WH-010) -------------------------------------------

    @Test
    void update_toSameIdentityForSameId_returns200_notConflict() {
        int id = seedProduct("ZTEST_PutSelfIdentity", "ZTEST-PRO-SELFID", CATEGORY_FILTROS, "0", true, "Bosch", "F1");
        String token = login("admin", "Admin1234");
        String etag = etagOf(id, token);

        // Mismo name/brand/partNumber que ya tiene (identidad sin cambio) -> NO debe
        // chocar contra si mismo (existsByIdentityIgnoreCaseExcludingId).
        given()
            .header("Authorization", "Bearer " + token)
            .header("If-Match", etag)
            .contentType(ContentType.JSON)
            .body(updateJson("ZTEST_PutSelfIdentity", CATEGORY_FILTROS, "Bosch", "F1", null, null, null))
        .when()
            .put("/warehouse/products/" + id)
        .then()
            .statusCode(200);
    }

    @Test
    void update_toAnotherProductsIdentity_returns409_WH010_caseInsensitive() {
        int other = seedProduct("ZTEST_PutOtherIdentity", "ZTEST-PRO-OTHERID", CATEGORY_FILTROS, "0", true, "Bosch", "F9");
        int id = seedProduct("ZTEST_PutMine", "0");
        String token = login("admin", "Admin1234");
        String etag = etagOf(id, token);

        given()
            .header("Authorization", "Bearer " + token)
            .header("If-Match", etag)
            .contentType(ContentType.JSON)
            .body(updateJson("ztest_putotheridentity", CATEGORY_FILTROS, "BOSCH", "f9", null, null, null))
        .when()
            .put("/warehouse/products/" + id)
        .then()
            .statusCode(409)
            .contentType("application/problem+json")
            .body("code", equalTo("WH-010"));

        org.junit.jupiter.api.Assertions.assertTrue(other > 0);
    }

    // ---------- PUT: FK inexistente (400 WH-004) ----------------------------------

    @Test
    void update_withNonexistentOrInactiveCategoryId_returns400_WH004() {
        int id = seedProduct("ZTEST_PutBadCat", "0");
        String token = login("admin", "Admin1234");
        String etag = etagOf(id, token);

        given()
            .header("Authorization", "Bearer " + token)
            .header("If-Match", etag)
            .contentType(ContentType.JSON)
            .body(minimalUpdate("ZTEST_PutBadCat", 999999))
        .when()
            .put("/warehouse/products/" + id)
        .then()
            .statusCode(400)
            .body("code", equalTo("WH-004"));
    }

    // ---------- PUT: validacion 400 (COM-001) -------------------------------------

    @Test
    void update_withNameTooShort_returns400_COM001() {
        int id = seedProduct("ZTEST_PutNameShort", "0");
        expectUpdateBadRequest(id, minimalUpdate("AB", CATEGORY_FILTROS));
    }

    @Test
    void update_withMissingName_returns400_COM001() {
        int id = seedProduct("ZTEST_PutNoName", "0");
        expectUpdateBadRequest(id, updateJson(null, CATEGORY_FILTROS, null, null, null, null, null));
    }

    @Test
    void update_withNegativeMinStock_returns400_COM001() {
        int id = seedProduct("ZTEST_PutNegMinStock", "0");
        expectUpdateBadRequest(id, updateJson("ZTEST_PutNegMinStock", CATEGORY_FILTROS, null, null, "-1", null, null));
    }

    @Test
    void update_withEmptyBody_returns400_COM001() {
        int id = seedProduct("ZTEST_PutEmptyBody", "0");
        expectUpdateBadRequest(id, "{}");
    }

    private void expectUpdateBadRequest(int id, String body) {
        String token = login("admin", "Admin1234");
        String etag = etagOf(id, token);

        given()
            .header("Authorization", "Bearer " + token)
            .header("If-Match", etag)
            .contentType(ContentType.JSON)
            .body(body)
        .when()
            .put("/warehouse/products/" + id)
        .then()
            .statusCode(400)
            .body("code", equalTo("COM-001"));
    }

    // ---------- PUT: optimistic locking (412 COM-004) -----------------------------

    @Test
    void update_withoutIfMatch_returns412_COM004() {
        int id = seedProduct("ZTEST_PutNoIfMatch", "0");
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(minimalUpdate("ZTEST_PutNoIfMatch", CATEGORY_FILTROS))
        .when()
            .put("/warehouse/products/" + id)
        .then()
            .statusCode(412)
            .body("code", equalTo("COM-004"));
    }

    @Test
    void update_withStaleIfMatch_returns412_COM004() {
        int id = seedProduct("ZTEST_PutStale", "0");
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
            .header("If-Match", "\"2000-01-01T00:00:00.000000Z\"")
            .contentType(ContentType.JSON)
            .body(minimalUpdate("ZTEST_PutStale", CATEGORY_FILTROS))
        .when()
            .put("/warehouse/products/" + id)
        .then()
            .statusCode(412)
            .body("code", equalTo("COM-004"));
    }

    // ---------- PUT: 404 -----------------------------------------------------------

    @Test
    void update_nonexistentProduct_returns404() {
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
            .header("If-Match", "\"2000-01-01T00:00:00.000000Z\"")
            .contentType(ContentType.JSON)
            .body(minimalUpdate("ZTEST_PutNotFound", CATEGORY_FILTROS))
        .when()
            .put("/warehouse/products/999999")
        .then()
            .statusCode(404)
            .body("code", equalTo("WH-003"));
    }

    // ---------- PUT: auth / roles ---------------------------------------------------

    @Test
    void update_withoutToken_returns401() {
        int id = seedProduct("ZTEST_PutNoToken", "0");

        given()
            .contentType(ContentType.JSON)
            .body(minimalUpdate("ZTEST_PutNoToken", CATEGORY_FILTROS))
        .when()
            .put("/warehouse/products/" + id)
        .then()
            .statusCode(401);
    }

    @Test
    void update_withSalesRole_returns403_COM003() {
        int id = seedProduct("ZTEST_PutSales", "0");
        String token = login("lcampos", "Sales1234");

        given()
            .header("Authorization", "Bearer " + token)
            .header("If-Match", "\"2000-01-01T00:00:00.000000Z\"")
            .contentType(ContentType.JSON)
            .body(minimalUpdate("ZTEST_PutSales", CATEGORY_FILTROS))
        .when()
            .put("/warehouse/products/" + id)
        .then()
            .statusCode(403)
            .body("code", equalTo("COM-003"));
    }

    @Test
    void update_withWarehouseKeeperRole_returns200() {
        int id = seedProduct("ZTEST_PutWk", "0");
        String token = login("admin", "Admin1234");
        String etag = etagOf(id, token);

        given()
            .header("Authorization", "Bearer " + fabricateAccessToken("wk_test", "warehouse_keeper"))
            .header("If-Match", etag)
            .contentType(ContentType.JSON)
            .body(minimalUpdate("ZTEST_PutWk Edited", CATEGORY_FILTROS))
        .when()
            .put("/warehouse/products/" + id)
        .then()
            .statusCode(200)
            .body("name", equalTo("ZTEST_PutWk Edited"));
    }
}
