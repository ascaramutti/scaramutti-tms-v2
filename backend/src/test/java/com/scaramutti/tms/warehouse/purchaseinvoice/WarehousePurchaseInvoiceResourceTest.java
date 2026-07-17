package com.scaramutti.tms.warehouse.purchaseinvoice;

import com.scaramutti.tms.support.WarehouseTestData;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static com.scaramutti.tms.support.TestAuth.fabricateAccessToken;
import static com.scaramutti.tms.support.TestAuth.fabricateTokenForUser;
import static com.scaramutti.tms.support.TestAuth.login;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * Integration tests de GET/POST /warehouse/purchase-invoices (A8). Hermético: siembra
 * sus propios productos/proveedores con prefijo {@code ZTEST_} y limpia en
 * {@code @AfterEach}. La siembra de facturas CANCELLED (para el caso RN-WH5 de número
 * liberado) usa SQL nativo, porque el endpoint de anulación es A9.
 */
@QuarkusTest
class WarehousePurchaseInvoiceResourceTest {

    @Inject WarehouseTestData fixtures;
    @Inject EntityManager entityManager;

    @AfterEach
    void cleanupFixtures() {
        QuarkusTransaction.requiringNew().run(() -> fixtures.deleteWarehouseTestData());
    }

    // ---------- fixtures --------------------------------------------------------

    /** Factura CANCELLED sembrada por SQL nativo (la anulación es A9). */
    private void seedCancelledInvoice(int supplierId, String invoiceNumber) {
        QuarkusTransaction.requiringNew().run(() ->
            entityManager.createNativeQuery(
                "INSERT INTO almacen.purchase_invoices "
                    + "(supplier_id, invoice_number, invoice_date, currency_id, registered_by, created_at, updated_at, "
                    + "status, cancel_reason, cancelled_by, cancelled_at) "
                    + "VALUES (?1, ?2, CURRENT_DATE, (SELECT id FROM public.currencies WHERE code = 'USD'), ?3, "
                    + "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'CANCELLED', 'ZTEST anulada', ?3, CURRENT_TIMESTAMP)")
                .setParameter(1, supplierId)
                .setParameter(2, invoiceNumber)
                .setParameter(3, fixtures.adminId())
                .executeUpdate());
    }

    private String itemJson(int productId, String quantity, String unitPrice) {
        return "{\"productId\":" + productId + ",\"quantity\":" + quantity + ",\"unitPrice\":" + unitPrice + "}";
    }

    /** Body de una entrada de 1 ítem, sin guía ni observaciones. */
    private String invoiceBody(int supplierId, String invoiceNumber, int currencyId, String itemsJson) {
        return "{\"supplierId\":" + supplierId + ",\"invoiceNumber\":\"" + invoiceNumber
            + "\",\"invoiceDate\":\"2026-07-02\",\"currencyId\":" + currencyId + ",\"items\":[" + itemsJson + "]}";
    }

    // ---------- POST: happy path --------------------------------------------------

    @Test
    void create_oneItem_returns201WithEtagDerivedTotalsAndStockIncreased() {
        int supplierId = fixtures.seedSupplier("ZTEST_Proveedor Uno");
        int productId = fixtures.seedProduct("ZTEST_PI Prod1");
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(invoiceBody(supplierId, "ZTEST-F001-001", fixtures.currencyId("USD"), itemJson(productId, "10", "45.00")))
        .when()
            .post("/warehouse/purchase-invoices")
        .then()
            .statusCode(201)
            .header("ETag", notNullValue())
            .body("id", notNullValue())
            .body("supplier.id", equalTo(supplierId))
            .body("currency.code", equalTo("USD"))
            .body("items", hasSize(1))
            .body("items[0].product.id", equalTo(productId))
            .body("items[0].subtotal", equalTo(450.00f))
            .body("total", equalTo(450.00f))
            .body("status", equalTo("ACTIVE"))
            .body("guideNumber", nullValue())
            .body("observations", nullValue())
            .body("cancelReason", nullValue())
            .body("lastEdit", nullValue())
            .body("registeredBy.username", equalTo("admin"))
            .body("createdAt", notNullValue())
            .body("updatedAt", notNullValue());

        fixtures.assertStock(productId, "10", token);
    }

