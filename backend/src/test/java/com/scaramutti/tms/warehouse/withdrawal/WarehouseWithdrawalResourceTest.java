package com.scaramutti.tms.warehouse.withdrawal;

import com.scaramutti.tms.support.WarehouseTestData;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.scaramutti.tms.support.TestAuth.fabricateAccessToken;
import static com.scaramutti.tms.support.TestAuth.fabricateTokenForUser;
import static com.scaramutti.tms.support.TestAuth.login;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * Integration tests de POST /warehouse/withdrawals. Hermético (prefijo ZTEST_, cleanup en
 * orden FK). El stock se da con una apertura de inventario (vía su endpoint). Worker y
 * unidades de flota se siembran por SQL nativo (no hay endpoint de alta para ellos).
 */
@QuarkusTest
class WarehouseWithdrawalResourceTest {

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

    // ---------- POST: happy path --------------------------------------------------

    @Test
    void create_withoutFleetUnit_returns201WithEtagAndStockDecreased() {
        int productId = fixtures.seedProduct("ZTEST_WD Happy");
        int workerId = fixtures.seedWorker("ZTESTW300");
        String token = login("admin", "Admin1234");
        fixtures.seedOpeningBalance(productId, "10", token);

        given().header("Authorization", "Bearer " + token).contentType(ContentType.JSON)
            .body("{\"productId\":" + productId + ",\"quantity\":3,\"receivedByWorkerId\":" + workerId
                + ",\"observations\":\"ZTEST cambio de aceite\"}")
        .when().post("/warehouse/withdrawals")
        .then().statusCode(201)
            .header("ETag", notNullValue())
            .body("id", notNullValue())
            .body("product.id", equalTo(productId))
            .body("quantity", equalTo(3))
            .body("withdrawnAt", notNullValue())
            .body("receivedBy.id", equalTo(workerId))
            .body("fleetUnit", nullValue())
            .body("observations", equalTo("ZTEST cambio de aceite"))
            .body("status", equalTo("ACTIVE"))
            .body("registeredBy.username", equalTo("admin"));

        fixtures.assertStock(productId, "7", token);   // 10 - 3
    }

    @Test
    void create_withTractor_returns201WithFleetUnitEmbedded() {
        int productId = fixtures.seedProduct("ZTEST_WD Tractor");
        int workerId = fixtures.seedWorker("ZTESTW301");
        int tractorId = fixtures.seedTractor("ZT0001", true);
        String token = login("admin", "Admin1234");
        fixtures.seedOpeningBalance(productId, "10", token);

        given().header("Authorization", "Bearer " + token).contentType(ContentType.JSON)
            .body("{\"productId\":" + productId + ",\"quantity\":2,\"receivedByWorkerId\":" + workerId
                + ",\"tractorId\":" + tractorId + "}")
        .when().post("/warehouse/withdrawals")
        .then().statusCode(201)
            .body("fleetUnit.kind", equalTo("TRACTOR"))
            .body("fleetUnit.id", equalTo(tractorId))
            .body("fleetUnit.plate", equalTo("ZT0001"));
    }

    @Test
    void create_withEscortVehicle_returns201WithEscortKind() {
        int productId = fixtures.seedProduct("ZTEST_WD Escort");
        int workerId = fixtures.seedWorker("ZTESTW302");
        int escortId = fixtures.seedEscortVehicle("ZT0002", true);
        String token = login("admin", "Admin1234");
        fixtures.seedOpeningBalance(productId, "10", token);

        given().header("Authorization", "Bearer " + token).contentType(ContentType.JSON)
            .body("{\"productId\":" + productId + ",\"quantity\":1,\"receivedByWorkerId\":" + workerId
                + ",\"escortVehicleId\":" + escortId + "}")
        .when().post("/warehouse/withdrawals")
        .then().statusCode(201).body("fleetUnit.kind", equalTo("ESCORT"));
    }

