package com.scaramutti.tms.warehouse.openingbalance;

import com.scaramutti.tms.support.WarehouseTestData;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static com.scaramutti.tms.support.TestAuth.fabricateAccessToken;
import static com.scaramutti.tms.support.TestAuth.fabricateTokenForUser;
import static com.scaramutti.tms.support.TestAuth.login;
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

    @Inject EntityManager entityManager;
    @Inject WarehouseTestData fixtures;

    @AfterEach
    void cleanupFixtures() {
        QuarkusTransaction.requiringNew().run(() -> {
            fixtures.deleteWarehouseTestData();
            fixtures.deleteTestWorkers();
        });
    }

    // ---------- fixtures --------------------------------------------------------

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
                    .setParameter(4, fixtures.adminId())
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
                .setParameter(4, fixtures.adminId())
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
                    .setParameter(5, fixtures.adminId())
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
                .setParameter(5, fixtures.adminId())
                .executeUpdate();
        });
    }

    private String requestBody(int productId, String quantity, String observations) {
        String obs = observations == null ? "null" : "\"" + observations + "\"";
        return "{\"productId\":" + productId + ",\"quantity\":" + quantity + ",\"observations\":" + obs + "}";
    }

    // ---------- POST: happy path --------------------------------------------------

    @Test
    void create_happyPath_returns201WithSummaryAndRegisteredByAndAt() {
        int productId = fixtures.seedProduct("ZTEST_OB Happy");
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
        int productId = fixtures.seedProduct("ZTEST_OB QuantityZero");
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
        int productId = fixtures.seedProduct("ZTEST_OB Inactive", false);
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
        int productId = fixtures.seedProduct("ZTEST_OB Duplicado");
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
        int productId = fixtures.seedProduct("ZTEST_OB ConEntrada");
        int supplierId = fixtures.seedSupplier("ZTEST_Proveedor OB Entrada");
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
        int productId = fixtures.seedProduct("ZTEST_OB Ambos");
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(requestBody(productId, "10", null))
        .when()
            .post("/warehouse/opening-balances")
        .then()
            .statusCode(201);

        int supplierId = fixtures.seedSupplier("ZTEST_Proveedor OB Ambos");
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
        int productId = fixtures.seedProduct("ZTEST_OB ConSalida");
        int workerId = fixtures.seedWorker("ZTESTW100", "Pedro", "Rios", "ZTEST Operario", true);
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
        int productId = fixtures.seedProduct("ZTEST_OB SoloAnulados");
        int supplierId = fixtures.seedSupplier("ZTEST_Proveedor OB Anulado");
        int cancelledInvoice = seedPurchaseInvoice(supplierId, "ZTEST-OB-INV2", OffsetDateTime.now(), true);
        seedPurchaseInvoiceItem(cancelledInvoice, productId, "999");
        int workerId = fixtures.seedWorker("ZTESTW101", "Julia", "Soto", "ZTEST Operario", true);
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
        int productId = fixtures.seedProduct("ZTEST_OB SinQuantity");
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
        int productId = fixtures.seedProduct("ZTEST_OB QuantityNegativa");
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
        int productId = fixtures.seedProduct("ZTEST_OB RegisteredAtMicros");
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

    // Registrar el corte inicial es solo de `admin`: fija la línea base del kardex,
    // es inmutable y no se puede anular. Consultarlo sigue abierto al módulo (ver
    // los tests del GET más abajo, que corren con otros roles).
    @Test
    void create_withAdminRole_returns201() {
        int productId = fixtures.seedProduct("ZTEST_OB RoleAdmin");

        given()
            .header("Authorization", "Bearer " + login("admin", "Admin1234"))
            .contentType(ContentType.JSON)
            .body(requestBody(productId, "10", null))
        .when()
            .post("/warehouse/opening-balances")
        .then()
            .statusCode(201);
    }

    @Test
    void create_withoutToken_returns401() {
        int productId = fixtures.seedProduct("ZTEST_OB NoToken");

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
        int productId = fixtures.seedProduct("ZTEST_OB RoleSales");
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
        int productId = fixtures.seedProduct("ZTEST_OB RoleDispatcher");

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
    void create_withOperationsManagerRole_returns403_COM003() {
        int productId = fixtures.seedProduct("ZTEST_OB RoleOM");

        given()
            .header("Authorization", "Bearer " + fabricateTokenForUser(fixtures.adminId(), "om_test", "operations_manager"))
            .contentType(ContentType.JSON)
            .body(requestBody(productId, "10", null))
        .when()
            .post("/warehouse/opening-balances")
        .then()
            .statusCode(403)
            .body("code", equalTo("COM-003"));
    }

    @Test
    void create_withFinanceManagerRole_returns403_COM003() {
        int productId = fixtures.seedProduct("ZTEST_OB RoleFM");

        given()
            .header("Authorization", "Bearer " + fabricateTokenForUser(fixtures.adminId(), "fm_test", "finance_manager"))
            .contentType(ContentType.JSON)
            .body(requestBody(productId, "10", null))
        .when()
            .post("/warehouse/opening-balances")
        .then()
            .statusCode(403)
            .body("code", equalTo("COM-003"));
    }

    @Test
    void create_withWarehouseKeeperRole_returns403_COM003() {
        int productId = fixtures.seedProduct("ZTEST_OB RoleWK");

        given()
            .header("Authorization", "Bearer " + fabricateTokenForUser(fixtures.adminId(), "wk_test", "warehouse_keeper"))
            .contentType(ContentType.JSON)
            .body(requestBody(productId, "10", null))
        .when()
            .post("/warehouse/opening-balances")
        .then()
            .statusCode(403)
            .body("code", equalTo("COM-003"));
    }

    @Test
    void create_withGeneralManagerRole_returns403_COM003() {
        int productId = fixtures.seedProduct("ZTEST_OB RoleGM");

        given()
            .header("Authorization", "Bearer " + fabricateTokenForUser(fixtures.adminId(), "gm_test", "general_manager"))
            .contentType(ContentType.JSON)
            .body(requestBody(productId, "10", null))
        .when()
            .post("/warehouse/opening-balances")
        .then()
            .statusCode(403)
            .body("code", equalTo("COM-003"));
    }

    // ---------- GET: listado ----------------------------------------------------------

    @Test
    void list_multipleOpeningBalances_returns200OrderedByRegisteredAtDesc() {
        int productA = fixtures.seedProduct("ZTEST_OB ListA");
        int productB = fixtures.seedProduct("ZTEST_OB ListB");
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
        int productA = fixtures.seedProduct("ZTEST_OB FiltroA");
        int productB = fixtures.seedProduct("ZTEST_OB FiltroB");
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
        int productA = fixtures.seedProduct("ZTEST_OB PagA");
        int productB = fixtures.seedProduct("ZTEST_OB PagB");
        int productC = fixtures.seedProduct("ZTEST_OB PagC");
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

    // Consultar no se restringió junto con el registro: un rol del módulo que ya NO
    // puede registrar sigue viendo el listado.
    @Test
    void list_withWarehouseKeeperRole_returns200() {
        given()
            .header("Authorization", "Bearer " + fabricateAccessToken("wk_test", "warehouse_keeper"))
        .when()
            .get("/warehouse/opening-balances")
        .then()
            .statusCode(200);
    }
}