    @Test
    void create_multipleItems_returns201WithDerivedTotalAndEachStockIncreased() {
        int supplierId = fixtures.seedSupplier("ZTEST_Proveedor Multi");
        int productA = fixtures.seedProduct("ZTEST_PI ProdA");
        int productB = fixtures.seedProduct("ZTEST_PI ProdB");
        String token = login("admin", "Admin1234");

        String items = itemJson(productA, "10", "45.00") + "," + itemJson(productB, "24", "18.50");

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(invoiceBody(supplierId, "ZTEST-F001-002", fixtures.currencyId("USD"), items))
        .when()
            .post("/warehouse/purchase-invoices")
        .then()
            .statusCode(201)
            .body("items", hasSize(2))
            .body("total", equalTo(894.00f));

        fixtures.assertStock(productA, "10", token);
        fixtures.assertStock(productB, "24", token);
    }

    @Test
    void create_withGuideNumberAndObservations_returnsBothPresent() {
        int supplierId = fixtures.seedSupplier("ZTEST_Proveedor Guia");
        int productId = fixtures.seedProduct("ZTEST_PI Guia");
        String token = login("admin", "Admin1234");

        String body = "{\"supplierId\":" + supplierId + ",\"invoiceNumber\":\"ZTEST-F001-003\","
            + "\"invoiceDate\":\"2026-07-02\",\"guideNumber\":\"ZTEST-T001-0001\","
            + "\"currencyId\":" + fixtures.currencyId("PEN") + ",\"observations\":\"ZTEST compra repuestos\","
            + "\"items\":[" + itemJson(productId, "5", "10.00") + "]}";

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(body)
        .when()
            .post("/warehouse/purchase-invoices")
        .then()
            .statusCode(201)
            .body("guideNumber", equalTo("ZTEST-T001-0001"))
            .body("observations", equalTo("ZTEST compra repuestos"))
            .body("currency.code", equalTo("PEN"));
    }

    @Test
    void create_unitPriceZero_returns201Bonificacion() {
        int supplierId = fixtures.seedSupplier("ZTEST_Proveedor Bonif");
        int productId = fixtures.seedProduct("ZTEST_PI Bonif");
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(invoiceBody(supplierId, "ZTEST-F001-004", fixtures.currencyId("USD"), itemJson(productId, "5", "0")))
        .when()
            .post("/warehouse/purchase-invoices")
        .then()
            .statusCode(201)
            .body("items[0].subtotal", equalTo(0))
            .body("total", equalTo(0));
    }

    @Test
    void create_sameProductInTwoLines_returns201BothLinesCounted() {
        int supplierId = fixtures.seedSupplier("ZTEST_Proveedor DosLineas");
        int productId = fixtures.seedProduct("ZTEST_PI DosLineas");
        String token = login("admin", "Admin1234");

        String items = itemJson(productId, "3", "10.00") + "," + itemJson(productId, "7", "10.00");

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(invoiceBody(supplierId, "ZTEST-F001-005", fixtures.currencyId("USD"), items))
        .when()
            .post("/warehouse/purchase-invoices")
        .then()
            .statusCode(201)
            .body("items", hasSize(2))
            .body("total", equalTo(100.00f));

        // Ambas líneas suman al stock (stock fungible: cada línea es independiente).
        fixtures.assertStock(productId, "10", token);
    }

    // ---------- POST: WH-002 (duplicado activo) -----------------------------------

