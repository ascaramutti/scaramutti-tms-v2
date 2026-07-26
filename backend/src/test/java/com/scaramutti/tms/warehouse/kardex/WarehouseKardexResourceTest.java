package com.scaramutti.tms.warehouse.kardex;

import com.scaramutti.tms.support.WarehouseTestData;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static com.scaramutti.tms.support.TestAuth.fabricateAccessToken;
import static com.scaramutti.tms.support.TestAuth.login;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

/**
 * Integration tests de GET /warehouse/products/{id}/kardex. Siembra por SQL
 * nativo (opening_balances/purchase_invoices+items/withdrawals): no hay
 * endpoints ni entities JPA para esas tablas todavia. Los timestamps son
 * {@code DEFAULT CURRENT_TIMESTAMP} SIN trigger — se insertan valores
 * EXPLICITOS para controlar {@code movedAt} determinsticamente.
 *
 * <p>La zona de negocio es America/Lima (UTC-5, sin DST) — los offsets se
 * escriben directo como {@code -05:00} para no depender de la config de zona
 * del runner de tests.
 */
@QuarkusTest
class WarehouseKardexResourceTest {

    private static final ZoneOffset LIMA_OFFSET = ZoneOffset.of("-05:00");

    @Inject EntityManager entityManager;
    @Inject WarehouseTestData fixtures;

    @AfterEach
    void cleanupFixtures() {
        QuarkusTransaction.requiringNew().run(() -> {
            fixtures.deleteWarehouseTestData();
            fixtures.deleteTestFleet();
            fixtures.deleteTestWorkers();
        });
    }

    // ---------- fixtures --------------------------------------------------------

    /** Se queda local: fija registered_at explicito (la variante de support usa el del servidor). */
    private void seedOpeningBalance(int productId, String quantity, OffsetDateTime registeredAt) {
        QuarkusTransaction.requiringNew().run(() ->
            entityManager.createNativeQuery(
                "INSERT INTO almacen.opening_balances (product_id, quantity, registered_by, registered_at) "
                    + "VALUES (?1, CAST(?2 AS NUMERIC), ?3, ?4)")
                .setParameter(1, productId)
                .setParameter(2, quantity)
                .setParameter(3, fixtures.adminId())
                .setParameter(4, registeredAt)
                .executeUpdate());
    }

    /** Factura ACTIVA (o CANCELLED con motivo) con created_at explicito, usado como movedAt de sus items. */
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

    private int seedWithdrawal(
        int productId, String quantity, OffsetDateTime withdrawnAt, int receivedBy, Integer tractorId, boolean cancelled
    ) {
        return QuarkusTransaction.requiringNew().call(() -> {
            if (!cancelled) {
                return ((Number) entityManager.createNativeQuery(
                    "INSERT INTO almacen.withdrawals "
                        + "(product_id, quantity, withdrawn_at, received_by, tractor_id, registered_by, status) "
                        + "VALUES (?1, CAST(?2 AS NUMERIC), ?3, ?4, ?5, ?6, 'ACTIVE') RETURNING id")
                    .setParameter(1, productId)
                    .setParameter(2, quantity)
                    .setParameter(3, withdrawnAt)
                    .setParameter(4, receivedBy)
                    .setParameter(5, tractorId)
                    .setParameter(6, fixtures.adminId())
                    .getSingleResult()).intValue();
            }
            return ((Number) entityManager.createNativeQuery(
                "INSERT INTO almacen.withdrawals "
                    + "(product_id, quantity, withdrawn_at, received_by, tractor_id, registered_by, status, "
                    + "cancel_reason, cancelled_by, cancelled_at) "
                    + "VALUES (?1, CAST(?2 AS NUMERIC), ?3, ?4, ?5, ?6, 'CANCELLED', 'ZTEST anulado', ?6, ?3) RETURNING id")
                .setParameter(1, productId)
                .setParameter(2, quantity)
                .setParameter(3, withdrawnAt)
                .setParameter(4, receivedBy)
                .setParameter(5, tractorId)
                .setParameter(6, fixtures.adminId())
                .getSingleResult()).intValue();
        });
    }

