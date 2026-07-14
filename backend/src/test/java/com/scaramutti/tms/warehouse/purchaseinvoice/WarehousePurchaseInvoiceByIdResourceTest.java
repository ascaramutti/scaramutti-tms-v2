package com.scaramutti.tms.warehouse.purchaseinvoice;

import com.scaramutti.tms.shared.entity.Product;
import com.scaramutti.tms.shared.entity.Supplier;
import com.scaramutti.tms.shared.entity.User;
import com.scaramutti.tms.shared.repository.ProductRepository;
import com.scaramutti.tms.shared.repository.SupplierRepository;
import com.scaramutti.tms.shared.repository.UserRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * Integration tests de GET /warehouse/purchase-invoices/{id} (A9). Hermético (prefijo
 * ZTEST_, cleanup en orden FK). Las facturas base se crean vía el POST de A8.
 */
@QuarkusTest
class WarehousePurchaseInvoiceByIdResourceTest {

    private static final String TEST_NAME_PREFIX = "ZTEST_";
    private static final int CATEGORY_FILTROS = 4;
    private static final int UNIT_UND = 1;

    @Inject ProductRepository productRepository;
    @Inject SupplierRepository supplierRepository;
    @Inject UserRepository userRepository;
    @Inject EntityManager entityManager;

    @AfterEach
    void cleanupFixtures() {
        QuarkusTransaction.requiringNew().run(() -> {
            entityManager.createNativeQuery(
                "DELETE FROM almacen.purchase_invoice_items WHERE product_id IN "
                    + "(SELECT id FROM almacen.products WHERE name LIKE ?1)")
                .setParameter(1, TEST_NAME_PREFIX + "%").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM almacen.purchase_invoices WHERE invoice_number LIKE 'ZTEST%'")
                .executeUpdate();
            entityManager.createNativeQuery("DELETE FROM almacen.products WHERE name LIKE ?1")
                .setParameter(1, TEST_NAME_PREFIX + "%").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM almacen.suppliers WHERE name LIKE ?1")
                .setParameter(1, TEST_NAME_PREFIX + "%").executeUpdate();
        });
    }

    private int adminId() {
        User admin = userRepository.findByUsername("admin").orElseThrow();
        return admin.id;
    }

    private int seedProduct(String name) {
        return QuarkusTransaction.requiringNew().call(() -> {
            Product product = new Product();
            product.name = name;
            product.categoryId = CATEGORY_FILTROS;
            product.unitOfMeasureId = UNIT_UND;
            product.attributes = new HashMap<>();
            product.minStock = BigDecimal.ZERO;
            product.isActive = true;
            product.createdBy = adminId();
            productRepository.persist(product);
            return product.id;
        });
    }

    private int seedSupplier(String name) {
        return QuarkusTransaction.requiringNew().call(() -> {
            Supplier supplier = new Supplier();
            supplier.name = name;
            supplier.isActive = true;
            supplierRepository.persist(supplier);
            return supplier.id;
        });
    }

    private int currencyId(String code) {
        return ((Number) entityManager.createNativeQuery("SELECT id FROM public.currencies WHERE code = ?1")
            .setParameter(1, code).getSingleResult()).intValue();
    }

    private String login(String username, String password) {
        return given().contentType(ContentType.JSON)
            .body("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}")
        .when().post("/auth/login")
        .then().statusCode(200).extract().jsonPath().getString("token");
    }

    private String itemJson(int productId, String quantity, String unitPrice) {
        return "{\"productId\":" + productId + ",\"quantity\":" + quantity + ",\"unitPrice\":" + unitPrice + "}";
    }

    private int createInvoice(int supplierId, String invoiceNumber, String itemsJson, String token) {
        String body = "{\"supplierId\":" + supplierId + ",\"invoiceNumber\":\"" + invoiceNumber
            + "\",\"invoiceDate\":\"2026-07-02\",\"currencyId\":" + currencyId("USD") + ",\"items\":[" + itemsJson + "]}";
        return given().header("Authorization", "Bearer " + token).contentType(ContentType.JSON).body(body)
        .when().post("/warehouse/purchase-invoices")
        .then().statusCode(201).extract().jsonPath().getInt("id");
    }

    // ---------- GET /{id} ---------------------------------------------------------

    @Test
    void get_existingInvoice_returns200WithDetailAndEtag() {
        int supplierId = seedSupplier("ZTEST_Prov Get");
        int productId = seedProduct("ZTEST_PI Get");
        String token = login("admin", "Admin1234");
        int id = createInvoice(supplierId, "ZTEST-GET-001", itemJson(productId, "10", "45.00"), token);

        given().header("Authorization", "Bearer " + token)
        .when().get("/warehouse/purchase-invoices/" + id)
        .then().statusCode(200)
            .header("ETag", notNullValue())
            .body("id", equalTo(id))
            .body("supplier.id", equalTo(supplierId))
            .body("items", hasSize(1))
            .body("total", equalTo(450.00f))
            .body("status", equalTo("ACTIVE"))
            .body("registeredBy.username", equalTo("admin"))
            .body("lastEdit", nullValue())
            .body("cancelReason", nullValue());
    }

    @Test
    void get_nonexistentId_returns404_WH003() {
        String token = login("admin", "Admin1234");
        given().header("Authorization", "Bearer " + token)
        .when().get("/warehouse/purchase-invoices/999999")
        .then().statusCode(404).body("code", equalTo("WH-003"));
    }

    @Test
    void get_idNonNumeric_returns404() {
        String token = login("admin", "Admin1234");
        given().header("Authorization", "Bearer " + token)
        .when().get("/warehouse/purchase-invoices/abc")
        .then().statusCode(404);
    }

    @Test
    void get_withoutToken_returns401() {
        given().when().get("/warehouse/purchase-invoices/1").then().statusCode(401);
    }

    @Test
    void get_withSalesRole_returns403_COM003() {
        String token = login("lcampos", "Sales1234");
        given().header("Authorization", "Bearer " + token)
        .when().get("/warehouse/purchase-invoices/1")
        .then().statusCode(403).body("code", equalTo("COM-003"));
    }
}
