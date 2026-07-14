package com.scaramutti.tms.warehouse.purchaseinvoice;

import com.scaramutti.tms.shared.entity.Product;
import com.scaramutti.tms.shared.entity.Supplier;
import com.scaramutti.tms.shared.entity.User;
import com.scaramutti.tms.shared.entity.Worker;
import com.scaramutti.tms.shared.repository.ProductRepository;
import com.scaramutti.tms.shared.repository.SupplierRepository;
import com.scaramutti.tms.shared.repository.UserRepository;
import com.scaramutti.tms.shared.repository.WorkerRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.smallrye.jwt.build.Jwt;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Set;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * Integration tests de GET/PUT/cancel /warehouse/purchase-invoices/{id}. Hermético
 * (prefijo ZTEST_, cleanup en orden FK). Los retiros que disparan WH-006/WH-007 se
 * siembran por SQL nativo (el endpoint de retiros aún no existe). Las facturas base se
 * crean vía el POST de alta de entradas.
 */
@QuarkusTest
class WarehousePurchaseInvoiceByIdResourceTest {

    private static final String TEST_NAME_PREFIX = "ZTEST_";
    private static final int CATEGORY_FILTROS = 4;
    private static final int UNIT_UND = 1;

    @Inject ProductRepository productRepository;
    @Inject SupplierRepository supplierRepository;
    @Inject WorkerRepository workerRepository;
    @Inject UserRepository userRepository;
    @Inject EntityManager entityManager;

    @AfterEach
    void cleanupFixtures() {
        QuarkusTransaction.requiringNew().run(() -> {
            entityManager.createNativeQuery(
                "DELETE FROM almacen.withdrawals WHERE product_id IN "
                    + "(SELECT id FROM almacen.products WHERE name LIKE ?1)")
                .setParameter(1, TEST_NAME_PREFIX + "%").executeUpdate();
            entityManager.createNativeQuery(
                "DELETE FROM almacen.audit_logs WHERE entity_type = 'PURCHASE_INVOICE' AND entity_id IN "
                    + "(SELECT id FROM almacen.purchase_invoices WHERE invoice_number LIKE 'ZTEST%')")
                .executeUpdate();
            entityManager.createNativeQuery(
                "DELETE FROM almacen.purchase_invoice_items WHERE product_id IN "
                    + "(SELECT id FROM almacen.products WHERE name LIKE ?1)")
                .setParameter(1, TEST_NAME_PREFIX + "%").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM almacen.purchase_invoices WHERE invoice_number LIKE 'ZTEST%'")
                .executeUpdate();
            entityManager.createNativeQuery(
                "DELETE FROM almacen.opening_balances WHERE product_id IN "
                    + "(SELECT id FROM almacen.products WHERE name LIKE ?1)")
                .setParameter(1, TEST_NAME_PREFIX + "%").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM almacen.products WHERE name LIKE ?1")
                .setParameter(1, TEST_NAME_PREFIX + "%").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM almacen.suppliers WHERE name LIKE ?1")
                .setParameter(1, TEST_NAME_PREFIX + "%").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM public.workers WHERE document_number LIKE 'ZTEST%'")
                .executeUpdate();
        });
    }

    // ---------- fixtures --------------------------------------------------------

    private int adminId() {
        User admin = userRepository.findByUsername("admin").orElseThrow();
        return admin.id;
    }

    private int seedProduct(String name) {
        return seedProduct(name, true);
    }