    @Test
    void create_withTrailer_returns201WithTrailerKind() {
        int productId = fixtures.seedProduct("ZTEST_WD Trailer");
        int workerId = fixtures.seedWorker("ZTESTW315");
        int trailerId = fixtures.seedTrailer("ZT0009", true);
        String token = login("admin", "Admin1234");
        fixtures.seedOpeningBalance(productId, "10", token);

        given().header("Authorization", "Bearer " + token).contentType(ContentType.JSON)
            .body("{\"productId\":" + productId + ",\"quantity\":2,\"receivedByWorkerId\":" + workerId
                + ",\"trailerId\":" + trailerId + "}")
        .when().post("/warehouse/withdrawals")
        .then().statusCode(201)
            .body("fleetUnit.kind", equalTo("TRAILER"))
            .body("fleetUnit.id", equalTo(trailerId))
            .body("fleetUnit.plate", equalTo("ZT0009"));
    }

    @Test
    void create_quantityExactlyEqualsStock_returns201StockToZero() {
        int productId = fixtures.seedProduct("ZTEST_WD Exact");
        int workerId = fixtures.seedWorker("ZTESTW303");
        String token = login("admin", "Admin1234");
        fixtures.seedOpeningBalance(productId, "5", token);

        given().header("Authorization", "Bearer " + token).contentType(ContentType.JSON)
            .body("{\"productId\":" + productId + ",\"quantity\":5,\"receivedByWorkerId\":" + workerId + "}")
        .when().post("/warehouse/withdrawals").then().statusCode(201);

        fixtures.assertStock(productId, "0", token);
    }

    // ---------- POST: WH-001 (stock insuficiente) --------------------------------

    @Test
    void create_quantityExceedsStock_returns409_WH001() {
        int productId = fixtures.seedProduct("ZTEST_WD Exceso");
        int workerId = fixtures.seedWorker("ZTESTW304");
        String token = login("admin", "Admin1234");
        fixtures.seedOpeningBalance(productId, "4", token);

        given().header("Authorization", "Bearer " + token).contentType(ContentType.JSON)
            .body("{\"productId\":" + productId + ",\"quantity\":5,\"receivedByWorkerId\":" + workerId + "}")
        .when().post("/warehouse/withdrawals")
        .then().statusCode(409).body("code", equalTo("WH-001")).body("detail", containsString("4"));
    }