    @Test
    void create_duplicateActiveSameSupplierAndNumber_returns409_WH002() {
        int supplierId = fixtures.seedSupplier("ZTEST_Proveedor Dup");
        int productId = fixtures.seedProduct("ZTEST_PI Dup");
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(invoiceBody(supplierId, "ZTEST-DUP-001", fixtures.currencyId("USD"), itemJson(productId, "10", "5.00")))
        .when()
            .post("/warehouse/purchase-invoices")
        .then()
            .statusCode(201);

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(invoiceBody(supplierId, "ZTEST-DUP-001", fixtures.currencyId("USD"), itemJson(productId, "3", "5.00")))
        .when()
            .post("/warehouse/purchase-invoices")
        .then()
            .statusCode(409)
            .body("code", equalTo("WH-002"));
    }

    @Test
    void create_invoiceNumberWithBorderSpaces_isTrimmed_thenSameNumberConflicts() {
        int supplierId = fixtures.seedSupplier("ZTEST_Proveedor Trim");
        int productId = fixtures.seedProduct("ZTEST_PI Trim");
        String token = login("admin", "Admin1234");

        // El número llega con espacios de borde (copy-paste de Excel): se registra trimmed.
        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(invoiceBody(supplierId, "  ZTEST-TRIM-001  ", fixtures.currencyId("USD"), itemJson(productId, "1", "1.00")))
        .when()
            .post("/warehouse/purchase-invoices")
        .then()
            .statusCode(201)
            .body("invoiceNumber", equalTo("ZTEST-TRIM-001"));

        // La misma factura tipeada sin espacios debe chocar (no registrarse dos veces).
        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(invoiceBody(supplierId, "ZTEST-TRIM-001", fixtures.currencyId("USD"), itemJson(productId, "1", "1.00")))
        .when()
            .post("/warehouse/purchase-invoices")
        .then()
            .statusCode(409)
            .body("code", equalTo("WH-002"));
    }

    @Test
    void create_sameNumberDifferentSupplier_returns201NoConflict() {
        int supplierA = fixtures.seedSupplier("ZTEST_Proveedor NumA");
        int supplierB = fixtures.seedSupplier("ZTEST_Proveedor NumB");
        int productId = fixtures.seedProduct("ZTEST_PI Num");
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(invoiceBody(supplierA, "ZTEST-SAME-001", fixtures.currencyId("USD"), itemJson(productId, "1", "1.00")))
        .when()
            .post("/warehouse/purchase-invoices")
        .then()
            .statusCode(201);

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(invoiceBody(supplierB, "ZTEST-SAME-001", fixtures.currencyId("USD"), itemJson(productId, "1", "1.00")))
        .when()
            .post("/warehouse/purchase-invoices")
        .then()
            .statusCode(201);
    }

    @Test
    void create_sameNumberAsCancelledInvoice_returns201NumberReleased() {
        int supplierId = fixtures.seedSupplier("ZTEST_Proveedor Reuse");
        int productId = fixtures.seedProduct("ZTEST_PI Reuse");
        seedCancelledInvoice(supplierId, "ZTEST-REUSE-001");
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(invoiceBody(supplierId, "ZTEST-REUSE-001", fixtures.currencyId("USD"), itemJson(productId, "1", "1.00")))
        .when()
            .post("/warehouse/purchase-invoices")
        .then()
            .statusCode(201);
    }

    // ---------- POST: WH-004 (catálogo inexistente/inactivo) ----------------------

    @Test
    void create_nonexistentSupplier_returns400_WH004() {
        int productId = fixtures.seedProduct("ZTEST_PI NoSup");
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(invoiceBody(999999, "ZTEST-WH004-001", fixtures.currencyId("USD"), itemJson(productId, "1", "1.00")))
        .when()
            .post("/warehouse/purchase-invoices")
        .then()
            .statusCode(400)
            .body("code", equalTo("WH-004"));
    }

    @Test
    void create_inactiveSupplier_returns400_WH004() {
        int supplierId = fixtures.seedSupplier("ZTEST_Proveedor Inactivo", false);
        int productId = fixtures.seedProduct("ZTEST_PI SupInactivo");
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(invoiceBody(supplierId, "ZTEST-WH004-002", fixtures.currencyId("USD"), itemJson(productId, "1", "1.00")))
        .when()
            .post("/warehouse/purchase-invoices")
        .then()
            .statusCode(400)
            .body("code", equalTo("WH-004"));
    }