    private OffsetDateTime lima(int year, int month, int day, int hour, int minute) {
        return OffsetDateTime.of(year, month, day, hour, minute, 0, 0, LIMA_OFFSET);
    }

    // ---------- shape / referencia por tipo --------------------------------------

    @Test
    void kardex_apertura_shapeAndReference() {
        int productId = fixtures.seedProduct("ZTEST_Kardex Apertura");
        seedOpeningBalance(productId, "100", lima(2026, 1, 1, 8, 0));
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/warehouse/products/" + productId + "/kardex")
        .then()
            .statusCode(200)
            .body("content[0].movementType", equalTo("APERTURA"))
            .body("content[0].quantity", equalTo(100.0f))
            .body("content[0].balance", equalTo(100.0f))
            .body("content[0].sourceId", nullValue())
            .body("content[0].reference", equalTo("Apertura de inventario"))
            .body("content[0].registeredBy.username", equalTo("admin"));
    }

    @Test
    void kardex_entrada_referenceComposesInvoiceAndSupplier() {
        int productId = fixtures.seedProduct("ZTEST_Kardex Entrada");
        int supplierId = fixtures.seedSupplier("ZTEST_Proveedor Norte");
        int invoiceId = seedPurchaseInvoice(supplierId, "ZTEST-INV-001", lima(2026, 1, 2, 9, 0), false);
        seedPurchaseInvoiceItem(invoiceId, productId, "50");
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/warehouse/products/" + productId + "/kardex")
        .then()
            .statusCode(200)
            .body("content[0].movementType", equalTo("ENTRADA"))
            .body("content[0].quantity", equalTo(50.0f))
            .body("content[0].balance", equalTo(50.0f))
            .body("content[0].sourceId", equalTo(invoiceId))
            .body("content[0].reference", equalTo("Factura ZTEST-INV-001 · ZTEST_Proveedor Norte"));
    }

    @Test
    void kardex_salida_withUnit_referenceIncludesPlate() {
        int productId = fixtures.seedProduct("ZTEST_Kardex SalidaConUnidad");
        seedOpeningBalance(productId, "50", lima(2026, 1, 1, 8, 0));
        int workerId = fixtures.seedWorker("ZTESTW001", "Carlos", "Quispe", "ZTEST Operario", true);
        int tractorId = fixtures.seedTractor("Z90001");
        int withdrawalId = seedWithdrawal(productId, "10", lima(2026, 1, 2, 10, 0), workerId, tractorId, false);
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/warehouse/products/" + productId + "/kardex")
        .then()
            .statusCode(200)
            .body("content[0].movementType", equalTo("SALIDA"))
            .body("content[0].quantity", equalTo(10.0f))
            .body("content[0].balance", equalTo(40.0f))
            .body("content[0].sourceId", equalTo(withdrawalId))
            .body("content[0].reference", equalTo("Retiro · recibe Carlos Quispe · Z90001"));
    }

    @Test
    void kardex_salida_withoutUnit_referenceOmitsPlateGracefully() {
        int productId = fixtures.seedProduct("ZTEST_Kardex SalidaSinUnidad");
        seedOpeningBalance(productId, "50", lima(2026, 1, 1, 8, 0));
        int workerId = fixtures.seedWorker("ZTESTW002", "Rosa", "Diaz", "ZTEST Operario", true);
        seedWithdrawal(productId, "5", lima(2026, 1, 2, 10, 0), workerId, null, false);
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/warehouse/products/" + productId + "/kardex")
        .then()
            .statusCode(200)
            .body("content[0].reference", equalTo("Retiro · recibe Rosa Diaz"));
    }

    // ---------- RN-WH13: saldo corrido sobre historia completa (regresion obligatoria) --

