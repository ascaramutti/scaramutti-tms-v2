package com.scaramutti.tms.warehouse.openingbalance;

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
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * Integration tests de GET/POST /warehouse/opening-balances. La siembra de
 * productos/proveedores/facturas/retiros reusa el mismo molde SQL nativo de
 * {@code WarehouseKardexResourceTest} (no hay endpoints propios todavía para
 * esas tablas); la apertura en sí se crea via el endpoint bajo prueba.
 */
@QuarkusTest
class WarehouseOpeningBalanceResourceTest {

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

    /** Factura ACTIVA (o CANCELLED con motivo) con created_at explicito. */
    private int seedPurchaseInvoice(int supplierId, String invoiceNumber, OffsetDateTime createdAt, boolean cancelled) {
        return QuarkusTransaction.requiringNew().call(() -> {
            if (!cancelled) {
                Object id = entityManager.createNativeQuery(
                    "INSERT INTO almacen.purchase_invoices "
                        + "(supplier_id, invoice_number, invoice_date, currency_id, registered_by, created_at, updated_at, status) "
                        + "VALUES (?1, ?2, ?3, (SELECT id FROM public.currencies WHERE code = 'USD'), ?4, ?5, ?5, 'ACTIVE') "
                        + "RETURNING id")
                    .setParameter(1, supplierId)
                    .setParameter(2, invoiceNumber)
                    .setParameter(3, createdAt.toLocalDate())
                    .setParameter(4, adminId())
                    .setParameter(5, createdAt)
                    .getSingleResult();
                return ((Number) id).intValue();
            }
            Object id = entityManager.createNativeQuery(
                "INSERT INTO almacen.purchase_invoices "
                    + "(supplier_id, invoice_number, invoice_date, currency_id, registered_by, created_at, updated_at, "
                    + "status, cancel_reason, cancelled_by, cancelled_at) "
                    + "VALUES (?1, ?2, ?3, (SELECT id FROM public.currencies WHERE code = 'USD'), ?4, ?5, ?5, "
                    + "'CANCELLED', 'ZTEST anulada', ?4, ?5) RETURNING id")
                .setParameter(1, supplierId)
                .setParameter(2, invoiceNumber)
                .setParameter(3, createdAt.toLocalDate())
                .setParameter(4, adminId())
                .setParameter(5, createdAt)
                .getSingleResult();
            return ((Number) id).intValue();
        });
    }

    private void seedPurchaseInvoiceItem(int invoiceId, int productId, String quantity) {
        QuarkusTransaction.requiringNew().run(() ->
            entityManager.createNativeQuery(
                "INSERT INTO almacen.purchase_invoice_items (invoice_id, product_id, quantity, unit_price) "
                    + "VALUES (?1, ?2, CAST(?3 AS NUMERIC), 10)")
                .setParameter(1, invoiceId)
                .setParameter(2, productId)
                .setParameter(3, quantity)
                .executeUpdate());
    }

    /** DNI ya deberia existir (DevDataSeeder lo garantiza al arrancar); se resuelve/crea defensivamente. */
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

    private int seedWorker(String documentNumber, String firstName, String lastName) {
        return QuarkusTransaction.requiringNew().call(() -> {
            Worker worker = new Worker();
            worker.firstName = firstName;
            worker.lastName = lastName;
            worker.documentTypeId = dniDocumentTypeId();
            worker.documentNumber = documentNumber;
            worker.position = "ZTEST Operario";
            worker.isActive = true;
            worker.createdAt = OffsetDateTime.now();
            workerRepository.persist(worker);
            return worker.id;
        });
    }