    @Test
    void create_nonexistentCurrency_returns400_WH004() {
        int supplierId = fixtures.seedSupplier("ZTEST_Proveedor NoMoneda");
        int productId = fixtures.seedProduct("ZTEST_PI NoMoneda");
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(invoiceBody(supplierId, "ZTEST-WH004-003", 999999, itemJson(productId, "1", "1.00")))
        .when()
            .post("/warehouse/purchase-invoices")
        .then()
            .statusCode(400)
            .body("code", equalTo("WH-004"));
    }

    @Test
    void create_nonexistentProductInItem_returns400_WH004() {
        int supplierId = fixtures.seedSupplier("ZTEST_Proveedor NoProd");
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(invoiceBody(supplierId, "ZTEST-WH004-004", fixtures.currencyId("USD"), itemJson(999999, "1", "1.00")))
        .when()
            .post("/warehouse/purchase-invoices")
        .then()
            .statusCode(400)
            .body("code", equalTo("WH-004"));
    }

    @Test
    void create_inactiveProductInItem_returns400_WH004() {
        int supplierId = fixtures.seedSupplier("ZTEST_Proveedor ProdInactivo");
        int productId = fixtures.seedProduct("ZTEST_PI Inactivo", false);
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(invoiceBody(supplierId, "ZTEST-WH004-005", fixtures.currencyId("USD"), itemJson(productId, "1", "1.00")))
        .when()
            .post("/warehouse/purchase-invoices")
        .then()
            .statusCode(400)
            .body("code", equalTo("WH-004"));
    }

    // ---------- POST: transaccionalidad (rollback) --------------------------------

    @Test
    void create_secondItemInvalidProduct_nothingPersisted() {
        int supplierId = fixtures.seedSupplier("ZTEST_Proveedor Rollback");
        int productId = fixtures.seedProduct("ZTEST_PI Rollback");
        String token = login("admin", "Admin1234");

        String items = itemJson(productId, "10", "5.00") + "," + itemJson(999999, "1", "1.00");

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(invoiceBody(supplierId, "ZTEST-RB-001", fixtures.currencyId("USD"), items))
        .when()
            .post("/warehouse/purchase-invoices")
        .then()
            .statusCode(400)
            .body("code", equalTo("WH-004"));

        // Ni el 1er ítem (válido) sumó stock ni la cabecera quedó registrada.
        fixtures.assertStock(productId, "0", token);
        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/warehouse/purchase-invoices?supplierId=" + supplierId)
        .then()
            .statusCode(200)
            .body("totalElements", equalTo(0));
    }

    @Test
    void create_duplicateNumber_secondItemStockUnchanged() {
        int supplierId = fixtures.seedSupplier("ZTEST_Proveedor RbDup");
        int productA = fixtures.seedProduct("ZTEST_PI RbDupA");
        int productB = fixtures.seedProduct("ZTEST_PI RbDupB");
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(invoiceBody(supplierId, "ZTEST-RBDUP-001", fixtures.currencyId("USD"), itemJson(productA, "10", "5.00")))
        .when()
            .post("/warehouse/purchase-invoices")
        .then()
            .statusCode(201);

        String items = itemJson(productA, "1", "5.00") + "," + itemJson(productB, "99", "5.00");
        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(invoiceBody(supplierId, "ZTEST-RBDUP-001", fixtures.currencyId("USD"), items))
        .when()
            .post("/warehouse/purchase-invoices")
        .then()
            .statusCode(409)
            .body("code", equalTo("WH-002"));

        // El productB de la 2da request (rechazada) no movió stock.
        fixtures.assertStock(productB, "0", token);
    }

    // ---------- POST: validación de body (COM-001) --------------------------------