    /**
     * 4 movimientos cronologicos: APERTURA+100, ENTRADA+50, SALIDA-30, ENTRADA+20
     * -> balances 100,150,120,140. Orden DESC por movedAt: 140,120,150,100.
     * size=2: pagina 0 = [140,120]; pagina 1 = [150,100]. El balance NO se
     * reinicia por pagina (se calcula sobre la historia completa, no sobre la
     * pagina).
     */
    @Test
    void kardex_pagination_balanceIsGlobal_notResetPerPage() {
        int productId = fixtures.seedProduct("ZTEST_Kardex Paginacion");
        int supplierId = fixtures.seedSupplier("ZTEST_Proveedor Paginacion");
        seedOpeningBalance(productId, "100", lima(2026, 2, 1, 8, 0));
        int invoice1 = seedPurchaseInvoice(supplierId, "ZTEST-INV-PAG1", lima(2026, 2, 2, 8, 0), false);
        seedPurchaseInvoiceItem(invoice1, productId, "50");
        int workerId = fixtures.seedWorker("ZTESTW010", "Luis", "Mamani", "ZTEST Operario", true);
        seedWithdrawal(productId, "30", lima(2026, 2, 3, 8, 0), workerId, null, false);
        int invoice2 = seedPurchaseInvoice(supplierId, "ZTEST-INV-PAG2", lima(2026, 2, 4, 8, 0), false);
        seedPurchaseInvoiceItem(invoice2, productId, "20");
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/warehouse/products/" + productId + "/kardex?size=2&page=0")
        .then()
            .statusCode(200)
            .body("totalElements", equalTo(4))
            .body("content[0].balance", equalTo(140.0f))
            .body("content[1].balance", equalTo(120.0f));

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/warehouse/products/" + productId + "/kardex?size=2&page=1")
        .then()
            .statusCode(200)
            .body("content[0].balance", equalTo(150.0f))
            .body("content[1].balance", equalTo(100.0f));
    }

    /**
     * dateFrom que excluye la APERTURA(+100) pero incluye la ENTRADA(+50): su
     * balance mostrado sigue siendo 150 (historia completa), NO 50 (como si
     * fuera el primer movimiento del rango filtrado).
     */
    @Test
    void kardex_dateFilter_balanceStillReflectsFullHistory() {
        int productId = fixtures.seedProduct("ZTEST_Kardex FiltroFecha");
        int supplierId = fixtures.seedSupplier("ZTEST_Proveedor FiltroFecha");
        seedOpeningBalance(productId, "100", lima(2026, 3, 1, 8, 0));
        int invoice = seedPurchaseInvoice(supplierId, "ZTEST-INV-FEC1", lima(2026, 3, 5, 8, 0), false);
        seedPurchaseInvoiceItem(invoice, productId, "50");
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/warehouse/products/" + productId + "/kardex?dateFrom=2026-03-05")
        .then()
            .statusCode(200)
            .body("totalElements", equalTo(1))
            .body("content[0].movementType", equalTo("ENTRADA"))
            .body("content[0].quantity", equalTo(50.0f))
            .body("content[0].balance", equalTo(150.0f));
    }

    /** Borde de medianoche Lima: 23:50 del dia D-1 vs 00:05 del dia D. */
    @Test
    void kardex_dateFilter_respectsLimaMidnightBoundary() {
        int productId = fixtures.seedProduct("ZTEST_Kardex Medianoche");
        int supplierId = fixtures.seedSupplier("ZTEST_Proveedor Medianoche");
        int invoiceLate = seedPurchaseInvoice(supplierId, "ZTEST-INV-MED1", lima(2026, 4, 9, 23, 50), false);
        seedPurchaseInvoiceItem(invoiceLate, productId, "10");
        int invoiceEarly = seedPurchaseInvoice(supplierId, "ZTEST-INV-MED2", lima(2026, 4, 10, 0, 5), false);
        seedPurchaseInvoiceItem(invoiceEarly, productId, "20");
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/warehouse/products/" + productId + "/kardex?dateTo=2026-04-09")
        .then()
            .statusCode(200)
            .body("totalElements", equalTo(1))
            .body("content[0].quantity", equalTo(10.0f))
            .body("content[0].balance", equalTo(10.0f));

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/warehouse/products/" + productId + "/kardex?dateFrom=2026-04-10")
        .then()
            .statusCode(200)
            .body("totalElements", equalTo(1))
            .body("content[0].quantity", equalTo(20.0f))
            .body("content[0].balance", equalTo(30.0f));
    }

