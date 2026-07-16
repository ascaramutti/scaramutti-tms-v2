package com.scaramutti.tms.warehouse.reports;

import com.scaramutti.tms.shared.entity.Product;
import com.scaramutti.tms.shared.entity.User;
import com.scaramutti.tms.shared.entity.Worker;
import com.scaramutti.tms.shared.repository.ProductRepository;
import com.scaramutti.tms.shared.repository.UserRepository;
import com.scaramutti.tms.shared.repository.WorkerRepository;
import com.scaramutti.tms.shared.util.DateUtils;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;
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
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

/**
 * Integration tests de GET /warehouse/reports (los 4 cortes). Hermetico (prefijo
 * ZTEST_, cleanup en orden FK). Usa FECHAS FIJAS (junio 2026): el reporte no
 * depende del "mes en curso", asi que sembrar withdrawn_at/invoice_date fijos lo
 * hace deterministico. withdrawn_at y las facturas se siembran por SQL nativo
 * (el POST no acepta withdrawn_at/created_at ni permite backdatear invoice_date
 * de forma controlada para varios casos), siguiendo el patron de los otros tests.
 */
@QuarkusTest
class WarehouseReportResourceTest {

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
            entityManager.createNativeQuery("DELETE FROM almacen.products WHERE name LIKE ?1")
                .setParameter(1, TEST_NAME_PREFIX + "%").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM almacen.suppliers WHERE name LIKE ?1")
                .setParameter(1, TEST_NAME_PREFIX + "%").executeUpdate();
            String byTestStatus = "WHERE status_id = (SELECT id FROM public.resource_statuses WHERE name = 'ZTEST_STATUS')";
            entityManager.createNativeQuery("DELETE FROM public.tractors " + byTestStatus).executeUpdate();
            entityManager.createNativeQuery("DELETE FROM public.trailers " + byTestStatus).executeUpdate();
            entityManager.createNativeQuery("DELETE FROM public.escort_vehicles " + byTestStatus).executeUpdate();
            entityManager.createNativeQuery("DELETE FROM public.workers WHERE document_number LIKE 'ZTEST%'")
                .executeUpdate();
            entityManager.createNativeQuery("DELETE FROM public.resource_statuses WHERE name = 'ZTEST_STATUS'")
                .executeUpdate();
        });
    }

    // ---------- fixtures --------------------------------------------------------

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
        return QuarkusTransaction.requiringNew().call(() -> ((Number) entityManager.createNativeQuery(
            "INSERT INTO almacen.suppliers (name, is_active, created_at) VALUES (?1, true, now()) RETURNING id")
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

    private int resourceStatusId() {
        var rows = entityManager.createNativeQuery(
            "SELECT id FROM public.resource_statuses WHERE name = 'ZTEST_STATUS'").getResultList();
        if (!rows.isEmpty()) {
            return ((Number) rows.get(0)).intValue();
        }
        entityManager.createNativeQuery(
            "INSERT INTO public.resource_statuses (name, is_active) VALUES ('ZTEST_STATUS', true)")
            .executeUpdate();
        return ((Number) entityManager.createNativeQuery(
            "SELECT id FROM public.resource_statuses WHERE name = 'ZTEST_STATUS'")
            .getSingleResult()).intValue();
    }

    private int seedTractor(String plate) {
        return QuarkusTransaction.requiringNew().call(() -> ((Number) entityManager.createNativeQuery(
            "INSERT INTO public.tractors (plate, status_id, is_active) VALUES (?1, ?2, true) RETURNING id")
            .setParameter(1, plate).setParameter(2, resourceStatusId()).getSingleResult()).intValue());
    }

    private int seedTrailer(String plate) {
        return QuarkusTransaction.requiringNew().call(() -> ((Number) entityManager.createNativeQuery(
            "INSERT INTO public.trailers (plate, type, status_id, is_active) VALUES (?1, 'ZTEST', ?2, true) RETURNING id")
            .setParameter(1, plate).setParameter(2, resourceStatusId()).getSingleResult()).intValue());
    }

    private int seedEscortVehicle(String plate) {
        return QuarkusTransaction.requiringNew().call(() -> ((Number) entityManager.createNativeQuery(
            "INSERT INTO public.escort_vehicles (plate, status_id, is_active) VALUES (?1, ?2, true) RETURNING id")
            .setParameter(1, plate).setParameter(2, resourceStatusId()).getSingleResult()).intValue());
    }

    /**
     * Retiro ACTIVE por SQL nativo con la unidad de flota en la columna indicada
     * ({@code trailer_id}/{@code escort_vehicle_id}). {@code unitColumn} es una
     * constante controlada del test (no entrada de usuario).
     */
    private void seedWithdrawalWithUnit(int productId, String quantity, int workerId,
                                        String unitColumn, int unitId, OffsetDateTime withdrawnAt) {
        QuarkusTransaction.requiringNew().run(() -> entityManager.createNativeQuery(
            "INSERT INTO almacen.withdrawals "
                + "(product_id, quantity, withdrawn_at, received_by, " + unitColumn + ", registered_by, updated_at, status) "
                + "VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?3, 'ACTIVE')")
            .setParameter(1, productId)
            .setParameter(2, new BigDecimal(quantity))
            .setParameter(3, withdrawnAt)
            .setParameter(4, workerId)
            .setParameter(5, unitId)
            .setParameter(6, adminId())
            .executeUpdate());
    }

    /** Factura ACTIVE/CANCELLED con UN item, por SQL nativo: fija invoice_date, moneda y precio. */
    private int seedInvoiceWithItem(int supplierId, String invoiceNumber, LocalDate invoiceDate,
                                    String currencyCode, String status, int productId,
                                    String quantity, String unitPrice) {
        boolean cancelled = "CANCELLED".equals(status);
        return QuarkusTransaction.requiringNew().call(() -> {
            int invoiceId = ((Number) entityManager.createNativeQuery(
                "INSERT INTO almacen.purchase_invoices "
                    + "(supplier_id, invoice_number, invoice_date, currency_id, registered_by, created_at, updated_at, "
                    + "status, cancel_reason, cancelled_by, cancelled_at) "
                    + "VALUES (?1, ?2, ?3, ?4, ?5, now(), now(), ?6, ?7, ?8, ?9) RETURNING id")
                .setParameter(1, supplierId)
                .setParameter(2, invoiceNumber)
                .setParameter(3, invoiceDate)
                .setParameter(4, currencyId(currencyCode))
                .setParameter(5, adminId())
                .setParameter(6, status)
                .setParameter(7, cancelled ? "ZTEST anulada" : null)
                .setParameter(8, cancelled ? adminId() : null)
                .setParameter(9, cancelled ? OffsetDateTime.now() : null)
                .getSingleResult()).intValue();
            entityManager.createNativeQuery(
                "INSERT INTO almacen.purchase_invoice_items (invoice_id, product_id, quantity, unit_price, created_at) "
                    + "VALUES (?1, ?2, ?3, ?4, now())")
                .setParameter(1, invoiceId)
                .setParameter(2, productId)
                .setParameter(3, new BigDecimal(quantity))
                .setParameter(4, new BigDecimal(unitPrice))
                .executeUpdate();
            return invoiceId;
        });
    }

    private OffsetDateTime limaNoon(LocalDate date) {
        return date.atTime(12, 0).atZone(DateUtils.LIMA).toOffsetDateTime();
    }

    /** Retiro ACTIVE/CANCELLED por SQL nativo: fija withdrawn_at, unidad (tractor) y status. */
    private void seedWithdrawal(int productId, String quantity, int workerId, Integer tractorId,
                                OffsetDateTime withdrawnAt, String status) {
        boolean cancelled = "CANCELLED".equals(status);
        QuarkusTransaction.requiringNew().run(() -> entityManager.createNativeQuery(
            "INSERT INTO almacen.withdrawals "
                + "(product_id, quantity, withdrawn_at, received_by, tractor_id, registered_by, updated_at, "
                + "status, cancel_reason, cancelled_by, cancelled_at) "
                + "VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?3, ?7, ?8, ?9, ?10)")
            .setParameter(1, productId)
            .setParameter(2, new BigDecimal(quantity))
            .setParameter(3, withdrawnAt)
            .setParameter(4, workerId)
            .setParameter(5, tractorId)
            .setParameter(6, adminId())
            .setParameter(7, status)
            .setParameter(8, cancelled ? "ZTEST anulado" : null)
            .setParameter(9, cancelled ? adminId() : null)
            .setParameter(10, cancelled ? withdrawnAt : null)
            .executeUpdate());
    }

    private String login(String username, String password) {
        return given().contentType(ContentType.JSON)
            .body("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}")
        .when().post("/auth/login").then().statusCode(200).extract().jsonPath().getString("token");
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

    private ValidatableResponse report(String token, String cut, String dateFrom, String dateTo) {
        return given().header("Authorization", "Bearer " + token)
            .queryParam("cut", cut).queryParam("dateFrom", dateFrom).queryParam("dateTo", dateTo)
        .when().get("/warehouse/reports").then();
    }

    // ---------- validacion / roles -----------------------------------------------

    @Test
    void getReport_dateFromAfterDateTo_returns400_COM001() {
        String token = login("admin", "Admin1234");
        report(token, "BY_PRODUCT", "2026-06-30", "2026-06-01")
            .statusCode(400).body("code", equalTo("COM-001"));
    }

    @Test
    void getReport_invalidCutEnum_returns404() {
        String token = login("admin", "Admin1234");
        report(token, "BY_NOTHING", "2026-06-01", "2026-06-30").statusCode(404);
    }

    @Test
    void getReport_missingCut_returns400() {
        String token = login("admin", "Admin1234");
        given().header("Authorization", "Bearer " + token)
            .queryParam("dateFrom", "2026-06-01").queryParam("dateTo", "2026-06-30")
        .when().get("/warehouse/reports").then().statusCode(400);
    }

    @Test
    void getReport_withoutToken_returns401() {
        report("", "BY_PRODUCT", "2026-06-01", "2026-06-30").statusCode(401);
    }

    @Test
    void getReport_withSalesRole_returns403_COM003() {
        String token = login("lcampos", "Sales1234");
        report(token, "BY_PRODUCT", "2026-06-01", "2026-06-30")
            .statusCode(403).body("code", equalTo("COM-003"));
    }

    @Test
    void getReport_withDispatcherRole_returns403_COM003() {
        report(fabricateAccessToken("disp_test", "dispatcher"), "BY_PRODUCT", "2026-06-01", "2026-06-30")
            .statusCode(403).body("code", equalTo("COM-003"));
    }

    @Test
    void getReport_withWarehouseKeeperRole_returns200() {
        report(fabricateTokenForUser(adminId(), "wk_test", "warehouse_keeper"),
            "BY_PRODUCT", "2026-06-01", "2026-06-30").statusCode(200);
    }

    @Test
    void getReport_noMovementsInRange_returnsEmptyRowsAndZeroTotals() {
        String token = login("admin", "Admin1234");
        report(token, "BY_UNIT", "2010-01-01", "2010-01-31")
            .statusCode(200)
            .body("rows", empty())
            .body("totalPEN", equalTo(0))
            .body("totalUSD", equalTo(0))
            .body("totalCount", equalTo(0));
    }

    // ---------- BY_UNIT ----------------------------------------------------------

    @Test
    void getReport_byUnit_groupsByFleetUnitAndValuesAtLastPrice() {
        String token = login("admin", "Admin1234");
        int productId = seedProduct("ZTEST_RP Unit");
        int supplierId = seedSupplier("ZTEST_RP ProvUnit");
        int workerId = seedWorker("ZTESTRP01");
        int tractorId = seedTractor("ZR0001");
        seedInvoiceWithItem(supplierId, "ZTEST-RP-U1", LocalDate.of(2026, 5, 1), "PEN", "ACTIVE", productId, "1", "25.00");
        seedWithdrawal(productId, "3", workerId, tractorId, limaNoon(LocalDate.of(2026, 6, 10)), "ACTIVE");
        seedWithdrawal(productId, "2", workerId, tractorId, limaNoon(LocalDate.of(2026, 6, 12)), "ACTIVE");

        report(token, "BY_UNIT", "2026-06-01", "2026-06-30")
            .statusCode(200)
            .body("rows.find { it.label == 'Tracto ZR0001' }.count", equalTo(2))
            .body("rows.find { it.label == 'Tracto ZR0001' }.amountPEN", equalTo(125.00f))
            .body("rows.find { it.label == 'Tracto ZR0001' }.amountUSD", equalTo(0));
    }

    @Test
    void getReport_byUnit_trailerAndEscortLabelledByKind() {
        String token = login("admin", "Admin1234");
        int productId = seedProduct("ZTEST_RP UnitKinds");
        int workerId = seedWorker("ZTESTRP13");
        int trailerId = seedTrailer("ZR0010");
        int escortId = seedEscortVehicle("ZR0011");
        seedWithdrawalWithUnit(productId, "1", workerId, "trailer_id", trailerId, limaNoon(LocalDate.of(2026, 6, 10)));
        seedWithdrawalWithUnit(productId, "1", workerId, "escort_vehicle_id", escortId, limaNoon(LocalDate.of(2026, 6, 11)));

        report(token, "BY_UNIT", "2026-06-01", "2026-06-30")
            .statusCode(200)
            .body("rows.find { it.label == 'Carreta ZR0010' }.count", equalTo(1))
            .body("rows.find { it.label == 'Escolta ZR0011' }.count", equalTo(1));
    }

    @Test
    void getReport_byUnit_withdrawalWithoutFleetUnit_groupsAsSinUnidadAsignada() {
        String token = login("admin", "Admin1234");
        int productId = seedProduct("ZTEST_RP NoUnit");
        int workerId = seedWorker("ZTESTRP02");
        seedWithdrawal(productId, "1", workerId, null, limaNoon(LocalDate.of(2026, 6, 10)), "ACTIVE");

        report(token, "BY_UNIT", "2026-06-01", "2026-06-30")
            .statusCode(200)
            .body("rows.find { it.label == 'Sin unidad asignada' }.count", equalTo(1));
    }

    // ---------- BY_PERIOD --------------------------------------------------------

    @Test
    void getReport_byPeriod_groupsByIsoWeekMondayAndOrdersChronologically() {
        String token = login("admin", "Admin1234");
        int productId = seedProduct("ZTEST_RP Period");
        int workerId = seedWorker("ZTESTRP03");
        // 3 semanas distintas; la ultima con el monto mayor para confirmar orden cronologico (no por monto)
        int supplierId = seedSupplier("ZTEST_RP ProvPeriod");
        seedInvoiceWithItem(supplierId, "ZTEST-RP-P1", LocalDate.of(2026, 5, 1), "PEN", "ACTIVE", productId, "1", "10.00");
        seedWithdrawal(productId, "1", workerId, null, limaNoon(LocalDate.of(2026, 6, 9)), "ACTIVE");   // semana lunes 08
        seedWithdrawal(productId, "1", workerId, null, limaNoon(LocalDate.of(2026, 6, 16)), "ACTIVE");  // semana lunes 15
        seedWithdrawal(productId, "9", workerId, null, limaNoon(LocalDate.of(2026, 6, 23)), "ACTIVE");  // semana lunes 22 (mayor monto)

        report(token, "BY_PERIOD", "2026-06-08", "2026-06-28")
            .statusCode(200)
            .body("rows", hasSize(3))
            .body("rows[0].detail", equalTo("2026-06-08"))
            .body("rows[1].detail", equalTo("2026-06-15"))
            .body("rows[2].detail", equalTo("2026-06-22"))
            .body("rows[0].label", equalTo("Semana del 08/06"));
    }

    @Test
    void getReport_byPeriod_sameWeekDifferentDays_mergedIntoOneRow() {
        String token = login("admin", "Admin1234");
        int productId = seedProduct("ZTEST_RP SameWeek");
        int workerId = seedWorker("ZTESTRP04");
        seedWithdrawal(productId, "1", workerId, null, limaNoon(LocalDate.of(2026, 6, 9)), "ACTIVE");   // martes
        seedWithdrawal(productId, "1", workerId, null, limaNoon(LocalDate.of(2026, 6, 12)), "ACTIVE");  // viernes, misma semana

        report(token, "BY_PERIOD", "2026-06-08", "2026-06-14")
            .statusCode(200)
            .body("rows", hasSize(1))
            .body("rows[0].detail", equalTo("2026-06-08"))
            .body("rows[0].count", equalTo(2));
    }

    // ---------- BY_PRODUCT -------------------------------------------------------

    @Test
    void getReport_byProduct_countIsSumOfQuantity() {
        String token = login("admin", "Admin1234");
        int productId = seedProduct("ZTEST_RP ByProdCount");
        int workerId = seedWorker("ZTESTRP05");
        seedWithdrawal(productId, "2", workerId, null, limaNoon(LocalDate.of(2026, 6, 10)), "ACTIVE");
        seedWithdrawal(productId, "3", workerId, null, limaNoon(LocalDate.of(2026, 6, 11)), "ACTIVE");
        seedWithdrawal(productId, "5", workerId, null, limaNoon(LocalDate.of(2026, 6, 12)), "ACTIVE");

        report(token, "BY_PRODUCT", "2026-06-01", "2026-06-30")
            .statusCode(200)
            .body("rows.find { it.label == 'ZTEST_RP ByProdCount' }.count", equalTo(10.00f));  // 2+3+5, no 3
    }

    @Test
    void getReport_byProduct_withoutPurchasePrice_countsButZeroAmount() {
        String token = login("admin", "Admin1234");
        int productId = seedProduct("ZTEST_RP NoPrice");
        int workerId = seedWorker("ZTESTRP06");
        seedWithdrawal(productId, "4", workerId, null, limaNoon(LocalDate.of(2026, 6, 10)), "ACTIVE");

        report(token, "BY_PRODUCT", "2026-06-01", "2026-06-30")
            .statusCode(200)
            .body("rows.find { it.label == 'ZTEST_RP NoPrice' }.count", equalTo(4.00f))
            .body("rows.find { it.label == 'ZTEST_RP NoPrice' }.amountPEN", equalTo(0))
            .body("rows.find { it.label == 'ZTEST_RP NoPrice' }.amountUSD", equalTo(0));
    }

    @Test
    void getReport_byProduct_usesLatestActivePurchasePrice() {
        String token = login("admin", "Admin1234");
        int productId = seedProduct("ZTEST_RP Latest");
        int supplierId = seedSupplier("ZTEST_RP ProvLatest");
        int workerId = seedWorker("ZTESTRP07");
        seedInvoiceWithItem(supplierId, "ZTEST-RP-L1", LocalDate.of(2026, 5, 1), "PEN", "ACTIVE", productId, "1", "10.00");
        seedInvoiceWithItem(supplierId, "ZTEST-RP-L2", LocalDate.of(2026, 5, 20), "USD", "ACTIVE", productId, "1", "8.00");
        seedWithdrawal(productId, "2", workerId, null, limaNoon(LocalDate.of(2026, 6, 10)), "ACTIVE");

        report(token, "BY_PRODUCT", "2026-06-01", "2026-06-30")
            .statusCode(200)
            .body("rows.find { it.label == 'ZTEST_RP Latest' }.amountUSD", equalTo(16.00f))  // 2*8.00 (USD mas reciente)
            .body("rows.find { it.label == 'ZTEST_RP Latest' }.amountPEN", equalTo(0));
    }

    @Test
    void getReport_byProduct_ignoresCancelledInvoiceForLatestPrice() {
        String token = login("admin", "Admin1234");
        int productId = seedProduct("ZTEST_RP CancelPrice");
        int supplierId = seedSupplier("ZTEST_RP ProvCancelPrice");
        int workerId = seedWorker("ZTESTRP08");
        seedInvoiceWithItem(supplierId, "ZTEST-RP-C1", LocalDate.of(2026, 5, 20), "USD", "ACTIVE", productId, "1", "8.00");
        seedInvoiceWithItem(supplierId, "ZTEST-RP-C2", LocalDate.of(2026, 5, 30), "USD", "CANCELLED", productId, "1", "20.00");
        seedWithdrawal(productId, "2", workerId, null, limaNoon(LocalDate.of(2026, 6, 10)), "ACTIVE");

        report(token, "BY_PRODUCT", "2026-06-01", "2026-06-30")
            .statusCode(200)
            .body("rows.find { it.label == 'ZTEST_RP CancelPrice' }.amountUSD", equalTo(16.00f));  // 2*8.00, no 20.00
    }

    @Test
    void getReport_byProduct_ordersByTotalAmountDescending() {
        String token = login("admin", "Admin1234");
        int workerId = seedWorker("ZTESTRP09");
        int supplierId = seedSupplier("ZTEST_RP ProvOrder");
        int low = seedProduct("ZTEST_RP zLow");
        int high = seedProduct("ZTEST_RP zHigh");
        seedInvoiceWithItem(supplierId, "ZTEST-RP-O1", LocalDate.of(2026, 5, 1), "PEN", "ACTIVE", low, "1", "10.00");
        seedInvoiceWithItem(supplierId, "ZTEST-RP-O2", LocalDate.of(2026, 5, 1), "PEN", "ACTIVE", high, "1", "100.00");
        seedWithdrawal(low, "1", workerId, null, limaNoon(LocalDate.of(2026, 6, 10)), "ACTIVE");
        seedWithdrawal(high, "1", workerId, null, limaNoon(LocalDate.of(2026, 6, 10)), "ACTIVE");

        // Ambas filas ZTEST en el mismo rango; la de mayor monto (high) debe venir antes que la de menor (low).
        report(token, "BY_PRODUCT", "2026-06-01", "2026-06-30")
            .statusCode(200)
            .body("rows.findAll { it.label in ['ZTEST_RP zHigh', 'ZTEST_RP zLow'] }.label",
                equalTo(java.util.List.of("ZTEST_RP zHigh", "ZTEST_RP zLow")));
    }

    // ---------- bi-moneda --------------------------------------------------------

    @Test
    void getReport_biCurrency_amountsAccumulateSeparately() {
        String token = login("admin", "Admin1234");
        int workerId = seedWorker("ZTESTRP10");
        int tractorId = seedTractor("ZR0002");
        int supplierId = seedSupplier("ZTEST_RP ProvBi");
        int pen = seedProduct("ZTEST_RP BiPen");
        int usd = seedProduct("ZTEST_RP BiUsd");
        seedInvoiceWithItem(supplierId, "ZTEST-RP-B1", LocalDate.of(2026, 5, 1), "PEN", "ACTIVE", pen, "1", "15.00");
        seedInvoiceWithItem(supplierId, "ZTEST-RP-B2", LocalDate.of(2026, 5, 1), "USD", "ACTIVE", usd, "1", "6.50");
        seedWithdrawal(pen, "2", workerId, tractorId, limaNoon(LocalDate.of(2026, 6, 10)), "ACTIVE");
        seedWithdrawal(usd, "4", workerId, tractorId, limaNoon(LocalDate.of(2026, 6, 11)), "ACTIVE");

        report(token, "BY_UNIT", "2026-06-01", "2026-06-30")
            .statusCode(200)
            .body("rows.find { it.label == 'Tracto ZR0002' }.amountPEN", equalTo(30.00f))   // 2*15.00
            .body("rows.find { it.label == 'Tracto ZR0002' }.amountUSD", equalTo(26.00f))   // 4*6.50
            .body("rows.find { it.label == 'Tracto ZR0002' }.count", equalTo(2));
    }

    // ---------- BY_SUPPLIER ------------------------------------------------------

    @Test
    void getReport_bySupplier_countIsInvoicesAmountByOwnCurrency() {
        String token = login("admin", "Admin1234");
        int productId = seedProduct("ZTEST_RP SupProd");
        int supplierId = seedSupplier("ZTEST_RP ProvSup");
        // factura 1 PEN 2 items => 25.00 ; factura 2 USD 1 item => 12.00
        int inv1 = seedInvoiceWithItem(supplierId, "ZTEST-RP-S1", LocalDate.of(2026, 6, 5), "PEN", "ACTIVE", productId, "2", "10.00");
        QuarkusTransaction.requiringNew().run(() -> entityManager.createNativeQuery(
            "INSERT INTO almacen.purchase_invoice_items (invoice_id, product_id, quantity, unit_price, created_at) "
                + "VALUES (?1, ?2, 1, 5.00, now())")
            .setParameter(1, inv1).setParameter(2, productId).executeUpdate());
        seedInvoiceWithItem(supplierId, "ZTEST-RP-S2", LocalDate.of(2026, 6, 6), "USD", "ACTIVE", productId, "3", "4.00");

        report(token, "BY_SUPPLIER", "2026-06-01", "2026-06-30")
            .statusCode(200)
            .body("rows.find { it.label == 'ZTEST_RP ProvSup' }.count", equalTo(2))
            .body("rows.find { it.label == 'ZTEST_RP ProvSup' }.amountPEN", equalTo(25.00f))
            .body("rows.find { it.label == 'ZTEST_RP ProvSup' }.amountUSD", equalTo(12.00f));
    }

    @Test
    void getReport_bySupplier_cancelledInvoiceExcluded() {
        String token = login("admin", "Admin1234");
        int productId = seedProduct("ZTEST_RP SupCancelProd");
        int supplierId = seedSupplier("ZTEST_RP ProvSupCancel");
        seedInvoiceWithItem(supplierId, "ZTEST-RP-SC1", LocalDate.of(2026, 6, 5), "PEN", "CANCELLED", productId, "2", "10.00");

        report(token, "BY_SUPPLIER", "2026-06-01", "2026-06-30")
            .statusCode(200)
            .body("rows.find { it.label == 'ZTEST_RP ProvSupCancel' }", equalTo(null));
    }

    @Test
    void getReport_bySupplier_invoiceDateRangeInclusiveBothEnds() {
        String token = login("admin", "Admin1234");
        int productId = seedProduct("ZTEST_RP SupRange");
        int supplierId = seedSupplier("ZTEST_RP ProvSupRange");
        seedInvoiceWithItem(supplierId, "ZTEST-RP-R1", LocalDate.of(2026, 6, 1), "PEN", "ACTIVE", productId, "1", "10.00");   // borde inicio
        seedInvoiceWithItem(supplierId, "ZTEST-RP-R2", LocalDate.of(2026, 6, 30), "PEN", "ACTIVE", productId, "1", "10.00");  // borde fin
        seedInvoiceWithItem(supplierId, "ZTEST-RP-R3", LocalDate.of(2026, 7, 1), "PEN", "ACTIVE", productId, "1", "10.00");   // fuera

        report(token, "BY_SUPPLIER", "2026-06-01", "2026-06-30")
            .statusCode(200)
            .body("rows.find { it.label == 'ZTEST_RP ProvSupRange' }.count", equalTo(2));  // solo los 2 dentro
    }

    // ---------- rango de retiros: bordes inclusivos ------------------------------

    @Test
    void getReport_withdrawalRange_bothEndsInclusiveLima() {
        String token = login("admin", "Admin1234");
        int productId = seedProduct("ZTEST_RP Range");
        int workerId = seedWorker("ZTESTRP11");
        seedWithdrawal(productId, "1", workerId, null,
            LocalDate.of(2026, 6, 10).atStartOfDay(DateUtils.LIMA).toOffsetDateTime(), "ACTIVE");        // 00:00 borde inicio
        seedWithdrawal(productId, "1", workerId, null,
            LocalDate.of(2026, 6, 20).atTime(23, 59).atZone(DateUtils.LIMA).toOffsetDateTime(), "ACTIVE"); // 23:59 borde fin
        seedWithdrawal(productId, "1", workerId, null,
            LocalDate.of(2026, 6, 21).atStartOfDay(DateUtils.LIMA).toOffsetDateTime(), "ACTIVE");        // dia siguiente: fuera

        report(token, "BY_PRODUCT", "2026-06-10", "2026-06-20")
            .statusCode(200)
            .body("rows.find { it.label == 'ZTEST_RP Range' }.count", equalTo(2.00f));  // 1+1, el tercero fuera
    }

    @Test
    void getReport_cancelledWithdrawalExcluded() {
        String token = login("admin", "Admin1234");
        int productId = seedProduct("ZTEST_RP WdCancel");
        int workerId = seedWorker("ZTESTRP12");
        seedWithdrawal(productId, "5", workerId, null, limaNoon(LocalDate.of(2026, 6, 10)), "CANCELLED");

        report(token, "BY_PRODUCT", "2026-06-01", "2026-06-30")
            .statusCode(200)
            .body("rows.find { it.label == 'ZTEST_RP WdCancel' }", equalTo(null));
    }
}