    @Test
    void create_missingItems_returns400_COM001() {
        int supplierId = fixtures.seedSupplier("ZTEST_Proveedor SinItems");
        String token = login("admin", "Admin1234");

        String body = "{\"supplierId\":" + supplierId + ",\"invoiceNumber\":\"ZTEST-VAL-001\","
            + "\"invoiceDate\":\"2026-07-02\",\"currencyId\":" + fixtures.currencyId("USD") + "}";

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(body)
        .when()
            .post("/warehouse/purchase-invoices")
        .then()
            .statusCode(400)
            .body("code", equalTo("COM-001"));
    }

    @Test
    void create_emptyItemsArray_returns400_COM001() {
        int supplierId = fixtures.seedSupplier("ZTEST_Proveedor ItemsVacio");
        String token = login("admin", "Admin1234");

        String body = "{\"supplierId\":" + supplierId + ",\"invoiceNumber\":\"ZTEST-VAL-002\","
            + "\"invoiceDate\":\"2026-07-02\",\"currencyId\":" + fixtures.currencyId("USD") + ",\"items\":[]}";

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(body)
        .when()
            .post("/warehouse/purchase-invoices")
        .then()
            .statusCode(400)
            .body("code", equalTo("COM-001"));
    }

    @Test
    void create_missingSupplierId_returns400_COM001() {
        int productId = fixtures.seedProduct("ZTEST_PI SinSup");
        String token = login("admin", "Admin1234");

        String body = "{\"invoiceNumber\":\"ZTEST-VAL-003\",\"invoiceDate\":\"2026-07-02\","
            + "\"currencyId\":" + fixtures.currencyId("USD") + ",\"items\":[" + itemJson(productId, "1", "1.00") + "]}";

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(body)
        .when()
            .post("/warehouse/purchase-invoices")
        .then()
            .statusCode(400)
            .body("code", equalTo("COM-001"));
    }

    @Test
    void create_blankInvoiceNumber_returns400_COM001() {
        int supplierId = fixtures.seedSupplier("ZTEST_Proveedor NumVacio");
        int productId = fixtures.seedProduct("ZTEST_PI NumVacio");
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(invoiceBody(supplierId, "", fixtures.currencyId("USD"), itemJson(productId, "1", "1.00")))
        .when()
            .post("/warehouse/purchase-invoices")
        .then()
            .statusCode(400)
            .body("code", equalTo("COM-001"));
    }

    @Test
    void create_itemQuantityZero_returns400_COM001() {
        int supplierId = fixtures.seedSupplier("ZTEST_Proveedor QtyCero");
        int productId = fixtures.seedProduct("ZTEST_PI QtyCero");
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(invoiceBody(supplierId, "ZTEST-VAL-004", fixtures.currencyId("USD"), itemJson(productId, "0", "1.00")))
        .when()
            .post("/warehouse/purchase-invoices")
        .then()
            .statusCode(400)
            .body("code", equalTo("COM-001"));
    }

    @Test
    void create_itemNegativeUnitPrice_returns400_COM001() {
        int supplierId = fixtures.seedSupplier("ZTEST_Proveedor PrecioNeg");
        int productId = fixtures.seedProduct("ZTEST_PI PrecioNeg");
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(invoiceBody(supplierId, "ZTEST-VAL-005", fixtures.currencyId("USD"), itemJson(productId, "1", "-1.00")))
        .when()
            .post("/warehouse/purchase-invoices")
        .then()
            .statusCode(400)
            .body("code", equalTo("COM-001"));
    }

    @Test
    void create_emptyBody_returns400_COM001() {
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body("{}")
        .when()
            .post("/warehouse/purchase-invoices")
        .then()
            .statusCode(400)
            .body("code", equalTo("COM-001"));
    }

    // ---------- POST: roles ------------------------------------------------------

    @Test
    void create_withoutToken_returns401() {
        int supplierId = fixtures.seedSupplier("ZTEST_Proveedor NoToken");
        int productId = fixtures.seedProduct("ZTEST_PI NoToken");

        given()
            .contentType(ContentType.JSON)
            .body(invoiceBody(supplierId, "ZTEST-ROLE-001", fixtures.currencyId("USD"), itemJson(productId, "1", "1.00")))
        .when()
            .post("/warehouse/purchase-invoices")
        .then()
            .statusCode(401);
    }