    private int seedProduct(String name, boolean isActive) {
        return QuarkusTransaction.requiringNew().call(() -> {
            Product product = new Product();
            product.name = name;
            product.categoryId = CATEGORY_FILTROS;
            product.unitOfMeasureId = UNIT_UND;
            product.attributes = new HashMap<>();
            product.minStock = BigDecimal.ZERO;
            product.isActive = isActive;
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

    private int dniDocumentTypeId() {
        var rows = entityManager.createNativeQuery("SELECT id FROM public.document_types WHERE code = 'DNI'")
            .getResultList();
        if (!rows.isEmpty()) {
            return ((Number) rows.get(0)).intValue();
        }
        entityManager.createNativeQuery(
            "INSERT INTO public.document_types (code, name, max_length, is_active) VALUES ('DNI', 'DNI', 8, true)")
            .executeUpdate();
        return ((Number) entityManager.createNativeQuery("SELECT id FROM public.document_types WHERE code = 'DNI'")
            .getSingleResult()).intValue();
    }

    private int seedWorker(String documentNumber) {
        return QuarkusTransaction.requiringNew().call(() -> {
            Worker worker = new Worker();
            worker.firstName = "ZTEST";
            worker.lastName = "Operario";
            worker.documentTypeId = dniDocumentTypeId();
            worker.documentNumber = documentNumber;
            worker.position = "ZTEST Operario";
            worker.isActive = true;
            worker.createdAt = OffsetDateTime.now();
            workerRepository.persist(worker);
            return worker.id;
        });
    }

    /** Retiro ACTIVO (o CANCELLED) sembrado por SQL nativo (el endpoint de retiros aún no existe). */
    private void seedWithdrawal(int productId, String quantity, int receivedBy, boolean cancelled) {
        QuarkusTransaction.requiringNew().run(() -> {
            if (!cancelled) {
                entityManager.createNativeQuery(
                    "INSERT INTO almacen.withdrawals (product_id, quantity, received_by, registered_by, status) "
                        + "VALUES (?1, CAST(?2 AS NUMERIC), ?3, ?4, 'ACTIVE')")
                    .setParameter(1, productId).setParameter(2, quantity)
                    .setParameter(3, receivedBy).setParameter(4, adminId()).executeUpdate();
                return;
            }
            entityManager.createNativeQuery(
                "INSERT INTO almacen.withdrawals (product_id, quantity, received_by, registered_by, status, "
                    + "cancel_reason, cancelled_by, cancelled_at) "
                    + "VALUES (?1, CAST(?2 AS NUMERIC), ?3, ?4, 'CANCELLED', 'ZTEST anulado', ?4, CURRENT_TIMESTAMP)")
                .setParameter(1, productId).setParameter(2, quantity)
                .setParameter(3, receivedBy).setParameter(4, adminId()).executeUpdate();
        });
    }

    private int seedCancelledInvoice(int supplierId, String invoiceNumber, int productId) {
        return QuarkusTransaction.requiringNew().call(() -> {
            Object id = entityManager.createNativeQuery(
                "INSERT INTO almacen.purchase_invoices "
                    + "(supplier_id, invoice_number, invoice_date, currency_id, registered_by, created_at, updated_at, "
                    + "status, cancel_reason, cancelled_by, cancelled_at) "
                    + "VALUES (?1, ?2, CURRENT_DATE, (SELECT id FROM public.currencies WHERE code = 'USD'), ?3, "
                    + "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'CANCELLED', 'ZTEST anulada', ?3, CURRENT_TIMESTAMP) "
                    + "RETURNING id")
                .setParameter(1, supplierId).setParameter(2, invoiceNumber).setParameter(3, adminId())
                .getSingleResult();
            int invoiceId = ((Number) id).intValue();
            entityManager.createNativeQuery(
                "INSERT INTO almacen.purchase_invoice_items (invoice_id, product_id, quantity, unit_price) "
                    + "VALUES (?1, ?2, 1, 1)")
                .setParameter(1, invoiceId).setParameter(2, productId).executeUpdate();
            return invoiceId;
        });
    }

    private String login(String username, String password) {
        return given().contentType(ContentType.JSON)
            .body("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}")
        .when().post("/auth/login")
        .then().statusCode(200).extract().jsonPath().getString("token");
    }

    private String fabricateAccessToken(String username, String role) {
        Instant now = Instant.now();
        return Jwt.subject("999").upn(username).groups(Set.of(role)).claim("typ", "access")
            .issuedAt(now).expiresAt(now.plusSeconds(3600)).sign();
    }

    private String fabricateTokenForUser(int userId, String username, String role) {
        Instant now = Instant.now();
        return Jwt.subject(String.valueOf(userId)).upn(username).groups(Set.of(role)).claim("typ", "access")
            .issuedAt(now).expiresAt(now.plusSeconds(3600)).sign();
    }

    private String itemJson(int productId, String quantity, String unitPrice) {
        return "{\"productId\":" + productId + ",\"quantity\":" + quantity + ",\"unitPrice\":" + unitPrice + "}";
    }

    /** Crea una factura vía POST y devuelve su id. */
    private int createInvoice(int supplierId, String invoiceNumber, String itemsJson, String token) {
        String body = "{\"supplierId\":" + supplierId + ",\"invoiceNumber\":\"" + invoiceNumber
            + "\",\"invoiceDate\":\"2026-07-02\",\"currencyId\":" + currencyId("USD") + ",\"items\":[" + itemsJson + "]}";
        return given().header("Authorization", "Bearer " + token).contentType(ContentType.JSON).body(body)
        .when().post("/warehouse/purchase-invoices")
        .then().statusCode(201).extract().jsonPath().getInt("id");
    }

    private String etagOf(int id, String token) {
        return given().header("Authorization", "Bearer " + token)
        .when().get("/warehouse/purchase-invoices/" + id)
        .then().statusCode(200).extract().header("ETag");
    }

    private String updateBody(String invoiceNumber, int currencyId, String itemsJson, String reason) {
        return "{\"invoiceNumber\":\"" + invoiceNumber + "\",\"invoiceDate\":\"2026-07-05\",\"currencyId\":" + currencyId
            + ",\"items\":[" + itemsJson + "],\"reason\":\"" + reason + "\"}";
    }

    private long countFieldEdits(int invoiceId, String fieldName) {
        return ((Number) entityManager.createNativeQuery(
            "SELECT COUNT(*) FROM almacen.audit_logs WHERE entity_type = 'PURCHASE_INVOICE' "
                + "AND entity_id = ?1 AND change_type = 'FIELD_EDIT' AND field_name = ?2")
            .setParameter(1, invoiceId).setParameter(2, fieldName).getSingleResult()).longValue();
    }

    private long countChanges(int invoiceId, String changeType) {
        return ((Number) entityManager.createNativeQuery(
            "SELECT COUNT(*) FROM almacen.audit_logs WHERE entity_type = 'PURCHASE_INVOICE' "
                + "AND entity_id = ?1 AND change_type = ?2")
            .setParameter(1, invoiceId).setParameter(2, changeType).getSingleResult()).longValue();
    }

    private BigDecimal stockOf(int productId, String token) {
        return given().header("Authorization", "Bearer " + token)
        .when().get("/warehouse/products/" + productId + "/stock")
        .then().statusCode(200).extract().jsonPath().getObject("stock", BigDecimal.class);
    }

    private void assertStock(int productId, String expected, String token) {
        BigDecimal actual = stockOf(productId, token);
        if (actual.compareTo(new BigDecimal(expected)) != 0) {
            throw new AssertionError("Stock de " + productId + " esperado " + expected + " pero fue " + actual);
        }
    }

    /** Registra el corte inicial del producto vía su endpoint (debe ir ANTES de cualquier movimiento). */
    private void seedOpeningBalance(int productId, int quantity, String token) {
        given().header("Authorization", "Bearer " + token).contentType(ContentType.JSON)
            .body("{\"productId\":" + productId + ",\"quantity\":" + quantity + "}")
        .when().post("/warehouse/opening-balances")
        .then().statusCode(201);
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

    // ---------- PUT /{id} happy ---------------------------------------------------

    @Test
    void update_fullPayload_returns200_newEtag_itemsReplaced_stockMoves() {
        int supplierId = seedSupplier("ZTEST_Prov Edit");
        int productA = seedProduct("ZTEST_PI EditA");
        int productB = seedProduct("ZTEST_PI EditB");
        String token = login("admin", "Admin1234");
        int id = createInvoice(supplierId, "ZTEST-EDIT-001", itemJson(productA, "10", "45.00"), token);
        String etag = etagOf(id, token);

        String body = "{\"invoiceNumber\":\"ZTEST-EDIT-001B\",\"invoiceDate\":\"2026-07-05\","
            + "\"guideNumber\":\"ZTEST-T001-9\",\"currencyId\":" + currencyId("PEN") + ","
            + "\"observations\":\"ZTEST editada\",\"items\":[" + itemJson(productB, "6", "12.00")
            + "],\"reason\":\"Corrección de datos de la factura\"}";

        given().header("Authorization", "Bearer " + token).header("If-Match", etag)
            .contentType(ContentType.JSON).body(body)
        .when().put("/warehouse/purchase-invoices/" + id)
        .then().statusCode(200)
            .header("ETag", notNullValue())
            .body("invoiceNumber", equalTo("ZTEST-EDIT-001B"))
            .body("currency.code", equalTo("PEN"))
            .body("observations", equalTo("ZTEST editada"))
            .body("items", hasSize(1))
            .body("items[0].product.id", equalTo(productB))
            .body("total", equalTo(72.00f))
            .body("lastEdit.by.username", equalTo("admin"))
            .body("lastEdit.reason", equalTo("Corrección de datos de la factura"));

        assertStock(productA, "0", token);   // el ítem viejo se fue
        assertStock(productB, "6", token);   // el reemplazo cuenta
    }

    @Test
    void update_thenNewEtagDiffersFromOld() {
        int supplierId = seedSupplier("ZTEST_Prov EtagChg");
        int productId = seedProduct("ZTEST_PI EtagChg");
        String token = login("admin", "Admin1234");
        int id = createInvoice(supplierId, "ZTEST-ETAG-001", itemJson(productId, "5", "1.00"), token);
        String oldEtag = etagOf(id, token);

        String newEtag = given().header("Authorization", "Bearer " + token).header("If-Match", oldEtag)
            .contentType(ContentType.JSON)
            .body(updateBody("ZTEST-ETAG-001", currencyId("USD"), itemJson(productId, "7", "1.00"), "Ajuste de cantidad recibida"))
        .when().put("/warehouse/purchase-invoices/" + id)
        .then().statusCode(200).extract().header("ETag");

        if (newEtag.equals(oldEtag)) {
            throw new AssertionError("El ETag no cambió tras la edición");
        }
    }

    @Test
    void update_sameNumberAsSelf_returns200NoConflict() {
        int supplierId = seedSupplier("ZTEST_Prov Self");
        int productId = seedProduct("ZTEST_PI Self");
        String token = login("admin", "Admin1234");
        int id = createInvoice(supplierId, "ZTEST-SELF-001", itemJson(productId, "5", "1.00"), token);
        String etag = etagOf(id, token);

        given().header("Authorization", "Bearer " + token).header("If-Match", etag)
            .contentType(ContentType.JSON)
            .body(updateBody("ZTEST-SELF-001", currencyId("USD"), itemJson(productId, "6", "1.00"), "Correccion de cantidad"))
        .when().put("/warehouse/purchase-invoices/" + id)
        .then().statusCode(200);
    }

    @Test
    void update_quantityAtWithdrawnLevel_returns200_guardExactZero() {
        int supplierId = seedSupplier("ZTEST_Prov GuardZero");
        int productId = seedProduct("ZTEST_PI GuardZero");
        int workerId = seedWorker("ZTESTW200");
        String token = login("admin", "Admin1234");
        int id = createInvoice(supplierId, "ZTEST-GZ-001", itemJson(productId, "10", "1.00"), token);
        seedWithdrawal(productId, "9", workerId, false);
        String etag = etagOf(id, token);

        // 9 entradas - 9 salidas = 0 >= 0 → permite
        given().header("Authorization", "Bearer " + token).header("If-Match", etag)
            .contentType(ContentType.JSON)
            .body(updateBody("ZTEST-GZ-001", currencyId("USD"), itemJson(productId, "9", "1.00"), "Ajuste al conteo real"))
        .when().put("/warehouse/purchase-invoices/" + id)
        .then().statusCode(200);
    }

    @Test
    void update_onlyGuideNumberChanged_writesExactlyOneFieldEditRow() {
        int supplierId = seedSupplier("ZTEST_Prov Diff");
        int productId = seedProduct("ZTEST_PI Diff");
        String token = login("admin", "Admin1234");
        int id = createInvoice(supplierId, "ZTEST-DIFF-001", itemJson(productId, "10", "45.00"), token);
        String etag = etagOf(id, token);

        // Todo IDÉNTICO al alta salvo guideNumber (null → valor): 1 sola fila FIELD_EDIT.
        String body = "{\"invoiceNumber\":\"ZTEST-DIFF-001\",\"invoiceDate\":\"2026-07-02\","
            + "\"guideNumber\":\"ZTEST-T001-1\",\"currencyId\":" + currencyId("USD") + ","
            + "\"items\":[" + itemJson(productId, "10", "45.00") + "],\"reason\":\"Solo se corrige la guía\"}";
        given().header("Authorization", "Bearer " + token).header("If-Match", etag)
            .contentType(ContentType.JSON).body(body)
        .when().put("/warehouse/purchase-invoices/" + id).then().statusCode(200);

        if (countFieldEdits(id, "guideNumber") != 1) {
            throw new AssertionError("Esperaba 1 fila FIELD_EDIT de guideNumber, fue " + countFieldEdits(id, "guideNumber"));
        }
        if (countFieldEdits(id, "invoiceNumber") != 0 || countFieldEdits(id, "items") != 0) {
            throw new AssertionError("No debería haber filas de campos sin cambios");
        }
    }

    @Test
    void update_noOpPayload_writesNoFieldEditRows_butBumpsEtag() {
        int supplierId = seedSupplier("ZTEST_Prov NoOp");
        int productId = seedProduct("ZTEST_PI NoOp");
        String token = login("admin", "Admin1234");
        int id = createInvoice(supplierId, "ZTEST-NOOP-001", itemJson(productId, "10", "45.00"), token);
        String oldEtag = etagOf(id, token);

        // Payload idéntico al estado actual: ningún campo cambia → cero filas de audit.
        String body = "{\"invoiceNumber\":\"ZTEST-NOOP-001\",\"invoiceDate\":\"2026-07-02\",\"currencyId\":"
            + currencyId("USD") + ",\"items\":[" + itemJson(productId, "10", "45.00")
            + "],\"reason\":\"Reproceso sin cambios reales\"}";
        String newEtag = given().header("Authorization", "Bearer " + token).header("If-Match", oldEtag)
            .contentType(ContentType.JSON).body(body)
        .when().put("/warehouse/purchase-invoices/" + id).then().statusCode(200).extract().header("ETag");

        if (countChanges(id, "FIELD_EDIT") != 0) {
            throw new AssertionError("Un no-op no debería escribir filas FIELD_EDIT");
        }
        if (newEtag.equals(oldEtag)) {
            throw new AssertionError("updatedAt/ETag debería bumpear aunque el edit sea no-op (deuda conocida)");
        }
    }

    // ---------- PUT /{id} unhappy -------------------------------------------------

    @Test
    void update_withoutIfMatch_returns412_COM004() {
        int supplierId = seedSupplier("ZTEST_Prov NoMatch");
        int productId = seedProduct("ZTEST_PI NoMatch");
        String token = login("admin", "Admin1234");
        int id = createInvoice(supplierId, "ZTEST-NM-001", itemJson(productId, "5", "1.00"), token);

        given().header("Authorization", "Bearer " + token).contentType(ContentType.JSON)
            .body(updateBody("ZTEST-NM-001", currencyId("USD"), itemJson(productId, "6", "1.00"), "Cambio de cantidad"))
        .when().put("/warehouse/purchase-invoices/" + id)
        .then().statusCode(412).body("code", equalTo("COM-004"));
    }

    @Test
    void update_staleIfMatch_returns412_COM004() {
        int supplierId = seedSupplier("ZTEST_Prov Stale");
        int productId = seedProduct("ZTEST_PI Stale");
        String token = login("admin", "Admin1234");
        int id = createInvoice(supplierId, "ZTEST-ST-001", itemJson(productId, "5", "1.00"), token);
        String oldEtag = etagOf(id, token);

        // Un primer PUT cambia el ETag
        given().header("Authorization", "Bearer " + token).header("If-Match", oldEtag).contentType(ContentType.JSON)
            .body(updateBody("ZTEST-ST-001", currencyId("USD"), itemJson(productId, "6", "1.00"), "Primera correccion"))
        .when().put("/warehouse/purchase-invoices/" + id).then().statusCode(200);

        // Reintentar con el ETag viejo → 412
        given().header("Authorization", "Bearer " + token).header("If-Match", oldEtag).contentType(ContentType.JSON)
            .body(updateBody("ZTEST-ST-001", currencyId("USD"), itemJson(productId, "7", "1.00"), "Segunda correccion"))
        .when().put("/warehouse/purchase-invoices/" + id)
        .then().statusCode(412).body("code", equalTo("COM-004"));
    }

    @Test
    void update_reasonTooShort_returns400_COM001() {
        int supplierId = seedSupplier("ZTEST_Prov ShortReason");
        int productId = seedProduct("ZTEST_PI ShortReason");
        String token = login("admin", "Admin1234");
        int id = createInvoice(supplierId, "ZTEST-SR-001", itemJson(productId, "5", "1.00"), token);
        String etag = etagOf(id, token);

        given().header("Authorization", "Bearer " + token).header("If-Match", etag).contentType(ContentType.JSON)
            .body(updateBody("ZTEST-SR-001", currencyId("USD"), itemJson(productId, "6", "1.00"), "corto"))
        .when().put("/warehouse/purchase-invoices/" + id)
        .then().statusCode(400).body("code", equalTo("COM-001"));
    }

    @Test
    void update_emptyItems_returns400_COM001() {
        int supplierId = seedSupplier("ZTEST_Prov EmptyItems");
        int productId = seedProduct("ZTEST_PI EmptyItems");
        String token = login("admin", "Admin1234");
        int id = createInvoice(supplierId, "ZTEST-EI-001", itemJson(productId, "5", "1.00"), token);
        String etag = etagOf(id, token);

        given().header("Authorization", "Bearer " + token).header("If-Match", etag).contentType(ContentType.JSON)
            .body("{\"invoiceNumber\":\"ZTEST-EI-001\",\"invoiceDate\":\"2026-07-05\",\"currencyId\":" + currencyId("USD")
                + ",\"items\":[],\"reason\":\"Motivo suficientemente largo\"}")
        .when().put("/warehouse/purchase-invoices/" + id)
        .then().statusCode(400).body("code", equalTo("COM-001"));
    }

    @Test
    void update_nonexistentProduct_returns400_WH004() {
        int supplierId = seedSupplier("ZTEST_Prov BadProd");
        int productId = seedProduct("ZTEST_PI BadProd");
        String token = login("admin", "Admin1234");
        int id = createInvoice(supplierId, "ZTEST-BP-001", itemJson(productId, "5", "1.00"), token);
        String etag = etagOf(id, token);

        given().header("Authorization", "Bearer " + token).header("If-Match", etag).contentType(ContentType.JSON)
            .body(updateBody("ZTEST-BP-001", currencyId("USD"), itemJson(999999, "1", "1.00"), "Cambio con producto malo"))
        .when().put("/warehouse/purchase-invoices/" + id)
        .then().statusCode(400).body("code", equalTo("WH-004"));
    }

    @Test
    void update_cancelledInvoice_returns409_WH008() {
        int supplierId = seedSupplier("ZTEST_Prov EditCancelled");
        int productId = seedProduct("ZTEST_PI EditCancelled");
        String token = login("admin", "Admin1234");
        int id = seedCancelledInvoice(supplierId, "ZTEST-EC-001", productId);
        String etag = etagOf(id, token);

        given().header("Authorization", "Bearer " + token).header("If-Match", etag).contentType(ContentType.JSON)
            .body(updateBody("ZTEST-EC-001", currencyId("USD"), itemJson(productId, "1", "1.00"), "Intento de editar anulada"))
        .when().put("/warehouse/purchase-invoices/" + id)
        .then().statusCode(409).body("code", equalTo("WH-008"));
    }

    @Test
    void update_duplicateNumberOfOtherActive_returns409_WH002() {
        int supplierId = seedSupplier("ZTEST_Prov DupEdit");
        int productId = seedProduct("ZTEST_PI DupEdit");
        String token = login("admin", "Admin1234");
        createInvoice(supplierId, "ZTEST-DE-A", itemJson(productId, "1", "1.00"), token);
        int idB = createInvoice(supplierId, "ZTEST-DE-B", itemJson(productId, "1", "1.00"), token);
        String etagB = etagOf(idB, token);

        given().header("Authorization", "Bearer " + token).header("If-Match", etagB).contentType(ContentType.JSON)
            .body(updateBody("ZTEST-DE-A", currencyId("USD"), itemJson(productId, "1", "1.00"), "Renombrar a uno existente"))
        .when().put("/warehouse/purchase-invoices/" + idB)
        .then().statusCode(409).body("code", equalTo("WH-002"));
    }

    @Test
    void update_belowAlreadyWithdrawn_returns409_WH006() {
        int supplierId = seedSupplier("ZTEST_Prov WH006");
        int productId = seedProduct("ZTEST_PI WH006");
        int workerId = seedWorker("ZTESTW201");
        String token = login("admin", "Admin1234");
        int id = createInvoice(supplierId, "ZTEST-006-001", itemJson(productId, "10", "1.00"), token);
        seedWithdrawal(productId, "9", workerId, false);   // stock queda en 1
        String etag = etagOf(id, token);

        // bajar a 8: 1 - 10 + 8 = -1 < 0 → WH-006
        given().header("Authorization", "Bearer " + token).header("If-Match", etag).contentType(ContentType.JSON)
            .body(updateBody("ZTEST-006-001", currencyId("USD"), itemJson(productId, "8", "1.00"), "Bajar por debajo de lo retirado"))
        .when().put("/warehouse/purchase-invoices/" + id)
        .then().statusCode(409).body("code", equalTo("WH-006"));
    }

    @Test
    void update_removingProductWithActiveWithdrawals_returns409_WH006() {
        int supplierId = seedSupplier("ZTEST_Prov WH006Rem");
        int productA = seedProduct("ZTEST_PI WH006RemA");
        int productB = seedProduct("ZTEST_PI WH006RemB");
        int workerId = seedWorker("ZTESTW202");
        String token = login("admin", "Admin1234");
        int id = createInvoice(supplierId, "ZTEST-006-002",
            itemJson(productA, "10", "1.00") + "," + itemJson(productB, "5", "1.00"), token);
        seedWithdrawal(productA, "3", workerId, false);
        String etag = etagOf(id, token);

        // dejar solo productB: productA queda con 0 entradas y 3 salidas → -3 < 0 → WH-006
        given().header("Authorization", "Bearer " + token).header("If-Match", etag).contentType(ContentType.JSON)
            .body(updateBody("ZTEST-006-002", currencyId("USD"), itemJson(productB, "5", "1.00"), "Quitar el producto A"))
        .when().put("/warehouse/purchase-invoices/" + id)
        .then().statusCode(409).body("code", equalTo("WH-006"));
    }

    @Test
    void update_cancelledWithdrawalNotCounted_returns200() {
        int supplierId = seedSupplier("ZTEST_Prov WH006Canc");
        int productId = seedProduct("ZTEST_PI WH006Canc");
        int workerId = seedWorker("ZTESTW203");
        String token = login("admin", "Admin1234");
        int id = createInvoice(supplierId, "ZTEST-006-003", itemJson(productId, "10", "1.00"), token);
        seedWithdrawal(productId, "9", workerId, true);   // ANULADO → no cuenta
        String etag = etagOf(id, token);

        given().header("Authorization", "Bearer " + token).header("If-Match", etag).contentType(ContentType.JSON)
            .body(updateBody("ZTEST-006-003", currencyId("USD"), itemJson(productId, "1", "1.00"), "Bajar con retiro anulado"))
        .when().put("/warehouse/purchase-invoices/" + id)
        .then().statusCode(200);
    }

    /**
     * ANCLA DE LA FÓRMULA RATIFICADA (WH-006 con apertura, ruling del dueño 2026-07-14): la
     * apertura cuenta como stock disponible. Contraejemplo exacto: apertura 10 + factura 5 +
     * retiro 12 → editar la factura de 5 a 2 → stock real 10+2-12 = 0 ≥ 0 → 200. Con la fórmula
     * VIEJA (sin apertura) daría 2-12 = -10 → WH-006, así que este test distingue las dos
     * fórmulas (con apertura 0 serían idénticas y no anclarían nada).
     */
    @Test
    void update_withOpeningBalanceCoveringTheGap_returns200() {
        int supplierId = seedSupplier("ZTEST_Prov WH006Apertura");
        int productId = seedProduct("ZTEST_PI WH006Apertura");
        int workerId = seedWorker("ZTESTW206");
        String token = login("admin", "Admin1234");
        seedOpeningBalance(productId, 10, token);                                   // apertura ANTES de todo
        int id = createInvoice(supplierId, "ZTEST-006-AP1", itemJson(productId, "5", "1.00"), token);
        seedWithdrawal(productId, "12", workerId, false);                           // stock actual = 10+5-12 = 3
        String etag = etagOf(id, token);

        // editar 5 → 2: stock real 10+2-12 = 0 (permite SOLO con apertura incluida)
        given().header("Authorization", "Bearer " + token).header("If-Match", etag).contentType(ContentType.JSON)
            .body(updateBody("ZTEST-006-AP1", currencyId("USD"), itemJson(productId, "2", "1.00"), "Ajuste al conteo con apertura"))
        .when().put("/warehouse/purchase-invoices/" + id)
        .then().statusCode(200);

        assertStock(productId, "0", token);
    }

    /** Gemelo negativo del ancla: con apertura la guarda igual dispara cuando el stock real cae bajo 0. */
    @Test
    void update_withOpeningBalanceButStillNegative_returns409_WH006() {
        int supplierId = seedSupplier("ZTEST_Prov WH006AperturaNeg");
        int productId = seedProduct("ZTEST_PI WH006AperturaNeg");
        int workerId = seedWorker("ZTESTW207");
        String token = login("admin", "Admin1234");
        seedOpeningBalance(productId, 10, token);
        int id = createInvoice(supplierId, "ZTEST-006-AP2", itemJson(productId, "5", "1.00"), token);
        seedWithdrawal(productId, "12", workerId, false);
        String etag = etagOf(id, token);

        // editar 5 → 1: stock real 10+1-12 = -1 < 0 → WH-006 (incluso contando la apertura)
        given().header("Authorization", "Bearer " + token).header("If-Match", etag).contentType(ContentType.JSON)
            .body(updateBody("ZTEST-006-AP2", currencyId("USD"), itemJson(productId, "1", "1.00"), "Baja que deja negativo"))
        .when().put("/warehouse/purchase-invoices/" + id)
        .then().statusCode(409).body("code", equalTo("WH-006"));
    }

    @Test
    void update_nonexistentId_returns404_WH003() {
        int productId = seedProduct("ZTEST_PI UpdMissing");
        String token = login("admin", "Admin1234");
        given().header("Authorization", "Bearer " + token).header("If-Match", "\"whatever\"").contentType(ContentType.JSON)
            .body(updateBody("ZTEST-X", currencyId("USD"), itemJson(productId, "1", "1.00"), "Editar inexistente"))
        .when().put("/warehouse/purchase-invoices/999999")
        .then().statusCode(404).body("code", equalTo("WH-003"));
    }

    @Test
    void update_withSalesRole_returns403_COM003() {
        String token = login("lcampos", "Sales1234");
        given().header("Authorization", "Bearer " + token).header("If-Match", "\"x\"").contentType(ContentType.JSON)
            .body(updateBody("ZTEST-Y", currencyId("USD"), itemJson(1, "1", "1.00"), "Rol no autorizado"))
        .when().put("/warehouse/purchase-invoices/1")
        .then().statusCode(403).body("code", equalTo("COM-003"));
    }

    @Test
    void update_withWarehouseKeeperRole_returns200() {
        int supplierId = seedSupplier("ZTEST_Prov WKedit");
        int productId = seedProduct("ZTEST_PI WKedit");
        String token = login("admin", "Admin1234");
        int id = createInvoice(supplierId, "ZTEST-WK-001", itemJson(productId, "5", "1.00"), token);
        String etag = etagOf(id, token);

        given().header("Authorization", "Bearer " + fabricateTokenForUser(adminId(), "wk_test", "warehouse_keeper"))
            .header("If-Match", etag).contentType(ContentType.JSON)
            .body(updateBody("ZTEST-WK-001", currencyId("USD"), itemJson(productId, "6", "1.00"), "Edicion por encargado"))
        .when().put("/warehouse/purchase-invoices/" + id)
        .then().statusCode(200);
    }

    // ---------- POST /{id}/cancel -------------------------------------------------

    @Test
    void cancel_activeNoWithdrawals_returns200_stockDrops() {
        int supplierId = seedSupplier("ZTEST_Prov Cancel");
        int productId = seedProduct("ZTEST_PI Cancel");
        String token = login("admin", "Admin1234");
        int id = createInvoice(supplierId, "ZTEST-CAN-001", itemJson(productId, "10", "1.00"), token);
        String etag = etagOf(id, token);

        given().header("Authorization", "Bearer " + token).header("If-Match", etag).contentType(ContentType.JSON)
            .body("{\"reason\":\"Factura registrada por error de proveedor\"}")
        .when().post("/warehouse/purchase-invoices/" + id + "/cancel")
        .then().statusCode(200)
            .body("status", equalTo("CANCELLED"))
            .body("cancelReason", equalTo("Factura registrada por error de proveedor"))
            .body("cancelledBy.username", equalTo("admin"))
            .body("cancelledAt", notNullValue());

        assertStock(productId, "0", token);
    }

    @Test
    void cancel_withWithdrawalsCoveredByOtherInvoice_returns200() {
        int supplierId = seedSupplier("ZTEST_Prov CancelCovered");
        int productId = seedProduct("ZTEST_PI CancelCovered");
        int workerId = seedWorker("ZTESTW204");
        String token = login("admin", "Admin1234");
        int idA = createInvoice(supplierId, "ZTEST-CC-A", itemJson(productId, "10", "1.00"), token);
        createInvoice(supplierId, "ZTEST-CC-B", itemJson(productId, "5", "1.00"), token);   // otra entrada cubre
        seedWithdrawal(productId, "4", workerId, false);                                     // stock = 11
        String etagA = etagOf(idA, token);

        // cancelar A: 11 - 10 = 1 >= 0 → OK
        given().header("Authorization", "Bearer " + token).header("If-Match", etagA).contentType(ContentType.JSON)
            .body("{\"reason\":\"Se duplicó el registro de la compra\"}")
        .when().post("/warehouse/purchase-invoices/" + idA + "/cancel")
        .then().statusCode(200);

        assertStock(productId, "1", token);   // apertura 0 + B(5) - salidas(4)
    }

    @Test
    void cancel_wouldLeaveNegativeStock_returns409_WH007() {
        int supplierId = seedSupplier("ZTEST_Prov WH007");
        int productId = seedProduct("ZTEST_PI WH007");
        int workerId = seedWorker("ZTESTW205");
        String token = login("admin", "Admin1234");
        int id = createInvoice(supplierId, "ZTEST-007-001", itemJson(productId, "10", "1.00"), token);
        seedWithdrawal(productId, "9", workerId, false);   // stock = 1
        String etag = etagOf(id, token);

        // cancelar la única entrada: 1 - 10 = -9 < 0 → WH-007
        given().header("Authorization", "Bearer " + token).header("If-Match", etag).contentType(ContentType.JSON)
            .body("{\"reason\":\"Intento de anular con retiros pendientes\"}")
        .when().post("/warehouse/purchase-invoices/" + id + "/cancel")
        .then().statusCode(409).body("code", equalTo("WH-007"));
    }

    @Test
    void cancel_alreadyCancelled_returns409_WH008() {
        int supplierId = seedSupplier("ZTEST_Prov ReCancel");
        int productId = seedProduct("ZTEST_PI ReCancel");
        String token = login("admin", "Admin1234");
        int id = seedCancelledInvoice(supplierId, "ZTEST-RC-001", productId);
        String etag = etagOf(id, token);

        given().header("Authorization", "Bearer " + token).header("If-Match", etag).contentType(ContentType.JSON)
            .body("{\"reason\":\"Intento de re-anular una anulada\"}")
        .when().post("/warehouse/purchase-invoices/" + id + "/cancel")
        .then().statusCode(409).body("code", equalTo("WH-008"));
    }

    @Test
    void cancel_withoutIfMatch_returns412_COM004() {
        int supplierId = seedSupplier("ZTEST_Prov CancelNoMatch");
        int productId = seedProduct("ZTEST_PI CancelNoMatch");
        String token = login("admin", "Admin1234");
        int id = createInvoice(supplierId, "ZTEST-CNM-001", itemJson(productId, "1", "1.00"), token);

        given().header("Authorization", "Bearer " + token).contentType(ContentType.JSON)
            .body("{\"reason\":\"Anular sin If-Match presente\"}")
        .when().post("/warehouse/purchase-invoices/" + id + "/cancel")
        .then().statusCode(412).body("code", equalTo("COM-004"));
    }

    @Test
    void cancel_reasonTooShort_returns400_COM001() {
        int supplierId = seedSupplier("ZTEST_Prov CancelShort");
        int productId = seedProduct("ZTEST_PI CancelShort");
        String token = login("admin", "Admin1234");
        int id = createInvoice(supplierId, "ZTEST-CS-001", itemJson(productId, "1", "1.00"), token);
        String etag = etagOf(id, token);

        given().header("Authorization", "Bearer " + token).header("If-Match", etag).contentType(ContentType.JSON)
            .body("{\"reason\":\"corto\"}")
        .when().post("/warehouse/purchase-invoices/" + id + "/cancel")
        .then().statusCode(400).body("code", equalTo("COM-001"));
    }

    @Test
    void cancel_nonexistentId_returns404_WH003() {
        String token = login("admin", "Admin1234");
        given().header("Authorization", "Bearer " + token).header("If-Match", "\"x\"").contentType(ContentType.JSON)
            .body("{\"reason\":\"Anular una factura inexistente\"}")
        .when().post("/warehouse/purchase-invoices/999999/cancel")
        .then().statusCode(404).body("code", equalTo("WH-003"));
    }

    @Test
    void cancel_withoutToken_returns401() {
        given().contentType(ContentType.JSON).body("{\"reason\":\"Sin token de autorización\"}")
        .when().post("/warehouse/purchase-invoices/1/cancel")
        .then().statusCode(401);
    }

    @Test
    void cancel_withSalesRole_returns403_COM003() {
        String token = login("lcampos", "Sales1234");
        given().header("Authorization", "Bearer " + token).header("If-Match", "\"x\"").contentType(ContentType.JSON)
            .body("{\"reason\":\"Rol no autorizado a anular\"}")
        .when().post("/warehouse/purchase-invoices/1/cancel")
        .then().statusCode(403).body("code", equalTo("COM-003"));
    }
}