    /** dateFrom > dateTo NO es 400: pagina vacia (mismo criterio que cotizaciones). */
    @Test
    void kardex_dateFromAfterDateTo_returns200EmptyContent() {
        int productId = fixtures.seedProduct("ZTEST_Kardex FechasInvertidas");
        seedOpeningBalance(productId, "10", lima(2026, 1, 1, 8, 0));
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/warehouse/products/" + productId + "/kardex?dateFrom=2026-05-10&dateTo=2026-05-01")
        .then()
            .statusCode(200)
            .body("content.size()", equalTo(0))
            .body("empty", equalTo(true));
    }

    // ---------- anulados excluidos, sin afectar balance ---------------------------

    @Test
    void kardex_cancelledInvoiceAndWithdrawal_excludedAndDoNotAffectBalance() {
        int productId = fixtures.seedProduct("ZTEST_Kardex Anulados");
        int supplierId = fixtures.seedSupplier("ZTEST_Proveedor Anulados");
        seedOpeningBalance(productId, "100", lima(2026, 6, 1, 8, 0));
        int cancelledInvoice = seedPurchaseInvoice(supplierId, "ZTEST-INV-CAN1", lima(2026, 6, 2, 8, 0), true);
        seedPurchaseInvoiceItem(cancelledInvoice, productId, "999");
        int workerId = fixtures.seedWorker("ZTESTW020", "Ana", "Vega", "ZTEST Operario", true);
        seedWithdrawal(productId, "999", lima(2026, 6, 3, 8, 0), workerId, null, true);
        int activeInvoice = seedPurchaseInvoice(supplierId, "ZTEST-INV-CAN2", lima(2026, 6, 4, 8, 0), false);
        seedPurchaseInvoiceItem(activeInvoice, productId, "30");
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/warehouse/products/" + productId + "/kardex")
        .then()
            .statusCode(200)
            .body("totalElements", equalTo(2))                    // apertura + entrada activa (los anulados no aparecen)
            .body("content[0].movementType", equalTo("ENTRADA"))
            .body("content[0].quantity", equalTo(30.0f))
            .body("content[0].balance", equalTo(130.0f));          // 100 + 30, sin los 999 anulados
    }

    // ---------- desempate deterministico de moved_at iguales ----------------------

    @Test
    void kardex_sameMovedAt_tiebreaksDeterministicallyByMovementSeq() {
        int productId = fixtures.seedProduct("ZTEST_Kardex Empate");
        int supplierId = fixtures.seedSupplier("ZTEST_Proveedor Empate");
        OffsetDateTime sameInstant = lima(2026, 7, 1, 12, 0);
        int invoiceId = seedPurchaseInvoice(supplierId, "ZTEST-INV-TIE1", sameInstant, false);
        seedPurchaseInvoiceItem(invoiceId, productId, "10");  // menor movement_seq (item insertado primero)
        seedPurchaseInvoiceItem(invoiceId, productId, "20");  // mayor movement_seq (item insertado despues)
        String token = login("admin", "Admin1234");

        // Mismo moved_at (misma factura) y mismo type-priority (ambos ENTRADA):
        // orden final DESC por movement_seq -> el item insertado DESPUES sale primero.
        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/warehouse/products/" + productId + "/kardex")
        .then()
            .statusCode(200)
            .body("totalElements", equalTo(2))
            .body("content[0].quantity", equalTo(20.0f))
            .body("content[0].balance", equalTo(30.0f))
            .body("content[1].quantity", equalTo(10.0f))
            .body("content[1].balance", equalTo(10.0f));
    }

    // ---------- casos borde --------------------------------------------------------

    @Test
    void kardex_productWithoutMovements_returnsEmptyPage() {
        int productId = fixtures.seedProduct("ZTEST_Kardex SinMovimientos");
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/warehouse/products/" + productId + "/kardex")
        .then()
            .statusCode(200)
            .body("content.size()", equalTo(0))
            .body("empty", equalTo(true))
            .body("totalElements", equalTo(0));
    }