    @Test
    void create_withSalesRole_returns403_COM003() {
        int supplierId = fixtures.seedSupplier("ZTEST_Proveedor Sales");
        int productId = fixtures.seedProduct("ZTEST_PI Sales");
        String token = login("lcampos", "Sales1234");

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(invoiceBody(supplierId, "ZTEST-ROLE-002", fixtures.currencyId("USD"), itemJson(productId, "1", "1.00")))
        .when()
            .post("/warehouse/purchase-invoices")
        .then()
            .statusCode(403)
            .body("code", equalTo("COM-003"));
    }

    @Test
    void create_withDispatcherRole_returns403_COM003() {
        int supplierId = fixtures.seedSupplier("ZTEST_Proveedor Disp");
        int productId = fixtures.seedProduct("ZTEST_PI Disp");

        given()
            .header("Authorization", "Bearer " + fabricateAccessToken("disp_test", "dispatcher"))
            .contentType(ContentType.JSON)
            .body(invoiceBody(supplierId, "ZTEST-ROLE-003", fixtures.currencyId("USD"), itemJson(productId, "1", "1.00")))
        .when()
            .post("/warehouse/purchase-invoices")
        .then()
            .statusCode(403)
            .body("code", equalTo("COM-003"));
    }

    @Test
    void create_withWarehouseKeeperRole_returns201() {
        int supplierId = fixtures.seedSupplier("ZTEST_Proveedor WK");
        int productId = fixtures.seedProduct("ZTEST_PI WK");

        given()
            .header("Authorization", "Bearer " + fabricateTokenForUser(fixtures.adminId(), "wk_test", "warehouse_keeper"))
            .contentType(ContentType.JSON)
            .body(invoiceBody(supplierId, "ZTEST-ROLE-004", fixtures.currencyId("USD"), itemJson(productId, "1", "1.00")))
        .when()
            .post("/warehouse/purchase-invoices")
        .then()
            .statusCode(201);
    }

    @Test
    void create_withFinanceManagerRole_returns201() {
        int supplierId = fixtures.seedSupplier("ZTEST_Proveedor FM");
        int productId = fixtures.seedProduct("ZTEST_PI FM");

        given()
            .header("Authorization", "Bearer " + fabricateTokenForUser(fixtures.adminId(), "fm_test", "finance_manager"))
            .contentType(ContentType.JSON)
            .body(invoiceBody(supplierId, "ZTEST-ROLE-005", fixtures.currencyId("USD"), itemJson(productId, "1", "1.00")))
        .when()
            .post("/warehouse/purchase-invoices")
        .then()
            .statusCode(201);
    }

    // ---------- GET: listado -----------------------------------------------------

    @Test
    void list_multipleInvoices_returns200OrderedByCreatedAtDesc() {
        int supplierId = fixtures.seedSupplier("ZTEST_Proveedor Orden");
        int productId = fixtures.seedProduct("ZTEST_PI Orden");
        String token = login("admin", "Admin1234");

        int first = createInvoice(supplierId, "ZTEST-ORD-001", productId, token);
        int second = createInvoice(supplierId, "ZTEST-ORD-002", productId, token);

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/warehouse/purchase-invoices?supplierId=" + supplierId + "&size=100")
        .then()
            .statusCode(200)
            .body("content[0].id", equalTo(second))
            .body("content[1].id", equalTo(first))
            .body("content[0].itemsCount", equalTo(1))
            .body("content[0].total", equalTo(10.00f))
            .body("content[0].currencyCode", equalTo("USD"));
    }

