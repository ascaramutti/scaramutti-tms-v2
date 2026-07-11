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
import java.util.HashMap;
import java.util.Set;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

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
}
