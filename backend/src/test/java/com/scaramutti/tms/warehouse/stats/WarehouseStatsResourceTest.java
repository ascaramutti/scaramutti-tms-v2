package com.scaramutti.tms.warehouse.stats;

import com.scaramutti.tms.shared.entity.Product;
import com.scaramutti.tms.shared.entity.User;
import com.scaramutti.tms.shared.entity.Worker;
import com.scaramutti.tms.shared.repository.ProductRepository;
import com.scaramutti.tms.shared.repository.UserRepository;
import com.scaramutti.tms.shared.repository.WorkerRepository;
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
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Set;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Integration tests de GET /warehouse/stats. Hermetico (prefijo ZTEST_, cleanup en
 * orden FK). Los contadores tienen data pre-existente (dev/CI), asi que las
 * aserciones se hacen por DELTA contra un baseline tomado antes de sembrar. Las
 * fechas de registro (created_at de facturas, withdrawn_at de retiros) son
 * server-assigned, asi que "mes anterior" y CANCELLED se siembran por SQL nativo.
 */
@QuarkusTest
class WarehouseStatsResourceTest {

    private static final String TEST_NAME_PREFIX = "ZTEST_";
    private static final int CATEGORY_FILTROS = 4;
    private static final int UNIT_UND = 1;