    @Test
    void list_filterBySupplierId_returnsOnlyThatSupplier() {
        int supplierA = fixtures.seedSupplier("ZTEST_Proveedor FiltroA");
        int supplierB = fixtures.seedSupplier("ZTEST_Proveedor FiltroB");
        int productId = fixtures.seedProduct("ZTEST_PI Filtro");
        String token = login("admin", "Admin1234");

        createInvoice(supplierA, "ZTEST-FIL-001", productId, token);
        createInvoice(supplierB, "ZTEST-FIL-002", productId, token);

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/warehouse/purchase-invoices?supplierId=" + supplierA)
        .then()
            .statusCode(200)
            .body("totalElements", equalTo(1))
            .body("content[0].supplier.id", equalTo(supplierA));
    }

    @Test
    void list_filterByStatusActive_excludesCancelled_butDefaultIncludesBoth() {
        int supplierId = fixtures.seedSupplier("ZTEST_Proveedor Estado");
        int productId = fixtures.seedProduct("ZTEST_PI Estado");
        String token = login("admin", "Admin1234");

        createInvoice(supplierId, "ZTEST-EST-ACT", productId, token);
        seedCancelledInvoice(supplierId, "ZTEST-EST-CAN");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/warehouse/purchase-invoices?supplierId=" + supplierId + "&status=ACTIVE")
        .then()
            .statusCode(200)
            .body("totalElements", equalTo(1))
            .body("content[0].status", equalTo("ACTIVE"));

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/warehouse/purchase-invoices?supplierId=" + supplierId)
        .then()
            .statusCode(200)
            .body("totalElements", equalTo(2));
    }

    @Test
    void list_filterByDateRange_inclusive() {
        int supplierId = fixtures.seedSupplier("ZTEST_Proveedor Fechas");
        int productId = fixtures.seedProduct("ZTEST_PI Fechas");
        String token = login("admin", "Admin1234");

        createInvoiceWithDate(supplierId, "ZTEST-DATE-01", "2026-07-01", productId, token);
        createInvoiceWithDate(supplierId, "ZTEST-DATE-05", "2026-07-05", productId, token);
        createInvoiceWithDate(supplierId, "ZTEST-DATE-10", "2026-07-10", productId, token);

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/warehouse/purchase-invoices?supplierId=" + supplierId + "&dateFrom=2026-07-01&dateTo=2026-07-05")
        .then()
            .statusCode(200)
            .body("totalElements", equalTo(2));
    }

    @Test
    void list_qMatchesGuideNumber_returnsMatch() {
        int supplierId = fixtures.seedSupplier("ZTEST_Proveedor QGuia");
        int productId = fixtures.seedProduct("ZTEST_PI QGuia");
        String token = login("admin", "Admin1234");

        String body = "{\"supplierId\":" + supplierId + ",\"invoiceNumber\":\"ZTEST-QG-001\","
            + "\"invoiceDate\":\"2026-07-02\",\"guideNumber\":\"ZTEST-T001-9999\","
            + "\"currencyId\":" + fixtures.currencyId("USD") + ",\"items\":[" + itemJson(productId, "1", "1.00") + "]}";
        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(body)
        .when()
            .post("/warehouse/purchase-invoices")
        .then()
            .statusCode(201);

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/warehouse/purchase-invoices?supplierId=" + supplierId + "&q=9999")
        .then()
            .statusCode(200)
            .body("totalElements", equalTo(1));
    }

    @Test
    void list_qMultiWord_eachWordMatchesDifferentField_returnsMatch() {
        int supplierId = fixtures.seedSupplier("ZTEST_Proveedor Especial");
        int productId = fixtures.seedProduct("ZTEST_PI MultiWord");
        String token = login("admin", "Admin1234");

        createInvoice(supplierId, "ZTEST-MW-0099", productId, token);

        // "0099" matchea el invoiceNumber; "Especial" matchea el nombre del proveedor.
        given()
            .header("Authorization", "Bearer " + token)
            .queryParam("q", "0099 Especial")
        .when()
            .get("/warehouse/purchase-invoices")
        .then()
            .statusCode(200)
            .body("totalElements", equalTo(1));
    }

