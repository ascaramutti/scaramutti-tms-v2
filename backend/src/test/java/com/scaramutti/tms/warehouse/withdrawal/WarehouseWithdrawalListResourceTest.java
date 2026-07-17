package com.scaramutti.tms.warehouse.withdrawal;

import com.scaramutti.tms.support.WarehouseTestData;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static com.scaramutti.tms.support.TestAuth.login;
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

    @Inject WarehouseTestData fixtures;
    @Inject EntityManager entityManager;

    @AfterEach
    void cleanupFixtures() {
        QuarkusTransaction.requiringNew().run(() -> {
            fixtures.deleteWarehouseTestData();
            fixtures.deleteTestFleet();
            fixtures.deleteTestWorkers();
        });
    }

    /** Retiro sembrado por SQL con withdrawn_at y status explícitos (el POST siempre usa now()/ACTIVE). */
    private void seedWithdrawal(int productId, String quantity, int workerId, String withdrawnAtIso, boolean cancelled) {
        QuarkusTransaction.requiringNew().run(() -> {
            if (!cancelled) {
                entityManager.createNativeQuery(
                    "INSERT INTO almacen.withdrawals (product_id, quantity, withdrawn_at, received_by, registered_by, status) "
                        + "VALUES (?1, CAST(?2 AS NUMERIC), CAST(?3 AS TIMESTAMPTZ), ?4, ?5, 'ACTIVE')")
                    .setParameter(1, productId).setParameter(2, quantity).setParameter(3, withdrawnAtIso)
                    .setParameter(4, workerId).setParameter(5, fixtures.adminId()).executeUpdate();
                return;
            }
            entityManager.createNativeQuery(
                "INSERT INTO almacen.withdrawals (product_id, quantity, withdrawn_at, received_by, registered_by, status, "
                    + "cancel_reason, cancelled_by, cancelled_at) "
                    + "VALUES (?1, CAST(?2 AS NUMERIC), CAST(?3 AS TIMESTAMPTZ), ?4, ?5, 'CANCELLED', 'ZTEST anulado', ?5, CURRENT_TIMESTAMP)")
                .setParameter(1, productId).setParameter(2, quantity).setParameter(3, withdrawnAtIso)
                .setParameter(4, workerId).setParameter(5, fixtures.adminId()).executeUpdate();
        });
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
        int productId = fixtures.seedProduct("ZTEST_WDL Orden");
        int workerId = fixtures.seedWorker("ZTESTW400");
        String token = login("admin", "Admin1234");
        fixtures.seedOpeningBalance(productId, "100", token);
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
        int productA = fixtures.seedProduct("ZTEST_WDL FiltroA");
        int productB = fixtures.seedProduct("ZTEST_WDL FiltroB");
        int workerId = fixtures.seedWorker("ZTESTW401");
        String token = login("admin", "Admin1234");
        fixtures.seedOpeningBalance(productA, "10", token);
        fixtures.seedOpeningBalance(productB, "10", token);
        createWithdrawal(productA, "1", workerId, null, token);
        createWithdrawal(productB, "1", workerId, null, token);

        given().header("Authorization", "Bearer " + token)
        .when().get("/warehouse/withdrawals?productId=" + productA)
        .then().statusCode(200).body("totalElements", equalTo(1)).body("content[0].product.id", equalTo(productA));
    }

    @Test
    void list_filterByTractor_embedsFleetUnit() {
        int productId = fixtures.seedProduct("ZTEST_WDL Tractor");
        int workerId = fixtures.seedWorker("ZTESTW402");
        int tractorId = fixtures.seedTractor("ZT9001");
        String token = login("admin", "Admin1234");
        fixtures.seedOpeningBalance(productId, "10", token);
        createWithdrawal(productId, "1", workerId, tractorId, token);

        given().header("Authorization", "Bearer " + token)
        .when().get("/warehouse/withdrawals?tractorId=" + tractorId)
        .then().statusCode(200).body("totalElements", equalTo(1))
            .body("content[0].fleetUnit.kind", equalTo("TRACTOR"))
            .body("content[0].fleetUnit.plate", equalTo("ZT9001"));
    }

    @Test
    void list_filterByTrailer_embedsFleetUnit() {
        int productId = fixtures.seedProduct("ZTEST_WDL Trailer");
        int workerId = fixtures.seedWorker("ZTESTW408");
        int trailerId = fixtures.seedTrailer("ZT9003");
        String token = login("admin", "Admin1234");
        fixtures.seedOpeningBalance(productId, "10", token);
        given().header("Authorization", "Bearer " + token).contentType(ContentType.JSON)
            .body("{\"productId\":" + productId + ",\"quantity\":1,\"receivedByWorkerId\":" + workerId + ",\"trailerId\":" + trailerId + "}")
        .when().post("/warehouse/withdrawals").then().statusCode(201);

        given().header("Authorization", "Bearer " + token)
        .when().get("/warehouse/withdrawals?trailerId=" + trailerId)
        .then().statusCode(200).body("totalElements", equalTo(1))
            .body("content[0].fleetUnit.kind", equalTo("TRAILER"))
            .body("content[0].fleetUnit.plate", equalTo("ZT9003"));
    }

    @Test
    void list_filterByReceivedByWorkerId_returnsOnlyThatWorker() {
        int productId = fixtures.seedProduct("ZTEST_WDL Worker");
        int workerA = fixtures.seedWorker("ZTESTW409");
        int workerB = fixtures.seedWorker("ZTESTW410");
        String token = login("admin", "Admin1234");
        fixtures.seedOpeningBalance(productId, "10", token);
        createWithdrawal(productId, "1", workerA, null, token);
        createWithdrawal(productId, "1", workerB, null, token);

        given().header("Authorization", "Bearer " + token)
        .when().get("/warehouse/withdrawals?receivedByWorkerId=" + workerA)
        .then().statusCode(200).body("totalElements", equalTo(1))
            .body("content[0].receivedBy.id", equalTo(workerA));
    }

    @Test
    void list_filterByStatusActive_excludesCancelled_butDefaultIncludesBoth() {
        int productId = fixtures.seedProduct("ZTEST_WDL Estado");
        int workerId = fixtures.seedWorker("ZTESTW403");
        String token = login("admin", "Admin1234");
        fixtures.seedOpeningBalance(productId, "100", token);
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
        int productId = fixtures.seedProduct("ZTEST_WDL Fechas");
        int workerId = fixtures.seedWorker("ZTESTW404");
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
        int productId = fixtures.seedProduct("ZTEST_WDL DiaCompleto");
        int workerId = fixtures.seedWorker("ZTESTW405");
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
        int productId = fixtures.seedProduct("ZTEST_WDL Pag");
        int workerId = fixtures.seedWorker("ZTESTW406");
        String token = login("admin", "Admin1234");
        fixtures.seedOpeningBalance(productId, "100", token);
        createWithdrawal(productId, "1", workerId, null, token);
        createWithdrawal(productId, "1", workerId, null, token);

        given().header("Authorization", "Bearer " + token)
        .when().get("/warehouse/withdrawals?productId=" + productId + "&page=1&size=1")
        .then().statusCode(200).body("totalElements", equalTo(2)).body("content.size()", equalTo(1)).body("page", equalTo(1));
    }

    @Test
    void list_emptyResult_returns200EmptyContent() {
        int productId = fixtures.seedProduct("ZTEST_WDL Vacio");
        String token = login("admin", "Admin1234");
        given().header("Authorization", "Bearer " + token)
        .when().get("/warehouse/withdrawals?productId=" + productId)
        .then().statusCode(200).body("content.size()", equalTo(0)).body("empty", equalTo(true)).body("totalElements", equalTo(0));
    }

    @Test
    void list_embedsProductReceivedByAndFleetUnit() {
        int productId = fixtures.seedProduct("ZTEST_WDL Embeds");
        int workerId = fixtures.seedWorker("ZTESTW407");
        int tractorId = fixtures.seedTractor("ZT9002");
        String token = login("admin", "Admin1234");
        fixtures.seedOpeningBalance(productId, "10", token);
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