    @Test
    void create_twoConcurrentWithdrawals_lockSerializesOneSucceedsOneRejected() throws Exception {
        // La razón de ser del endpoint (RN-WH2): dos retiros SIMULTÁNEOS del mismo producto no
        // pueden pasar ambos el chequeo de stock. Con el lock de fila correcto el resultado es
        // determinista (un 201 + un 409); sin el lock, las dos lecturas de stock verían el mismo
        // valor y ambos retiros descontarían → stock negativo. Se repite el par para que un lock
        // roto caiga en la race con alta probabilidad.
        String token = login("admin", "Admin1234");
        for (int i = 0; i < 5; i++) {
            int productId = fixtures.seedProduct("ZTEST_WD Concurrente " + i);
            int workerId = fixtures.seedWorker("ZTESTWC" + i);
            fixtures.seedOpeningBalance(productId, "5", token);   // alcanza para UN retiro de 3, no dos

            CyclicBarrier barrier = new CyclicBarrier(2);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                Callable<Integer> attempt = () -> {
                    barrier.await();   // ambos hilos disparan el POST a la vez
                    return given().header("Authorization", "Bearer " + token).contentType(ContentType.JSON)
                        .body("{\"productId\":" + productId + ",\"quantity\":3,\"receivedByWorkerId\":" + workerId + "}")
                    .when().post("/warehouse/withdrawals").then().extract().statusCode();
                };
                Future<Integer> f1 = pool.submit(attempt);
                Future<Integer> f2 = pool.submit(attempt);
                int s1 = f1.get(20, TimeUnit.SECONDS);
                int s2 = f2.get(20, TimeUnit.SECONDS);

                boolean exactlyOneEach = (s1 == 201 && s2 == 409) || (s1 == 409 && s2 == 201);
                if (!exactlyOneEach) {
                    throw new AssertionError("Iteración " + i + ": esperaba un 201 y un 409, fueron " + s1 + " y " + s2);
                }
                fixtures.assertStock(productId, "2", token);   // 5 - 3: el retiro perdedor no descontó
            } finally {
                pool.shutdownNow();
            }
        }
    }

    @Test
    void create_twoSequentialWithdrawalsExceedingStock_secondReturns409_WH001() {
        int productId = fixtures.seedProduct("ZTEST_WD Secuencial");
        int workerId = fixtures.seedWorker("ZTESTW305");
        String token = login("admin", "Admin1234");
        fixtures.seedOpeningBalance(productId, "5", token);

        given().header("Authorization", "Bearer " + token).contentType(ContentType.JSON)
            .body("{\"productId\":" + productId + ",\"quantity\":3,\"receivedByWorkerId\":" + workerId + "}")
        .when().post("/warehouse/withdrawals").then().statusCode(201);   // stock → 2

        given().header("Authorization", "Bearer " + token).contentType(ContentType.JSON)
            .body("{\"productId\":" + productId + ",\"quantity\":3,\"receivedByWorkerId\":" + workerId + "}")
        .when().post("/warehouse/withdrawals").then().statusCode(409).body("code", equalTo("WH-001"));
    }

    // ---------- POST: WH-005 (más de una unidad) ---------------------------------

    @Test
    void create_tractorAndTrailer_returns400_WH005() {
        int productId = fixtures.seedProduct("ZTEST_WD DosUnidades");
        int workerId = fixtures.seedWorker("ZTESTW306");
        int tractorId = fixtures.seedTractor("ZT0003", true);
        int trailerId = fixtures.seedTrailer("ZT0004", true);
        String token = login("admin", "Admin1234");
        fixtures.seedOpeningBalance(productId, "10", token);

        given().header("Authorization", "Bearer " + token).contentType(ContentType.JSON)
            .body("{\"productId\":" + productId + ",\"quantity\":1,\"receivedByWorkerId\":" + workerId
                + ",\"tractorId\":" + tractorId + ",\"trailerId\":" + trailerId + "}")
        .when().post("/warehouse/withdrawals")
        .then().statusCode(400).body("code", equalTo("WH-005"));
    }

    @Test
    void create_allThreeUnits_returns400_WH005() {
        int productId = fixtures.seedProduct("ZTEST_WD TresUnidades");
        int workerId = fixtures.seedWorker("ZTESTW307");
        int tractorId = fixtures.seedTractor("ZT0005", true);
        int trailerId = fixtures.seedTrailer("ZT0006", true);
        int escortId = fixtures.seedEscortVehicle("ZT0007", true);
        String token = login("admin", "Admin1234");
        fixtures.seedOpeningBalance(productId, "10", token);

        given().header("Authorization", "Bearer " + token).contentType(ContentType.JSON)
            .body("{\"productId\":" + productId + ",\"quantity\":1,\"receivedByWorkerId\":" + workerId
                + ",\"tractorId\":" + tractorId + ",\"trailerId\":" + trailerId + ",\"escortVehicleId\":" + escortId + "}")
        .when().post("/warehouse/withdrawals")
        .then().statusCode(400).body("code", equalTo("WH-005"));
    }

    // ---------- POST: WH-004 (FK inexistente/inactiva) ---------------------------

    @Test
    void create_nonexistentProduct_returns400_WH004() {
        int workerId = fixtures.seedWorker("ZTESTW308");
        String token = login("admin", "Admin1234");
        given().header("Authorization", "Bearer " + token).contentType(ContentType.JSON)
            .body("{\"productId\":999999,\"quantity\":1,\"receivedByWorkerId\":" + workerId + "}")
        .when().post("/warehouse/withdrawals").then().statusCode(400).body("code", equalTo("WH-004"));
    }

    @Test
    void create_nonexistentWorker_returns400_WH004() {
        int productId = fixtures.seedProduct("ZTEST_WD NoWorker");
        String token = login("admin", "Admin1234");
        fixtures.seedOpeningBalance(productId, "10", token);
        given().header("Authorization", "Bearer " + token).contentType(ContentType.JSON)
            .body("{\"productId\":" + productId + ",\"quantity\":1,\"receivedByWorkerId\":999999}")
        .when().post("/warehouse/withdrawals").then().statusCode(400).body("code", equalTo("WH-004"));
    }

    @Test
    void create_inactiveWorker_returns400_WH004() {
        int productId = fixtures.seedProduct("ZTEST_WD WorkerInactivo");
        int workerId = fixtures.seedWorker("ZTESTW309", false);
        String token = login("admin", "Admin1234");
        fixtures.seedOpeningBalance(productId, "10", token);
        given().header("Authorization", "Bearer " + token).contentType(ContentType.JSON)
            .body("{\"productId\":" + productId + ",\"quantity\":1,\"receivedByWorkerId\":" + workerId + "}")
        .when().post("/warehouse/withdrawals").then().statusCode(400).body("code", equalTo("WH-004"));
    }

    @Test
    void create_nonexistentTractor_returns400_WH004() {
        int productId = fixtures.seedProduct("ZTEST_WD NoTractor");
        int workerId = fixtures.seedWorker("ZTESTW310");
        String token = login("admin", "Admin1234");
        fixtures.seedOpeningBalance(productId, "10", token);
        given().header("Authorization", "Bearer " + token).contentType(ContentType.JSON)
            .body("{\"productId\":" + productId + ",\"quantity\":1,\"receivedByWorkerId\":" + workerId + ",\"tractorId\":999999}")
        .when().post("/warehouse/withdrawals").then().statusCode(400).body("code", equalTo("WH-004"));
    }

    @Test
    void create_inactiveTractor_returns400_WH004() {
        int productId = fixtures.seedProduct("ZTEST_WD TractorInactivo");
        int workerId = fixtures.seedWorker("ZTESTW311");
        int tractorId = fixtures.seedTractor("ZT0008", false);
        String token = login("admin", "Admin1234");
        fixtures.seedOpeningBalance(productId, "10", token);
        given().header("Authorization", "Bearer " + token).contentType(ContentType.JSON)
            .body("{\"productId\":" + productId + ",\"quantity\":1,\"receivedByWorkerId\":" + workerId + ",\"tractorId\":" + tractorId + "}")
        .when().post("/warehouse/withdrawals").then().statusCode(400).body("code", equalTo("WH-004"));
    }

    @Test
    void create_inactiveTrailer_returns400_WH004() {
        int productId = fixtures.seedProduct("ZTEST_WD TrailerInactivo");
        int workerId = fixtures.seedWorker("ZTESTW316");
        int trailerId = fixtures.seedTrailer("ZT0010", false);
        String token = login("admin", "Admin1234");
        fixtures.seedOpeningBalance(productId, "10", token);
        given().header("Authorization", "Bearer " + token).contentType(ContentType.JSON)
            .body("{\"productId\":" + productId + ",\"quantity\":1,\"receivedByWorkerId\":" + workerId + ",\"trailerId\":" + trailerId + "}")
        .when().post("/warehouse/withdrawals").then().statusCode(400).body("code", equalTo("WH-004"));
    }

    // ---------- POST: validación (COM-001) ---------------------------------------

    @Test
    void create_missingReceivedByWorkerId_returns400_COM001() {
        int productId = fixtures.seedProduct("ZTEST_WD SinWorker");
        String token = login("admin", "Admin1234");
        fixtures.seedOpeningBalance(productId, "10", token);
        given().header("Authorization", "Bearer " + token).contentType(ContentType.JSON)
            .body("{\"productId\":" + productId + ",\"quantity\":1}")
        .when().post("/warehouse/withdrawals").then().statusCode(400).body("code", equalTo("COM-001"));
    }

    @Test
    void create_quantityZero_returns400_COM001() {
        int productId = fixtures.seedProduct("ZTEST_WD QtyCero");
        int workerId = fixtures.seedWorker("ZTESTW312");
        String token = login("admin", "Admin1234");
        fixtures.seedOpeningBalance(productId, "10", token);
        given().header("Authorization", "Bearer " + token).contentType(ContentType.JSON)
            .body("{\"productId\":" + productId + ",\"quantity\":0,\"receivedByWorkerId\":" + workerId + "}")
        .when().post("/warehouse/withdrawals").then().statusCode(400).body("code", equalTo("COM-001"));
    }

    @Test
    void create_emptyBody_returns400_COM001() {
        String token = login("admin", "Admin1234");
        given().header("Authorization", "Bearer " + token).contentType(ContentType.JSON).body("{}")
        .when().post("/warehouse/withdrawals").then().statusCode(400).body("code", equalTo("COM-001"));
    }

    // ---------- POST: roles ------------------------------------------------------

    @Test
    void create_withoutToken_returns401() {
        given().contentType(ContentType.JSON).body("{\"productId\":1,\"quantity\":1,\"receivedByWorkerId\":1}")
        .when().post("/warehouse/withdrawals").then().statusCode(401);
    }

    @Test
    void create_withSalesRole_returns403_COM003() {
        String token = login("lcampos", "Sales1234");
        given().header("Authorization", "Bearer " + token).contentType(ContentType.JSON)
            .body("{\"productId\":1,\"quantity\":1,\"receivedByWorkerId\":1}")
        .when().post("/warehouse/withdrawals").then().statusCode(403).body("code", equalTo("COM-003"));
    }

    @Test
    void create_withDispatcherRole_returns403_COM003() {
        given().header("Authorization", "Bearer " + fabricateAccessToken("disp_test", "dispatcher"))
            .contentType(ContentType.JSON).body("{\"productId\":1,\"quantity\":1,\"receivedByWorkerId\":1}")
        .when().post("/warehouse/withdrawals").then().statusCode(403).body("code", equalTo("COM-003"));
    }

    @Test
    void create_withWarehouseKeeperRole_returns201() {
        int productId = fixtures.seedProduct("ZTEST_WD RoleWK");
        int workerId = fixtures.seedWorker("ZTESTW313");
        String token = login("admin", "Admin1234");
        fixtures.seedOpeningBalance(productId, "10", token);
        given().header("Authorization", "Bearer " + fabricateTokenForUser(fixtures.adminId(), "wk_test", "warehouse_keeper"))
            .contentType(ContentType.JSON)
            .body("{\"productId\":" + productId + ",\"quantity\":1,\"receivedByWorkerId\":" + workerId + "}")
        .when().post("/warehouse/withdrawals").then().statusCode(201);
    }

    @Test
    void create_withFinanceManagerRole_returns201() {
        int productId = fixtures.seedProduct("ZTEST_WD RoleFM");
        int workerId = fixtures.seedWorker("ZTESTW314");
        String token = login("admin", "Admin1234");
        fixtures.seedOpeningBalance(productId, "10", token);
        given().header("Authorization", "Bearer " + fabricateTokenForUser(fixtures.adminId(), "fm_test", "finance_manager"))
            .contentType(ContentType.JSON)
            .body("{\"productId\":" + productId + ",\"quantity\":1,\"receivedByWorkerId\":" + workerId + "}")
        .when().post("/warehouse/withdrawals").then().statusCode(201);
    }
}