    @Inject ProductRepository productRepository;
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
                "DELETE FROM almacen.purchase_invoice_items WHERE invoice_id IN "
                    + "(SELECT id FROM almacen.purchase_invoices WHERE supplier_id IN "
                    + "(SELECT id FROM almacen.suppliers WHERE name LIKE ?1))")
                .setParameter(1, TEST_NAME_PREFIX + "%").executeUpdate();
            entityManager.createNativeQuery(
                "DELETE FROM almacen.purchase_invoices WHERE supplier_id IN "
                    + "(SELECT id FROM almacen.suppliers WHERE name LIKE ?1)")
                .setParameter(1, TEST_NAME_PREFIX + "%").executeUpdate();
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

    private int seedProduct(String name, String minStock, boolean isActive) {
        return QuarkusTransaction.requiringNew().call(() -> {
            Product product = new Product();
            product.name = name;
            product.categoryId = CATEGORY_FILTROS;
            product.unitOfMeasureId = UNIT_UND;
            product.attributes = new HashMap<>();
            product.minStock = new BigDecimal(minStock);
            product.isActive = isActive;
            product.createdBy = adminId();
            productRepository.persist(product);
            return product.id;
        });
    }

    private int seedSupplier(String name) {
        return QuarkusTransaction.requiringNew().call(() -> ((Number) entityManager.createNativeQuery(
            "INSERT INTO almacen.suppliers (name, is_active, created_at) "
                + "VALUES (?1, true, now()) RETURNING id")
            .setParameter(1, name).getSingleResult()).intValue());
    }

    private int currencyId(String code) {
        return ((Number) entityManager.createNativeQuery(
            "SELECT id FROM public.currencies WHERE code = ?1").setParameter(1, code)
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

    /** Corte inicial via endpoint: da stock al producto (para probar low_stock). */
    private void seedOpeningBalance(int productId, String quantity, String token) {
        given().header("Authorization", "Bearer " + token).contentType(ContentType.JSON)
            .body("{\"productId\":" + productId + ",\"quantity\":" + quantity + "}")
        .when().post("/warehouse/opening-balances").then().statusCode(201);
    }

    /**
     * Factura por SQL nativo con created_at y status arbitrarios (backdatear/anular).
     * Un status CANCELLED exige poblar los campos de anulacion (CHECK
     * chk_invoices_cancel_consistent); ACTIVE los deja null.
     */
    private void seedInvoiceNative(int supplierId, String invoiceNumber, OffsetDateTime createdAt, String status) {
        boolean cancelled = "CANCELLED".equals(status);
        QuarkusTransaction.requiringNew().run(() -> entityManager.createNativeQuery(
            "INSERT INTO almacen.purchase_invoices "
                + "(supplier_id, invoice_number, invoice_date, currency_id, registered_by, created_at, updated_at, "
                + "status, cancel_reason, cancelled_by, cancelled_at) "
                + "VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?6, ?7, ?8, ?9, ?10)")
            .setParameter(1, supplierId)
            .setParameter(2, invoiceNumber)
            .setParameter(3, createdAt.toLocalDate())
            .setParameter(4, currencyId("PEN"))
            .setParameter(5, adminId())
            .setParameter(6, createdAt)
            .setParameter(7, status)
            .setParameter(8, cancelled ? "ZTEST anulada" : null)
            .setParameter(9, cancelled ? adminId() : null)
            .setParameter(10, cancelled ? createdAt : null)
            .executeUpdate());
    }

    /**
     * Retiro por SQL nativo con withdrawn_at y status arbitrarios (backdatear/anular).
     * Un status CANCELLED exige poblar los campos de anulacion (CHECK
     * chk_withdrawals_cancel_consistent); ACTIVE los deja null.
     */
    private void seedWithdrawalNative(int productId, String quantity, int workerId, OffsetDateTime withdrawnAt, String status) {
        boolean cancelled = "CANCELLED".equals(status);
        QuarkusTransaction.requiringNew().run(() -> entityManager.createNativeQuery(
            "INSERT INTO almacen.withdrawals "
                + "(product_id, quantity, withdrawn_at, received_by, registered_by, updated_at, "
                + "status, cancel_reason, cancelled_by, cancelled_at) "
                + "VALUES (?1, ?2, ?3, ?4, ?5, ?3, ?6, ?7, ?8, ?9)")
            .setParameter(1, productId)
            .setParameter(2, new BigDecimal(quantity))
            .setParameter(3, withdrawnAt)
            .setParameter(4, workerId)
            .setParameter(5, adminId())
            .setParameter(6, status)
            .setParameter(7, cancelled ? "ZTEST anulado" : null)
            .setParameter(8, cancelled ? adminId() : null)
            .setParameter(9, cancelled ? withdrawnAt : null)
            .executeUpdate());
    }

    /** Marca un producto como inactivo por SQL nativo (el endpoint de apertura rechaza inactivos). */
    private void deactivateProduct(int productId) {
        QuarkusTransaction.requiringNew().run(() -> entityManager.createNativeQuery(
            "UPDATE almacen.products SET is_active = false WHERE id = ?1")
            .setParameter(1, productId).executeUpdate());
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

    private int statField(String token, String field) {
        Response r = given().header("Authorization", "Bearer " + token).when().get("/warehouse/stats");
        r.then().statusCode(200);
        return r.jsonPath().getInt(field);
    }

    private OffsetDateTime nowLima() {
        return OffsetDateTime.now(com.scaramutti.tms.shared.util.DateUtils.LIMA);
    }

    private OffsetDateTime lastMonthLima() {
        return LocalDate.now(com.scaramutti.tms.shared.util.DateUtils.LIMA)
            .withDayOfMonth(1).minusDays(1).atTime(12, 0)
            .atZone(com.scaramutti.tms.shared.util.DateUtils.LIMA).toOffsetDateTime();
    }

    // ---------- activeProducts + lowStockCount -----------------------------------

    @Test
    void getStats_countsActiveProductsNotInactive() {
        String token = login("admin", "Admin1234");
        int baseActive = statField(token, "activeProducts");

        seedProduct("ZTEST_ST Active", "0", true);
        seedProduct("ZTEST_ST Inactive", "0", false);

        assertEquals(baseActive + 1, statField(token, "activeProducts"));
    }

    @Test
    void getStats_lowStockIsStrictLessThanMinStock() {
        String token = login("admin", "Admin1234");
        int baseLow = statField(token, "lowStockCount");

        int equalStock = seedProduct("ZTEST_ST Equal", "10", true);
        seedOpeningBalance(equalStock, "10", token);   // stock == minStock: NO low
        int belowStock = seedProduct("ZTEST_ST Below", "10", true);
        seedOpeningBalance(belowStock, "9", token);     // stock < minStock: low

        assertEquals(baseLow + 1, statField(token, "lowStockCount"));
    }

    @Test
    void getStats_inactiveLowStockProductNotCounted() {
        String token = login("admin", "Admin1234");
        int baseLow = statField(token, "lowStockCount");
        int baseActive = statField(token, "activeProducts");

        int p = seedProduct("ZTEST_ST InactiveLow", "10", true);
        seedOpeningBalance(p, "1", token);   // stock < minStock (la apertura exige producto activo)
        deactivateProduct(p);                // recien ahora se inactiva

        assertEquals(baseLow, statField(token, "lowStockCount"));
        assertEquals(baseActive, statField(token, "activeProducts"));
    }

    // ---------- entriesThisMonth / withdrawalsThisMonth --------------------------

    @Test
    void getStats_entryRegisteredThisMonthCounted_previousMonthNotCounted() {
        String token = login("admin", "Admin1234");
        int supplierId = seedSupplier("ZTEST_ST Proveedor");
        int baseEntries = statField(token, "entriesThisMonth");

        seedInvoiceNative(supplierId, "ZTEST-ST-THIS", nowLima(), "ACTIVE");
        seedInvoiceNative(supplierId, "ZTEST-ST-PREV", lastMonthLima(), "ACTIVE");

        assertEquals(baseEntries + 1, statField(token, "entriesThisMonth"));
    }

    @Test
    void getStats_cancelledEntryNotCounted() {
        String token = login("admin", "Admin1234");
        int supplierId = seedSupplier("ZTEST_ST ProveedorCancel");
        int baseEntries = statField(token, "entriesThisMonth");

        seedInvoiceNative(supplierId, "ZTEST-ST-CANC", nowLima(), "CANCELLED");

        assertEquals(baseEntries, statField(token, "entriesThisMonth"));
    }

    @Test
    void getStats_withdrawalRegisteredThisMonthCounted_previousMonthNotCounted() {
        String token = login("admin", "Admin1234");
        int productId = seedProduct("ZTEST_ST WD", "0", true);
        int workerId = seedWorker("ZTESTST01");
        int baseWithdrawals = statField(token, "withdrawalsThisMonth");

        seedWithdrawalNative(productId, "1", workerId, nowLima(), "ACTIVE");
        seedWithdrawalNative(productId, "1", workerId, lastMonthLima(), "ACTIVE");

        assertEquals(baseWithdrawals + 1, statField(token, "withdrawalsThisMonth"));
    }

    @Test
    void getStats_cancelledWithdrawalNotCounted() {
        String token = login("admin", "Admin1234");
        int productId = seedProduct("ZTEST_ST WDCancel", "0", true);
        int workerId = seedWorker("ZTESTST02");
        int baseWithdrawals = statField(token, "withdrawalsThisMonth");

        seedWithdrawalNative(productId, "1", workerId, nowLima(), "CANCELLED");

        assertEquals(baseWithdrawals, statField(token, "withdrawalsThisMonth"));
    }

    // ---------- shape ------------------------------------------------------------

    @Test
    void getStats_allFieldsPresentAndNonNegative() {
        String token = login("admin", "Admin1234");
        given().header("Authorization", "Bearer " + token).when().get("/warehouse/stats")
        .then().statusCode(200)
            .body("activeProducts", greaterThanOrEqualTo(0))
            .body("lowStockCount", greaterThanOrEqualTo(0))
            .body("entriesThisMonth", greaterThanOrEqualTo(0))
            .body("withdrawalsThisMonth", greaterThanOrEqualTo(0));
    }

    // ---------- roles ------------------------------------------------------------

    @Test
    void getStats_withoutToken_returns401() {
        given().when().get("/warehouse/stats").then().statusCode(401);
    }

    @Test
    void getStats_withSalesRole_returns403_COM003() {
        String token = login("lcampos", "Sales1234");
        given().header("Authorization", "Bearer " + token).when().get("/warehouse/stats")
        .then().statusCode(403).body("code", equalTo("COM-003"));
    }

    @Test
    void getStats_withDispatcherRole_returns403_COM003() {
        given().header("Authorization", "Bearer " + fabricateAccessToken("disp_test", "dispatcher"))
        .when().get("/warehouse/stats").then().statusCode(403).body("code", equalTo("COM-003"));
    }

    @Test
    void getStats_withWarehouseKeeperRole_returns200() {
        given().header("Authorization", "Bearer " + fabricateTokenForUser(adminId(), "wk_test", "warehouse_keeper"))
        .when().get("/warehouse/stats").then().statusCode(200);
    }

    @Test
    void getStats_withFinanceManagerRole_returns200() {
        given().header("Authorization", "Bearer " + fabricateTokenForUser(adminId(), "fm_test", "finance_manager"))
        .when().get("/warehouse/stats").then().statusCode(200);
    }
}
