package com.scaramutti.tms.warehouse.withdrawal;

import com.scaramutti.tms.shared.entity.Product;
import com.scaramutti.tms.shared.entity.Worker;
import com.scaramutti.tms.shared.repository.ProductRepository;
import com.scaramutti.tms.shared.repository.UserRepository;
import com.scaramutti.tms.shared.repository.WorkerRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.HashMap;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Integration tests de GET /warehouse/withdrawals (listado con filtros). Hermético. Los
 * retiros con {@code withdrawn_at} explícito (para el filtro de fecha) y los ANULADOS se
 * siembran por SQL nativo; los ACTIVE con timestamp "ahora" se crean vía el POST.
 */
@QuarkusTest
class WarehouseWithdrawalListResourceTest {

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
                "DELETE FROM almacen.opening_balances WHERE product_id IN "
                    + "(SELECT id FROM almacen.products WHERE name LIKE ?1)")
                .setParameter(1, TEST_NAME_PREFIX + "%").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM almacen.products WHERE name LIKE ?1")
                .setParameter(1, TEST_NAME_PREFIX + "%").executeUpdate();
            // Flota de test: borrado quirúrgico por su estado propio (ZTEST_STATUS), NO por prefijo
            // de placa — tractors/trailers/escort viven en public COMPARTIDO con v1 y un prefijo de
            // 2 chars podría matchear una placa real (y reventar el cleanup por FKs de v1).
            String byTestStatus = "WHERE status_id = (SELECT id FROM public.resource_statuses WHERE name = 'ZTEST_STATUS')";
            entityManager.createNativeQuery("DELETE FROM public.tractors " + byTestStatus).executeUpdate();
            entityManager.createNativeQuery("DELETE FROM public.trailers " + byTestStatus).executeUpdate();
            entityManager.createNativeQuery("DELETE FROM public.escort_vehicles " + byTestStatus).executeUpdate();
            entityManager.createNativeQuery("DELETE FROM public.workers WHERE document_number LIKE 'ZTEST%'")
                .executeUpdate();
            // El estado de test se borra al final (su flota, la única FK, ya se fue): no queda
            // residual en la dev-DB compartida con v1 (que lo mostraría en sus dropdowns de estados).
            entityManager.createNativeQuery("DELETE FROM public.resource_statuses WHERE name = 'ZTEST_STATUS'")
                .executeUpdate();
        });
    }

    private int adminId() {
        return userRepository.findByUsername("admin").orElseThrow().id;
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

    private int dniDocumentTypeId() {
        var rows = entityManager.createNativeQuery("SELECT id FROM public.document_types WHERE code = 'DNI'").getResultList();
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

    /**
     * Estado de recurso propio y estable ({@code ZTEST_STATUS}), sembrado get-or-create:
     * {@code public.resource_statuses} es una tabla de v1 sin seed en la BD virgen de CI,
     * así que asumir una fila existente ({@code LIMIT 1}) fallaba solo en CI. La flota lo
     * referencia por FK ({@code status_id}).
     */
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

    /** Retiro sembrado por SQL con withdrawn_at y status explícitos (el POST siempre usa now()/ACTIVE). */
    private void seedWithdrawal(int productId, String quantity, int workerId, String withdrawnAtIso, boolean cancelled) {
        QuarkusTransaction.requiringNew().run(() -> {
            if (!cancelled) {
                entityManager.createNativeQuery(
                    "INSERT INTO almacen.withdrawals (product_id, quantity, withdrawn_at, received_by, registered_by, status) "
                        + "VALUES (?1, CAST(?2 AS NUMERIC), CAST(?3 AS TIMESTAMPTZ), ?4, ?5, 'ACTIVE')")
                    .setParameter(1, productId).setParameter(2, quantity).setParameter(3, withdrawnAtIso)
                    .setParameter(4, workerId).setParameter(5, adminId()).executeUpdate();
                return;
            }
            entityManager.createNativeQuery(
                "INSERT INTO almacen.withdrawals (product_id, quantity, withdrawn_at, received_by, registered_by, status, "
                    + "cancel_reason, cancelled_by, cancelled_at) "
                    + "VALUES (?1, CAST(?2 AS NUMERIC), CAST(?3 AS TIMESTAMPTZ), ?4, ?5, 'CANCELLED', 'ZTEST anulado', ?5, CURRENT_TIMESTAMP)")
                .setParameter(1, productId).setParameter(2, quantity).setParameter(3, withdrawnAtIso)
                .setParameter(4, workerId).setParameter(5, adminId()).executeUpdate();
        });
    }

    private String login(String username, String password) {
        return given().contentType(ContentType.JSON)
            .body("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}")
        .when().post("/auth/login").then().statusCode(200).extract().jsonPath().getString("token");
    }

    private void seedOpeningBalance(int productId, String quantity, String token) {
        given().header("Authorization", "Bearer " + token).contentType(ContentType.JSON)
            .body("{\"productId\":" + productId + ",\"quantity\":" + quantity + "}")
        .when().post("/warehouse/opening-balances").then().statusCode(201);
    }

    private int createWithdrawal(int productId, String quantity, int workerId, Integer tractorId, String token) {
        String unit = tractorId != null ? ",\"tractorId\":" + tractorId : "";
        return given().header("Authorization", "Bearer " + token).contentType(ContentType.JSON)
            .body("{\"productId\":" + productId + ",\"quantity\":" + quantity + ",\"receivedByWorkerId\":" + workerId + unit + "}")
        .when().post("/warehouse/withdrawals").then().statusCode(201).extract().jsonPath().getInt("id");
    }

    // ---------- GET ---------------------------------------------------------------

    @Test
    void list_multiple_returns200OrderedByWithdrawnAtDesc() {
        int productId = seedProduct("ZTEST_WDL Orden");
        int workerId = seedWorker("ZTESTW400");
        String token = login("admin", "Admin1234");
        seedOpeningBalance(productId, "100", token);
        int first = createWithdrawal(productId, "1", workerId, null, token);
        int second = createWithdrawal(productId, "1", workerId, null, token);

        given().header("Authorization", "Bearer " + token)
        .when().get("/warehouse/withdrawals?productId=" + productId + "&size=100")
        .then().statusCode(200)
            .body("content[0].id", equalTo(second))
            .body("content[1].id", equalTo(first))
            .body("content[0].product.id", equalTo(productId))
            .body("content[0].receivedBy.id", equalTo(workerId));
    }

    @Test
    void list_filterByProductId_returnsOnlyThatProduct() {
        int productA = seedProduct("ZTEST_WDL FiltroA");
        int productB = seedProduct("ZTEST_WDL FiltroB");
        int workerId = seedWorker("ZTESTW401");
        String token = login("admin", "Admin1234");
        seedOpeningBalance(productA, "10", token);
        seedOpeningBalance(productB, "10", token);
        createWithdrawal(productA, "1", workerId, null, token);
        createWithdrawal(productB, "1", workerId, null, token);

        given().header("Authorization", "Bearer " + token)
        .when().get("/warehouse/withdrawals?productId=" + productA)
        .then().statusCode(200).body("totalElements", equalTo(1)).body("content[0].product.id", equalTo(productA));
    }

    @Test
    void list_filterByTractor_embedsFleetUnit() {
        int productId = seedProduct("ZTEST_WDL Tractor");
        int workerId = seedWorker("ZTESTW402");
        int tractorId = seedTractor("ZT9001");
        String token = login("admin", "Admin1234");
        seedOpeningBalance(productId, "10", token);
        createWithdrawal(productId, "1", workerId, tractorId, token);

        given().header("Authorization", "Bearer " + token)
        .when().get("/warehouse/withdrawals?tractorId=" + tractorId)
        .then().statusCode(200).body("totalElements", equalTo(1))
            .body("content[0].fleetUnit.kind", equalTo("TRACTOR"))
            .body("content[0].fleetUnit.plate", equalTo("ZT9001"));
    }

    @Test
    void list_filterByStatusActive_excludesCancelled_butDefaultIncludesBoth() {
        int productId = seedProduct("ZTEST_WDL Estado");
        int workerId = seedWorker("ZTESTW403");
        String token = login("admin", "Admin1234");
        seedOpeningBalance(productId, "100", token);
        createWithdrawal(productId, "1", workerId, null, token);                                   // ACTIVE
        seedWithdrawal(productId, "1", workerId, "2026-07-05T10:00:00-05:00", true);               // CANCELLED

        given().header("Authorization", "Bearer " + token)
        .when().get("/warehouse/withdrawals?productId=" + productId + "&status=ACTIVE")
        .then().statusCode(200).body("totalElements", equalTo(1)).body("content[0].status", equalTo("ACTIVE"));

        given().header("Authorization", "Bearer " + token)
        .when().get("/warehouse/withdrawals?productId=" + productId)
        .then().statusCode(200).body("totalElements", equalTo(2));
    }

    @Test
    void list_filterByDateRange_inclusive() {
        int productId = seedProduct("ZTEST_WDL Fechas");
        int workerId = seedWorker("ZTESTW404");
        String token = login("admin", "Admin1234");
        seedWithdrawal(productId, "1", workerId, "2026-07-01T08:00:00-05:00", false);
        seedWithdrawal(productId, "1", workerId, "2026-07-05T10:00:00-05:00", false);
        seedWithdrawal(productId, "1", workerId, "2026-07-10T09:00:00-05:00", false);

        given().header("Authorization", "Bearer " + token)
        .when().get("/warehouse/withdrawals?productId=" + productId + "&dateFrom=2026-07-01&dateTo=2026-07-05")
        .then().statusCode(200).body("totalElements", equalTo(2));
    }

    @Test
    void list_dateTo_includesEntireDayInLima() {
        int productId = seedProduct("ZTEST_WDL DiaCompleto");
        int workerId = seedWorker("ZTESTW405");
        String token = login("admin", "Admin1234");
        // 23:30 hora Lima del dateTo: debe entrar (día completo, no medianoche UTC).
        seedWithdrawal(productId, "1", workerId, "2026-07-05T23:30:00-05:00", false);

        given().header("Authorization", "Bearer " + token)
        .when().get("/warehouse/withdrawals?productId=" + productId + "&dateFrom=2026-07-05&dateTo=2026-07-05")
        .then().statusCode(200).body("totalElements", equalTo(1));
    }

    @Test
    void list_productIdMalformed_returns404() {
        String token = login("admin", "Admin1234");
        given().header("Authorization", "Bearer " + token)
        .when().get("/warehouse/withdrawals?productId=abc").then().statusCode(404);
    }

    @Test
    void list_dateFromMalformed_returns404() {
        String token = login("admin", "Admin1234");
        given().header("Authorization", "Bearer " + token)
        .when().get("/warehouse/withdrawals?dateFrom=notadate").then().statusCode(404);
    }

    @Test
    void list_statusInvalidEnum_returns404() {
        String token = login("admin", "Admin1234");
        given().header("Authorization", "Bearer " + token)
        .when().get("/warehouse/withdrawals?status=BOGUS").then().statusCode(404);
    }

    @Test
    void list_sizeAboveMax_returns400_COM001() {
        String token = login("admin", "Admin1234");
        given().header("Authorization", "Bearer " + token)
        .when().get("/warehouse/withdrawals?size=101").then().statusCode(400).body("code", equalTo("COM-001"));
    }

    @Test
    void list_pagination_secondPageReturnsRemaining() {
        int productId = seedProduct("ZTEST_WDL Pag");
        int workerId = seedWorker("ZTESTW406");
        String token = login("admin", "Admin1234");
        seedOpeningBalance(productId, "100", token);
        createWithdrawal(productId, "1", workerId, null, token);
        createWithdrawal(productId, "1", workerId, null, token);

        given().header("Authorization", "Bearer " + token)
        .when().get("/warehouse/withdrawals?productId=" + productId + "&page=1&size=1")
        .then().statusCode(200).body("totalElements", equalTo(2)).body("content.size()", equalTo(1)).body("page", equalTo(1));
    }

    @Test
    void list_emptyResult_returns200EmptyContent() {
        int productId = seedProduct("ZTEST_WDL Vacio");
        String token = login("admin", "Admin1234");
        given().header("Authorization", "Bearer " + token)
        .when().get("/warehouse/withdrawals?productId=" + productId)
        .then().statusCode(200).body("content.size()", equalTo(0)).body("empty", equalTo(true)).body("totalElements", equalTo(0));
    }

    @Test
    void list_embedsProductReceivedByAndFleetUnit() {
        int productId = seedProduct("ZTEST_WDL Embeds");
        int workerId = seedWorker("ZTESTW407");
        int tractorId = seedTractor("ZT9002");
        String token = login("admin", "Admin1234");
        seedOpeningBalance(productId, "10", token);
        createWithdrawal(productId, "1", workerId, tractorId, token);

        given().header("Authorization", "Bearer " + token)
        .when().get("/warehouse/withdrawals?productId=" + productId)
        .then().statusCode(200)
            .body("content[0].product.id", notNullValue())
            .body("content[0].receivedBy.fullName", notNullValue())
            .body("content[0].fleetUnit.plate", equalTo("ZT9002"));
    }

    @Test
    void list_withoutToken_returns401() {
        given().when().get("/warehouse/withdrawals").then().statusCode(401);
    }

    @Test
    void list_withSalesRole_returns403_COM003() {
        String token = login("lcampos", "Sales1234");
        given().header("Authorization", "Bearer " + token)
        .when().get("/warehouse/withdrawals").then().statusCode(403).body("code", equalTo("COM-003"));
    }
}