    @Test
    void kardex_nonexistentProduct_returns404_WH003() {
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/warehouse/products/999999/kardex")
        .then()
            .statusCode(404)
            .body("code", equalTo("WH-003"));
    }

    /**
     * Una fecha malformada en un @QueryParam LocalDate no llega a invocar el
     * resource method: el param-converter de JAX-RS falla ANTES del match de
     * ruta y RESTEasy Reactive responde 404 vacio (mismo comportamiento
     * pre-existente que GET /quotations?dateFrom=15-07-2026 — verificado, no
     * es un bug de este endpoint). NO es COM-001 (eso es de Bean Validation,
     * @Min/@Max), por eso el 400 del test list original no aplica acá.
     */
    @Test
    void kardex_malformedDate_returns404() {
        int productId = fixtures.seedProduct("ZTEST_Kardex FechaInvalida");
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/warehouse/products/" + productId + "/kardex?dateFrom=15-07-2026")
        .then()
            .statusCode(404);
    }

    @Test
    void kardex_sizeAboveMax_returns400_COM001() {
        int productId = fixtures.seedProduct("ZTEST_Kardex SizeMax");
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/warehouse/products/" + productId + "/kardex?size=101")
        .then()
            .statusCode(400)
            .body("code", equalTo("COM-001"));
    }

    // ---------- auth / roles --------------------------------------------------------

    @Test
    void kardex_withoutToken_returns401() {
        int productId = fixtures.seedProduct("ZTEST_Kardex NoToken");

        given()
        .when()
            .get("/warehouse/products/" + productId + "/kardex")
        .then()
            .statusCode(401);
    }

    @Test
    void kardex_withAdminRole_returns200() {
        int productId = fixtures.seedProduct("ZTEST_Kardex RoleAdmin");
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/warehouse/products/" + productId + "/kardex")
        .then()
            .statusCode(200);
    }

    @Test
    void kardex_withOperationsManagerRole_returns200() {
        int productId = fixtures.seedProduct("ZTEST_Kardex RoleOM");

        given()
            .header("Authorization", "Bearer " + fabricateAccessToken("om_test", "operations_manager"))
        .when()
            .get("/warehouse/products/" + productId + "/kardex")
        .then()
            .statusCode(200);
    }

    @Test
    void kardex_withFinanceManagerRole_returns200() {
        int productId = fixtures.seedProduct("ZTEST_Kardex RoleFM");

        given()
            .header("Authorization", "Bearer " + fabricateAccessToken("fm_test", "finance_manager"))
        .when()
            .get("/warehouse/products/" + productId + "/kardex")
        .then()
            .statusCode(200);
    }

    @Test
    void kardex_withWarehouseKeeperRole_returns200() {
        int productId = fixtures.seedProduct("ZTEST_Kardex RoleWK");

        given()
            .header("Authorization", "Bearer " + fabricateAccessToken("wk_test", "warehouse_keeper"))
        .when()
            .get("/warehouse/products/" + productId + "/kardex")
        .then()
            .statusCode(200);
    }

    @Test
    void kardex_withGeneralManagerRole_returns200() {
        int productId = fixtures.seedProduct("ZTEST_Kardex RoleGM");

        given()
            .header("Authorization", "Bearer " + fabricateAccessToken("gm_test", "general_manager"))
        .when()
            .get("/warehouse/products/" + productId + "/kardex")
        .then()
            .statusCode(200);
    }

    @Test
    void kardex_withSalesRole_returns403_COM003() {
        int productId = fixtures.seedProduct("ZTEST_Kardex RoleSales");
        String token = login("lcampos", "Sales1234");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/warehouse/products/" + productId + "/kardex")
        .then()
            .statusCode(403)
            .body("code", equalTo("COM-003"));
    }

    @Test
    void kardex_withDispatcherRole_returns403_COM003() {
        int productId = fixtures.seedProduct("ZTEST_Kardex RoleDispatcher");

        given()
            .header("Authorization", "Bearer " + fabricateAccessToken("disp_test", "dispatcher"))
        .when()
            .get("/warehouse/products/" + productId + "/kardex")
        .then()
            .statusCode(403)
            .body("code", equalTo("COM-003"));
    }
}