    @Test
    void list_qMultiWord_oneWordMatchesNothing_returnsEmpty() {
        int supplierId = fixtures.seedSupplier("ZTEST_Proveedor Parcial");
        int productId = fixtures.seedProduct("ZTEST_PI Parcial");
        String token = login("admin", "Admin1234");

        createInvoice(supplierId, "ZTEST-MW-0100", productId, token);

        given()
            .header("Authorization", "Bearer " + token)
            .queryParam("q", "0100 Inexistente123")
        .when()
            .get("/warehouse/purchase-invoices")
        .then()
            .statusCode(200)
            .body("totalElements", equalTo(0));
    }

    @Test
    void list_qBelow3Chars_returns400_COM001() {
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/warehouse/purchase-invoices?q=ab")
        .then()
            .statusCode(400)
            .body("code", equalTo("COM-001"));
    }

    @Test
    void list_supplierIdMalformed_returns404() {
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/warehouse/purchase-invoices?supplierId=abc")
        .then()
            .statusCode(404);
    }

    @Test
    void list_dateFromMalformed_returns404() {
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/warehouse/purchase-invoices?dateFrom=notadate")
        .then()
            .statusCode(404);
    }

    @Test
    void list_statusInvalidEnum_returns404() {
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/warehouse/purchase-invoices?status=BOGUS")
        .then()
            .statusCode(404);
    }

    @Test
    void list_pagination_secondPageReturnsRemainingElement() {
        int supplierId = fixtures.seedSupplier("ZTEST_Proveedor Pag");
        int productId = fixtures.seedProduct("ZTEST_PI Pag");
        String token = login("admin", "Admin1234");

        createInvoice(supplierId, "ZTEST-PAG-001", productId, token);
        createInvoice(supplierId, "ZTEST-PAG-002", productId, token);

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/warehouse/purchase-invoices?supplierId=" + supplierId + "&page=1&size=1")
        .then()
            .statusCode(200)
            .body("totalElements", equalTo(2))
            .body("content.size()", equalTo(1))
            .body("page", equalTo(1));
    }

    @Test
    void list_sizeAboveMax_returns400_COM001() {
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/warehouse/purchase-invoices?size=101")
        .then()
            .statusCode(400)
            .body("code", equalTo("COM-001"));
    }

    @Test
    void list_emptyResult_returns200EmptyContent() {
        int supplierId = fixtures.seedSupplier("ZTEST_Proveedor Vacio");
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/warehouse/purchase-invoices?supplierId=" + supplierId)
        .then()
            .statusCode(200)
            .body("content.size()", equalTo(0))
            .body("empty", equalTo(true))
            .body("totalElements", equalTo(0));
    }

    @Test
    void list_withoutToken_returns401() {
        given()
        .when()
            .get("/warehouse/purchase-invoices")
        .then()
            .statusCode(401);
    }

    @Test
    void list_withSalesRole_returns403_COM003() {
        String token = login("lcampos", "Sales1234");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/warehouse/purchase-invoices")
        .then()
            .statusCode(403)
            .body("code", equalTo("COM-003"));
    }

    // ---------- helpers de listado ------------------------------------------------

    private int createInvoice(int supplierId, String invoiceNumber, int productId, String token) {
        return given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(invoiceBody(supplierId, invoiceNumber, fixtures.currencyId("USD"), itemJson(productId, "10", "1.00")))
        .when()
            .post("/warehouse/purchase-invoices")
        .then()
            .statusCode(201)
            .extract().jsonPath().getInt("id");
    }

    private int createInvoiceWithDate(int supplierId, String invoiceNumber, String date, int productId, String token) {
        String body = "{\"supplierId\":" + supplierId + ",\"invoiceNumber\":\"" + invoiceNumber
            + "\",\"invoiceDate\":\"" + date + "\",\"currencyId\":" + fixtures.currencyId("USD")
            + ",\"items\":[" + itemJson(productId, "1", "1.00") + "]}";
        return given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(body)
        .when()
            .post("/warehouse/purchase-invoices")
        .then()
            .statusCode(201)
            .extract().jsonPath().getInt("id");
    }

}