    private void seedWithdrawal(int productId, String quantity, OffsetDateTime withdrawnAt, int receivedBy, boolean cancelled) {
        QuarkusTransaction.requiringNew().run(() -> {
            if (!cancelled) {
                entityManager.createNativeQuery(
                    "INSERT INTO almacen.withdrawals "
                        + "(product_id, quantity, withdrawn_at, received_by, registered_by, status) "
                        + "VALUES (?1, CAST(?2 AS NUMERIC), ?3, ?4, ?5, 'ACTIVE')")
                    .setParameter(1, productId)
                    .setParameter(2, quantity)
                    .setParameter(3, withdrawnAt)
                    .setParameter(4, receivedBy)
                    .setParameter(5, adminId())
                    .executeUpdate();
                return;
            }
            entityManager.createNativeQuery(
                "INSERT INTO almacen.withdrawals "
                    + "(product_id, quantity, withdrawn_at, received_by, registered_by, status, "
                    + "cancel_reason, cancelled_by, cancelled_at) "
                    + "VALUES (?1, CAST(?2 AS NUMERIC), ?3, ?4, ?5, 'CANCELLED', 'ZTEST anulado', ?5, ?3)")
                .setParameter(1, productId)
                .setParameter(2, quantity)
                .setParameter(3, withdrawnAt)
                .setParameter(4, receivedBy)
                .setParameter(5, adminId())
                .executeUpdate();
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

    /** registeredBy es FK real → token con el id de un usuario existente (admin). */
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

    private String requestBody(int productId, String quantity, String observations) {
        String obs = observations == null ? "null" : "\"" + observations + "\"";
        return "{\"productId\":" + productId + ",\"quantity\":" + quantity + ",\"observations\":" + obs + "}";
    }

    // ---------- POST: happy path --------------------------------------------------

    @Test
    void create_happyPath_returns201WithSummaryAndRegisteredByAndAt() {
        int productId = seedProduct("ZTEST_OB Happy");
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(requestBody(productId, "100", "Conteo inicial"))
        .when()
            .post("/warehouse/opening-balances")
        .then()
            .statusCode(201)
            .body("id", notNullValue())
            .body("product.id", equalTo(productId))
            .body("product.unitCode", equalTo("UND"))
            .body("quantity", equalTo(100))
            .body("observations", equalTo("Conteo inicial"))
            .body("registeredBy.username", equalTo("admin"))
            .body("registeredAt", notNullValue());
    }

    @Test
    void create_withQuantityZero_returns201() {
        int productId = seedProduct("ZTEST_OB QuantityZero");
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(requestBody(productId, "0", null))
        .when()
            .post("/warehouse/opening-balances")
        .then()
            .statusCode(201)
            .body("quantity", equalTo(0))
            .body("observations", nullValue());
    }

    // ---------- POST: producto inexistente/inactivo (WH-004) -----------------------

    @Test
    void create_nonexistentProduct_returns400_WH004() {
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(requestBody(999999, "10", null))
        .when()
            .post("/warehouse/opening-balances")
        .then()
            .statusCode(400)
            .body("code", equalTo("WH-004"));
    }

    @Test
    void create_inactiveProduct_returns400_WH004() {
        int productId = seedProduct("ZTEST_OB Inactive", false);
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(requestBody(productId, "10", null))
        .when()
            .post("/warehouse/opening-balances")
        .then()
            .statusCode(400)
            .body("code", equalTo("WH-004"));
    }

    // ---------- POST: duplicado (WH-009) --------------------------------------------

    @Test
    void create_secondOpeningBalanceForSameProduct_returns409_WH009() {
        int productId = seedProduct("ZTEST_OB Duplicado");
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(requestBody(productId, "10", null))
        .when()
            .post("/warehouse/opening-balances")
        .then()
            .statusCode(201);

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(requestBody(productId, "20", null))
        .when()
            .post("/warehouse/opening-balances")
        .then()
            .statusCode(409)
            .body("code", equalTo("WH-009"));
    }

    // ---------- POST: producto con movimientos previos (WH-011) ---------------------

    @Test
    void create_productWithActiveEntrada_returns409_WH011() {
        int productId = seedProduct("ZTEST_OB ConEntrada");
        int supplierId = seedSupplier("ZTEST_Proveedor OB Entrada");
        int invoiceId = seedPurchaseInvoice(supplierId, "ZTEST-OB-INV1", OffsetDateTime.now(), false);
        seedPurchaseInvoiceItem(invoiceId, productId, "50");
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(requestBody(productId, "10", null))
        .when()
            .post("/warehouse/opening-balances")
        .then()
            .statusCode(409)
            .body("code", equalTo("WH-011"));
    }

    @Test
    void create_productWithBothOpeningAndMovements_returns409_WH009_takesPrecedence() {
        // Un producto con apertura previa Y movimientos: el chequeo de apertura
        // duplicada (WH-009) corre antes que el de movimientos (WH-011), asi que
        // gana WH-009. La apertura se registra primero (sin movimientos aun), y el
        // movimiento se siembra despues, para llegar al estado con ambos.
        int productId = seedProduct("ZTEST_OB Ambos");
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(requestBody(productId, "10", null))
        .when()
            .post("/warehouse/opening-balances")
        .then()
            .statusCode(201);

        int supplierId = seedSupplier("ZTEST_Proveedor OB Ambos");
        int invoiceId = seedPurchaseInvoice(supplierId, "ZTEST-OB-INV2", OffsetDateTime.now(), false);
        seedPurchaseInvoiceItem(invoiceId, productId, "50");

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(requestBody(productId, "20", null))
        .when()
            .post("/warehouse/opening-balances")
        .then()
            .statusCode(409)
            .body("code", equalTo("WH-009"));
    }

    @Test
    void create_productWithActiveSalida_returns409_WH011() {
        int productId = seedProduct("ZTEST_OB ConSalida");
        int workerId = seedWorker("ZTESTW100", "Pedro", "Rios");
        seedWithdrawal(productId, "5", OffsetDateTime.now(), workerId, false);
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(requestBody(productId, "10", null))
        .when()
            .post("/warehouse/opening-balances")
        .then()
            .statusCode(409)
            .body("code", equalTo("WH-011"));
    }

    /** Un movimiento ANULADO (CANCELLED) NO bloquea la apertura (la VIEW ya lo excluye). */
    @Test
    void create_productWithOnlyCancelledMovements_returns201() {
        int productId = seedProduct("ZTEST_OB SoloAnulados");
        int supplierId = seedSupplier("ZTEST_Proveedor OB Anulado");
        int cancelledInvoice = seedPurchaseInvoice(supplierId, "ZTEST-OB-INV2", OffsetDateTime.now(), true);
        seedPurchaseInvoiceItem(cancelledInvoice, productId, "999");
        int workerId = seedWorker("ZTESTW101", "Julia", "Soto");
        seedWithdrawal(productId, "999", OffsetDateTime.now(), workerId, true);
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(requestBody(productId, "10", null))
        .when()
            .post("/warehouse/opening-balances")
        .then()
            .statusCode(201);
    }

    // ---------- POST: body invalido (COM-001) ----------------------------------------

    @Test
    void create_withoutProductId_returns400_COM001() {
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body("{\"quantity\":10}")
        .when()
            .post("/warehouse/opening-balances")
        .then()
            .statusCode(400)
            .body("code", equalTo("COM-001"));
    }

    @Test
    void create_withoutQuantity_returns400_COM001() {
        int productId = seedProduct("ZTEST_OB SinQuantity");
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body("{\"productId\":" + productId + "}")
        .when()
            .post("/warehouse/opening-balances")
        .then()
            .statusCode(400)
            .body("code", equalTo("COM-001"));
    }

    @Test
    void create_withNegativeQuantity_returns400_COM001() {
        int productId = seedProduct("ZTEST_OB QuantityNegativa");
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(requestBody(productId, "-1", null))
        .when()
            .post("/warehouse/opening-balances")
        .then()
            .statusCode(400)
            .body("code", equalTo("COM-001"));
    }

    // ---------- POST: regresion registeredAt (MICROS, clase D-12) -------------------

    @Test
    void create_thenGet_registeredAtMatchesExactly() {
        int productId = seedProduct("ZTEST_OB RegisteredAtMicros");
        String token = login("admin", "Admin1234");

        String registeredAtFromPost = given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(requestBody(productId, "10", null))
        .when()
            .post("/warehouse/opening-balances")
        .then()
            .statusCode(201)
            .extract().jsonPath().getString("registeredAt");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/warehouse/opening-balances?productId=" + productId)
        .then()
            .statusCode(200)
            .body("content[0].registeredAt", equalTo(registeredAtFromPost));
    }

    // ---------- POST: roles ----------------------------------------------------------

    @Test
    void create_withoutToken_returns401() {
        int productId = seedProduct("ZTEST_OB NoToken");

        given()
            .contentType(ContentType.JSON)
            .body(requestBody(productId, "10", null))
        .when()
            .post("/warehouse/opening-balances")
        .then()
            .statusCode(401);
    }

    @Test
    void create_withSalesRole_returns403_COM003() {
        int productId = seedProduct("ZTEST_OB RoleSales");
        String token = login("lcampos", "Sales1234");

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(requestBody(productId, "10", null))
        .when()
            .post("/warehouse/opening-balances")
        .then()
            .statusCode(403)
            .body("code", equalTo("COM-003"));
    }

    @Test
    void create_withDispatcherRole_returns403_COM003() {
        int productId = seedProduct("ZTEST_OB RoleDispatcher");

        given()
            .header("Authorization", "Bearer " + fabricateAccessToken("disp_test", "dispatcher"))
            .contentType(ContentType.JSON)
            .body(requestBody(productId, "10", null))
        .when()
            .post("/warehouse/opening-balances")
        .then()
            .statusCode(403)
            .body("code", equalTo("COM-003"));
    }

    @Test
    void create_withOperationsManagerRole_returns201() {
        int productId = seedProduct("ZTEST_OB RoleOM");

        given()
            .header("Authorization", "Bearer " + fabricateTokenForUser(adminId(), "om_test", "operations_manager"))
            .contentType(ContentType.JSON)
            .body(requestBody(productId, "10", null))
        .when()
            .post("/warehouse/opening-balances")
        .then()
            .statusCode(201);
    }

    @Test
    void create_withFinanceManagerRole_returns201() {
        int productId = seedProduct("ZTEST_OB RoleFM");

        given()
            .header("Authorization", "Bearer " + fabricateTokenForUser(adminId(), "fm_test", "finance_manager"))
            .contentType(ContentType.JSON)
            .body(requestBody(productId, "10", null))
        .when()
            .post("/warehouse/opening-balances")
        .then()
            .statusCode(201);
    }

    @Test
    void create_withWarehouseKeeperRole_returns201() {
        int productId = seedProduct("ZTEST_OB RoleWK");

        given()
            .header("Authorization", "Bearer " + fabricateTokenForUser(adminId(), "wk_test", "warehouse_keeper"))
            .contentType(ContentType.JSON)
            .body(requestBody(productId, "10", null))
        .when()
            .post("/warehouse/opening-balances")
        .then()
            .statusCode(201);
    }

    @Test
    void create_withGeneralManagerRole_returns201() {
        int productId = seedProduct("ZTEST_OB RoleGM");

        given()
            .header("Authorization", "Bearer " + fabricateTokenForUser(adminId(), "gm_test", "general_manager"))
            .contentType(ContentType.JSON)
            .body(requestBody(productId, "10", null))
        .when()
            .post("/warehouse/opening-balances")
        .then()
            .statusCode(201);
    }

    // ---------- GET: listado ----------------------------------------------------------

    @Test
    void list_multipleOpeningBalances_returns200OrderedByRegisteredAtDesc() {
        int productA = seedProduct("ZTEST_OB ListA");
        int productB = seedProduct("ZTEST_OB ListB");
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(requestBody(productA, "10", null))
        .when()
            .post("/warehouse/opening-balances")
        .then()
            .statusCode(201);

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(requestBody(productB, "20", null))
        .when()
            .post("/warehouse/opening-balances")
        .then()
            .statusCode(201);

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/warehouse/opening-balances?size=100")
        .then()
            .statusCode(200)
            .body("content[0].product.id", equalTo(productB))
            .body("content[1].product.id", equalTo(productA));
    }

    @Test
    void list_filterByProductId_returnsOnlyThatProductsOpeningBalance() {
        int productA = seedProduct("ZTEST_OB FiltroA");
        int productB = seedProduct("ZTEST_OB FiltroB");
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(requestBody(productA, "10", null))
        .when()
            .post("/warehouse/opening-balances")
        .then()
            .statusCode(201);

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(requestBody(productB, "20", null))
        .when()
            .post("/warehouse/opening-balances")
        .then()
            .statusCode(201);

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/warehouse/opening-balances?productId=" + productA)
        .then()
            .statusCode(200)
            .body("totalElements", equalTo(1))
            .body("content[0].product.id", equalTo(productA));
    }

    @Test
    void list_emptyPage_returns200EmptyContent() {
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/warehouse/opening-balances?productId=999999")
        .then()
            .statusCode(200)
            .body("content.size()", equalTo(0))
            .body("empty", equalTo(true))
            .body("totalElements", equalTo(0));
    }

    @Test
    void list_pagination_secondPageReturnsRemainingElements() {
        int productA = seedProduct("ZTEST_OB PagA");
        int productB = seedProduct("ZTEST_OB PagB");
        int productC = seedProduct("ZTEST_OB PagC");
        String token = login("admin", "Admin1234");

        for (int productId : new int[] {productA, productB, productC}) {
            given()
                .header("Authorization", "Bearer " + token)
                .contentType(ContentType.JSON)
                .body(requestBody(productId, "10", null))
            .when()
                .post("/warehouse/opening-balances")
            .then()
                .statusCode(201);
        }

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/warehouse/opening-balances?productId=" + productA + "&page=0&size=1")
        .then()
            .statusCode(200)
            .body("totalElements", equalTo(1))
            .body("content.size()", equalTo(1));
    }

    @Test
    void list_sizeAboveMax_returns400_COM001() {
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/warehouse/opening-balances?size=101")
        .then()
            .statusCode(400)
            .body("code", equalTo("COM-001"));
    }

    @Test
    void list_negativePage_returns400_COM001() {
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/warehouse/opening-balances?page=-1")
        .then()
            .statusCode(400)
            .body("code", equalTo("COM-001"));
    }

    /**
     * Un {@code productId} no numerico no llega a invocar el resource method: el
     * param-converter de JAX-RS falla ANTES del match de ruta y RESTEasy Reactive
     * responde 404 vacio (mismo comportamiento pre-existente que el kardex con
     * fechas malformadas). NO es un bug de este endpoint.
     */
    @Test
    void list_nonNumericProductId_returns404() {
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/warehouse/opening-balances?productId=abc")
        .then()
            .statusCode(404);
    }

    @Test
    void list_withoutToken_returns401() {
        given()
        .when()
            .get("/warehouse/opening-balances")
        .then()
            .statusCode(401);
    }

    @Test
    void list_withSalesRole_returns403_COM003() {
        String token = login("lcampos", "Sales1234");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/warehouse/opening-balances")
        .then()
            .statusCode(403)
            .body("code", equalTo("COM-003"));
    }
}
