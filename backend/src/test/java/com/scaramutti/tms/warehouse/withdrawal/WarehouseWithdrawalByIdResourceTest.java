package com.scaramutti.tms.warehouse.withdrawal;

import com.scaramutti.tms.support.WarehouseTestData;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
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
 * Integration tests de GET/PUT/cancel /warehouse/withdrawals/{id}. Hermético (prefijo
 * ZTEST_, cleanup en orden FK). El stock se da con una apertura de inventario (vía su
 * endpoint); el retiro base se crea con el POST de alta de retiros. Worker y unidades de
 * flota se siembran por SQL nativo (no hay endpoint de alta para ellos).
 */
@QuarkusTest
class WarehouseWithdrawalByIdResourceTest {

    @Inject WarehouseTestData fixtures;
    @Inject EntityManager entityManager;

    @AfterEach
    void cleanupFixtures() {
        QuarkusTransaction.requiringNew().run(() -> {
            // audit_logs de retiros ZTEST: exclusivo de este test (el fragmento común no los toca).
            entityManager.createNativeQuery(
                "DELETE FROM almacen.audit_logs WHERE entity_type = 'WITHDRAWAL' AND entity_id IN "
                    + "(SELECT id FROM almacen.withdrawals WHERE product_id IN "
                    + "(SELECT id FROM almacen.products WHERE name LIKE 'ZTEST_%'))")
                .executeUpdate();
            fixtures.deleteWarehouseTestData();
            fixtures.deleteTestFleet();
            fixtures.deleteTestWorkers();
        });
    }

    // ---------- fixtures --------------------------------------------------------

    /** Retiro CANCELLED sembrado por SQL nativo (para el detalle de un retiro ya anulado). */
    private int seedCancelledWithdrawal(int productId, String quantity, int receivedBy) {
        return QuarkusTransaction.requiringNew().call(() -> ((Number) entityManager.createNativeQuery(
            "INSERT INTO almacen.withdrawals (product_id, quantity, received_by, registered_by, status, "
                + "cancel_reason, cancelled_by, cancelled_at) "
                + "VALUES (?1, CAST(?2 AS NUMERIC), ?3, ?4, 'CANCELLED', 'ZTEST anulado antes del test', ?4, CURRENT_TIMESTAMP) "
                + "RETURNING id")
            .setParameter(1, productId).setParameter(2, quantity)
            .setParameter(3, receivedBy).setParameter(4, fixtures.adminId())
            .getSingleResult()).intValue());
    }

    /** Crea un retiro vía POST y devuelve su id. */
    private int createWithdrawal(int productId, String quantity, int workerId, String token) {
        return given().header("Authorization", "Bearer " + token).contentType(ContentType.JSON)
            .body("{\"productId\":" + productId + ",\"quantity\":" + quantity + ",\"receivedByWorkerId\":" + workerId + "}")
        .when().post("/warehouse/withdrawals")
        .then().statusCode(201).extract().jsonPath().getInt("id");
    }

    private String etagOf(int id, String token) {
        return given().header("Authorization", "Bearer " + token)
        .when().get("/warehouse/withdrawals/" + id)
        .then().statusCode(200).extract().header("ETag");
    }

    // ---------- GET /{id} ---------------------------------------------------------

    @Test
    void get_existingWithdrawal_returns200WithDetailAndEtag() {
        int productId = fixtures.seedProduct("ZTEST_WD Get");
        int workerId = fixtures.seedWorker("ZTESTW400");
        String token = login("admin", "Admin1234");
        fixtures.seedOpeningBalance(productId, "10", token);
        int id = createWithdrawal(productId, "3", workerId, token);

        given().header("Authorization", "Bearer " + token)
        .when().get("/warehouse/withdrawals/" + id)
        .then().statusCode(200)
            .header("ETag", notNullValue())
            .body("id", equalTo(id))
            .body("product.id", equalTo(productId))
            .body("quantity", equalTo(3.0f))
            .body("receivedBy.id", equalTo(workerId))
            .body("fleetUnit", nullValue())
            .body("status", equalTo("ACTIVE"))
            .body("registeredBy.username", equalTo("admin"))
            .body("lastEdit", nullValue())
            .body("cancelReason", nullValue())
            .body("cancelledBy", nullValue())
            .body("cancelledAt", nullValue());
    }

    @Test
    void get_withTractor_returnsFleetUnitRef() {
        int productId = fixtures.seedProduct("ZTEST_WD GetTractor");
        int workerId = fixtures.seedWorker("ZTESTW401");
        int tractorId = fixtures.seedTractor("ZT0400", true);
        String token = login("admin", "Admin1234");
        fixtures.seedOpeningBalance(productId, "10", token);
        int id = given().header("Authorization", "Bearer " + token).contentType(ContentType.JSON)
            .body("{\"productId\":" + productId + ",\"quantity\":2,\"receivedByWorkerId\":" + workerId
                + ",\"tractorId\":" + tractorId + "}")
        .when().post("/warehouse/withdrawals").then().statusCode(201).extract().jsonPath().getInt("id");

        given().header("Authorization", "Bearer " + token)
        .when().get("/warehouse/withdrawals/" + id)
        .then().statusCode(200)
            .body("fleetUnit.kind", equalTo("TRACTOR"))
            .body("fleetUnit.id", equalTo(tractorId))
            .body("fleetUnit.plate", equalTo("ZT0400"));
    }

    @Test
    void get_cancelledWithdrawal_returnsCancelFields() {
        int productId = fixtures.seedProduct("ZTEST_WD GetCancelled");
        int workerId = fixtures.seedWorker("ZTESTW402");
        String token = login("admin", "Admin1234");
        int id = seedCancelledWithdrawal(productId, "4", workerId);

        given().header("Authorization", "Bearer " + token)
        .when().get("/warehouse/withdrawals/" + id)
        .then().statusCode(200)
            .body("status", equalTo("CANCELLED"))
            .body("cancelReason", equalTo("ZTEST anulado antes del test"))
            .body("cancelledBy.username", equalTo("admin"))
            .body("cancelledAt", notNullValue());
    }

    @Test
    void get_nonexistentId_returns404_WH003() {
        String token = login("admin", "Admin1234");
        given().header("Authorization", "Bearer " + token)
        .when().get("/warehouse/withdrawals/999999")
        .then().statusCode(404).body("code", equalTo("WH-003"));
    }

    @Test
    void get_idNonNumeric_returns404() {
        String token = login("admin", "Admin1234");
        given().header("Authorization", "Bearer " + token)
        .when().get("/warehouse/withdrawals/abc")
        .then().statusCode(404);
    }

    @Test
    void get_withoutToken_returns401() {
        given().when().get("/warehouse/withdrawals/1").then().statusCode(401);
    }

    @Test
    void get_withSalesRole_returns403_COM003() {
        String token = login("lcampos", "Sales1234");
        given().header("Authorization", "Bearer " + token)
        .when().get("/warehouse/withdrawals/1")
        .then().statusCode(403).body("code", equalTo("COM-003"));
    }

    @Test
    void get_withDispatcherRole_returns403_COM003() {
        given().header("Authorization", "Bearer " + fabricateAccessToken("disp_test", "dispatcher"))
        .when().get("/warehouse/withdrawals/1")
        .then().statusCode(403).body("code", equalTo("COM-003"));
    }

    // ---------- PUT /{id} helpers -------------------------------------------------

    private String updateBody(String quantity, int workerId, String reason) {
        return "{\"quantity\":" + quantity + ",\"receivedByWorkerId\":" + workerId + ",\"reason\":\"" + reason + "\"}";
    }

    private long countChanges(int withdrawalId, String changeType) {
        return ((Number) entityManager.createNativeQuery(
            "SELECT COUNT(*) FROM almacen.audit_logs WHERE entity_type = 'WITHDRAWAL' "
                + "AND entity_id = ?1 AND change_type = ?2")
            .setParameter(1, withdrawalId).setParameter(2, changeType).getSingleResult()).longValue();
    }

    // ---------- PUT /{id} happy ---------------------------------------------------

    @Test
    void update_lowerQuantity_returns200_lastEdit_stockReturns() {
        int productId = fixtures.seedProduct("ZTEST_WD EditLower");
        int workerId = fixtures.seedWorker("ZTESTW410");
        String token = login("admin", "Admin1234");
        fixtures.seedOpeningBalance(productId, "10", token);
        int id = createWithdrawal(productId, "7", workerId, token);
        String etag = etagOf(id, token);

        given().header("Authorization", "Bearer " + token).header("If-Match", etag)
            .contentType(ContentType.JSON)
            .body(updateBody("3", workerId, "Se pidieron 7 filtros pero se usaron 3, se devuelven 4"))
        .when().put("/warehouse/withdrawals/" + id)
        .then().statusCode(200)
            .header("ETag", notNullValue())
            .body("quantity", equalTo(3))
            .body("lastEdit.by.username", equalTo("admin"))
            .body("lastEdit.reason", equalTo("Se pidieron 7 filtros pero se usaron 3, se devuelven 4"));

        fixtures.assertStock(productId, "7", token);   // 10 - 3 (antes 10 - 7 = 3)
    }

    @Test
    void update_raiseWithinAvailable_returns200_stockDecreases() {
        int productId = fixtures.seedProduct("ZTEST_WD EditRaise");
        int workerId = fixtures.seedWorker("ZTESTW411");
        String token = login("admin", "Admin1234");
        fixtures.seedOpeningBalance(productId, "10", token);
        int id = createWithdrawal(productId, "3", workerId, token);   // stock 7
        String etag = etagOf(id, token);

        given().header("Authorization", "Bearer " + token).header("If-Match", etag)
            .contentType(ContentType.JSON)
            .body(updateBody("6", workerId, "Se necesitaban 6 y no 3 para el mantenimiento"))
        .when().put("/warehouse/withdrawals/" + id)
        .then().statusCode(200).body("quantity", equalTo(6));

        fixtures.assertStock(productId, "4", token);   // 10 - 6
    }

    @Test
    void update_replaceFleetUnit_tractorToTrailer_returns200() {
        int productId = fixtures.seedProduct("ZTEST_WD EditFleet");
        int workerId = fixtures.seedWorker("ZTESTW412");
        int tractorId = fixtures.seedTractor("ZT0410", true);
        int trailerId = fixtures.seedTrailer("ZT0411", true);
        String token = login("admin", "Admin1234");
        fixtures.seedOpeningBalance(productId, "10", token);
        int id = given().header("Authorization", "Bearer " + token).contentType(ContentType.JSON)
            .body("{\"productId\":" + productId + ",\"quantity\":2,\"receivedByWorkerId\":" + workerId
                + ",\"tractorId\":" + tractorId + "}")
        .when().post("/warehouse/withdrawals").then().statusCode(201).extract().jsonPath().getInt("id");
        String etag = etagOf(id, token);

        given().header("Authorization", "Bearer " + token).header("If-Match", etag)
            .contentType(ContentType.JSON)
            .body("{\"quantity\":2,\"receivedByWorkerId\":" + workerId + ",\"trailerId\":" + trailerId
                + ",\"reason\":\"Se cargo a la carreta y no al tracto\"}")
        .when().put("/warehouse/withdrawals/" + id)
        .then().statusCode(200)
            .body("fleetUnit.kind", equalTo("TRAILER"))
            .body("fleetUnit.id", equalTo(trailerId));
    }

    @Test
    void update_removeFleetUnit_returns200Null() {
        int productId = fixtures.seedProduct("ZTEST_WD EditRemoveFleet");
        int workerId = fixtures.seedWorker("ZTESTW413");
        int tractorId = fixtures.seedTractor("ZT0412", true);
        String token = login("admin", "Admin1234");
        fixtures.seedOpeningBalance(productId, "10", token);
        int id = given().header("Authorization", "Bearer " + token).contentType(ContentType.JSON)
            .body("{\"productId\":" + productId + ",\"quantity\":2,\"receivedByWorkerId\":" + workerId
                + ",\"tractorId\":" + tractorId + "}")
        .when().post("/warehouse/withdrawals").then().statusCode(201).extract().jsonPath().getInt("id");
        String etag = etagOf(id, token);

        given().header("Authorization", "Bearer " + token).header("If-Match", etag)
            .contentType(ContentType.JSON)
            .body(updateBody("2", workerId, "El retiro no iba a ninguna unidad en particular"))
        .when().put("/warehouse/withdrawals/" + id)
        .then().statusCode(200).body("fleetUnit", nullValue());
    }

    @Test
    void update_thenNewEtagDiffersFromOld() {
        int productId = fixtures.seedProduct("ZTEST_WD EditEtag");
        int workerId = fixtures.seedWorker("ZTESTW414");
        String token = login("admin", "Admin1234");
        fixtures.seedOpeningBalance(productId, "10", token);
        int id = createWithdrawal(productId, "3", workerId, token);
        String oldEtag = etagOf(id, token);

        String newEtag = given().header("Authorization", "Bearer " + token).header("If-Match", oldEtag)
            .contentType(ContentType.JSON).body(updateBody("4", workerId, "Ajuste de la cantidad retirada"))
        .when().put("/warehouse/withdrawals/" + id)
        .then().statusCode(200).extract().header("ETag");

        if (newEtag.equals(oldEtag)) {
            throw new AssertionError("El ETag no cambió tras la edición");
        }
    }

    @Test
    void update_twoFieldsChanged_writesOneFieldEditRowPerField() {
        int productId = fixtures.seedProduct("ZTEST_WD EditDiff");
        int workerId = fixtures.seedWorker("ZTESTW415");
        int otherWorkerId = fixtures.seedWorker("ZTESTW416");
        String token = login("admin", "Admin1234");
        fixtures.seedOpeningBalance(productId, "10", token);
        int id = createWithdrawal(productId, "3", workerId, token);
        String etag = etagOf(id, token);

        // Cambian cantidad Y quien recibe (2 campos): 2 filas FIELD_EDIT, ninguna para flota/obs.
        given().header("Authorization", "Bearer " + token).header("If-Match", etag)
            .contentType(ContentType.JSON)
            .body("{\"quantity\":5,\"receivedByWorkerId\":" + otherWorkerId
                + ",\"reason\":\"Se corrige cantidad y receptor\"}")
        .when().put("/warehouse/withdrawals/" + id).then().statusCode(200);

        if (countChanges(id, "FIELD_EDIT") != 2) {
            throw new AssertionError("Esperaba 2 filas FIELD_EDIT, fueron " + countChanges(id, "FIELD_EDIT"));
        }
    }

    @Test
    void update_noopValues_writesNoFieldEditRows_butBumpsEtag() {
        int productId = fixtures.seedProduct("ZTEST_WD EditNoop");
        int workerId = fixtures.seedWorker("ZTESTW417");
        String token = login("admin", "Admin1234");
        fixtures.seedOpeningBalance(productId, "10", token);
        int id = createWithdrawal(productId, "3", workerId, token);
        String oldEtag = etagOf(id, token);

        // Mismos valores actuales: 0 filas de auditoría, pero la versión igual se bumpea.
        String newEtag = given().header("Authorization", "Bearer " + token).header("If-Match", oldEtag)
            .contentType(ContentType.JSON).body(updateBody("3", workerId, "Se guarda sin cambios reales"))
        .when().put("/warehouse/withdrawals/" + id).then().statusCode(200).extract().header("ETag");

        if (countChanges(id, "FIELD_EDIT") != 0) {
            throw new AssertionError("Un no-op no debería escribir filas FIELD_EDIT");
        }
        if (newEtag.equals(oldEtag)) {
            throw new AssertionError("El ETag debería bumpearse aunque no cambien los campos");
        }
    }

    @Test
    void update_productIdInBodyIsIgnored_productImmutable() {
        int productId = fixtures.seedProduct("ZTEST_WD EditImmutable");
        int otherProductId = fixtures.seedProduct("ZTEST_WD EditImmutableOther");
        int workerId = fixtures.seedWorker("ZTESTW418");
        String token = login("admin", "Admin1234");
        fixtures.seedOpeningBalance(productId, "10", token);
        int id = createWithdrawal(productId, "3", workerId, token);
        String etag = etagOf(id, token);

        given().header("Authorization", "Bearer " + token).header("If-Match", etag)
            .contentType(ContentType.JSON)
            .body("{\"productId\":" + otherProductId + ",\"quantity\":3,\"receivedByWorkerId\":" + workerId
                + ",\"reason\":\"Intento de cambiar el producto (debe ignorarse)\"}")
        .when().put("/warehouse/withdrawals/" + id)
        .then().statusCode(200).body("product.id", equalTo(productId));
    }

    // ---------- PUT /{id} WH-001 (stock) -----------------------------------------

    @Test
    void update_raiseExceedsAvailable_returns409_WH001() {
        int productId = fixtures.seedProduct("ZTEST_WD EditExceso");
        int workerId = fixtures.seedWorker("ZTESTW419");
        String token = login("admin", "Admin1234");
        fixtures.seedOpeningBalance(productId, "6", token);
        int id = createWithdrawal(productId, "4", workerId, token);   // stock 2; disponible sin este = 6
        String etag = etagOf(id, token);

        given().header("Authorization", "Bearer " + token).header("If-Match", etag)
            .contentType(ContentType.JSON).body(updateBody("8", workerId, "Se intenta subir por encima del stock"))
        .when().put("/warehouse/withdrawals/" + id)
        .then().statusCode(409).body("code", equalTo("WH-001")).body("detail", containsString("6"));
    }

    @Test
    void update_twoConcurrentRaises_lockSerializesOneSucceedsOneRejected() throws Exception {
        // Dos retiros DISTINTOS del MISMO producto suben cantidad a la vez compitiendo por el
        // stock restante. Cada uno lleva su propio ETag válido (filas distintas), así que no chocan
        // en el If-Match: compiten en el lock de fila del producto. Con el lock correcto el
        // resultado es determinista (un 200 y un 409 WH-001); sin él ambas subas descontarían y el
        // stock quedaría negativo. Se repite para exponer un lock roto con alta probabilidad.
        String token = login("admin", "Admin1234");
        for (int i = 0; i < 5; i++) {
            int productId = fixtures.seedProduct("ZTEST_WD EditConc " + i);
            int workerId = fixtures.seedWorker("ZTESTWEC" + i);
            fixtures.seedOpeningBalance(productId, "6", token);
            int idA = createWithdrawal(productId, "2", workerId, token);
            int idB = createWithdrawal(productId, "2", workerId, token);   // stock 2
            String etagA = etagOf(idA, token);
            String etagB = etagOf(idB, token);

            CyclicBarrier barrier = new CyclicBarrier(2);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                Callable<Integer> raiseA = raiseAttempt(idA, etagA, productId, workerId, barrier, token);
                Callable<Integer> raiseB = raiseAttempt(idB, etagB, productId, workerId, barrier, token);
                Future<Integer> f1 = pool.submit(raiseA);
                Future<Integer> f2 = pool.submit(raiseB);
                int s1 = f1.get(20, TimeUnit.SECONDS);
                int s2 = f2.get(20, TimeUnit.SECONDS);

                boolean exactlyOneEach = (s1 == 200 && s2 == 409) || (s1 == 409 && s2 == 200);
                if (!exactlyOneEach) {
                    throw new AssertionError("Iteración " + i + ": esperaba un 200 y un 409, fueron " + s1 + " y " + s2);
                }
                fixtures.assertStock(productId, "0", token);   // 6 - 4 (ganador) - 2 (perdedor sigue en 2)
            } finally {
                pool.shutdownNow();
            }
        }
    }

    /** Sube un retiro a cantidad 4 (delta que solo entra si el otro no gano) tras la barrera. */
    private Callable<Integer> raiseAttempt(int withdrawalId, String etag, int productId, int workerId,
                                           CyclicBarrier barrier, String token) {
        return () -> {
            barrier.await();
            return given().header("Authorization", "Bearer " + token).header("If-Match", etag)
                .contentType(ContentType.JSON)
                .body(updateBody("4", workerId, "Se necesita el doble para el mantenimiento urgente"))
            .when().put("/warehouse/withdrawals/" + withdrawalId).then().extract().statusCode();
        };
    }

    // ---------- PUT /{id} WH-005 / WH-004 ----------------------------------------

    @Test
    void update_twoFleetUnits_returns400_WH005() {
        int productId = fixtures.seedProduct("ZTEST_WD EditDosUnidades");
        int workerId = fixtures.seedWorker("ZTESTW420");
        int tractorId = fixtures.seedTractor("ZT0420", true);
        int trailerId = fixtures.seedTrailer("ZT0421", true);
        String token = login("admin", "Admin1234");
        fixtures.seedOpeningBalance(productId, "10", token);
        int id = createWithdrawal(productId, "2", workerId, token);
        String etag = etagOf(id, token);

        given().header("Authorization", "Bearer " + token).header("If-Match", etag)
            .contentType(ContentType.JSON)
            .body("{\"quantity\":2,\"receivedByWorkerId\":" + workerId + ",\"tractorId\":" + tractorId
                + ",\"trailerId\":" + trailerId + ",\"reason\":\"Dos unidades, debe fallar\"}")
        .when().put("/warehouse/withdrawals/" + id)
        .then().statusCode(400).body("code", equalTo("WH-005"));
    }

    @Test
    void update_inactiveWorker_returns400_WH004() {
        int productId = fixtures.seedProduct("ZTEST_WD EditWorkerInactivo");
        int workerId = fixtures.seedWorker("ZTESTW421");
        int inactiveWorkerId = fixtures.seedWorker("ZTESTW422", false);
        String token = login("admin", "Admin1234");
        fixtures.seedOpeningBalance(productId, "10", token);
        int id = createWithdrawal(productId, "2", workerId, token);
        String etag = etagOf(id, token);

        given().header("Authorization", "Bearer " + token).header("If-Match", etag)
            .contentType(ContentType.JSON)
            .body(updateBody("2", inactiveWorkerId, "Se asigna a un trabajador inactivo"))
        .when().put("/warehouse/withdrawals/" + id)
        .then().statusCode(400).body("code", equalTo("WH-004"));
    }

    @Test
    void update_inactiveTractor_returns400_WH004() {
        int productId = fixtures.seedProduct("ZTEST_WD EditTractorInactivo");
        int workerId = fixtures.seedWorker("ZTESTW423");
        int inactiveTractorId = fixtures.seedTractor("ZT0423", false);
        String token = login("admin", "Admin1234");
        fixtures.seedOpeningBalance(productId, "10", token);
        int id = createWithdrawal(productId, "2", workerId, token);
        String etag = etagOf(id, token);

        given().header("Authorization", "Bearer " + token).header("If-Match", etag)
            .contentType(ContentType.JSON)
            .body("{\"quantity\":2,\"receivedByWorkerId\":" + workerId + ",\"tractorId\":" + inactiveTractorId
                + ",\"reason\":\"Se asigna a un tracto inactivo\"}")
        .when().put("/warehouse/withdrawals/" + id)
        .then().statusCode(400).body("code", equalTo("WH-004"));
    }

    // ---------- PUT /{id} WH-008 / If-Match / validación -------------------------

    @Test
    void update_cancelledWithdrawal_returns409_WH008() {
        int productId = fixtures.seedProduct("ZTEST_WD EditCancelled");
        int workerId = fixtures.seedWorker("ZTESTW424");
        String token = login("admin", "Admin1234");
        int id = seedCancelledWithdrawal(productId, "3", workerId);
        String etag = etagOf(id, token);

        given().header("Authorization", "Bearer " + token).header("If-Match", etag)
            .contentType(ContentType.JSON).body(updateBody("2", workerId, "Intento de editar un anulado"))
        .when().put("/warehouse/withdrawals/" + id)
        .then().statusCode(409).body("code", equalTo("WH-008"));
    }

    @Test
    void update_missingIfMatch_returns412_COM004() {
        int productId = fixtures.seedProduct("ZTEST_WD EditNoIfMatch");
        int workerId = fixtures.seedWorker("ZTESTW425");
        String token = login("admin", "Admin1234");
        fixtures.seedOpeningBalance(productId, "10", token);
        int id = createWithdrawal(productId, "3", workerId, token);

        given().header("Authorization", "Bearer " + token).contentType(ContentType.JSON)
            .body(updateBody("2", workerId, "Sin If-Match, debe fallar"))
        .when().put("/warehouse/withdrawals/" + id)
        .then().statusCode(412).body("code", equalTo("COM-004"));
    }

    @Test
    void update_staleIfMatch_returns412_COM004() {
        int productId = fixtures.seedProduct("ZTEST_WD EditStale");
        int workerId = fixtures.seedWorker("ZTESTW426");
        String token = login("admin", "Admin1234");
        fixtures.seedOpeningBalance(productId, "10", token);
        int id = createWithdrawal(productId, "3", workerId, token);
        String staleEtag = etagOf(id, token);

        // Una primera edición cambia la versión.
        given().header("Authorization", "Bearer " + token).header("If-Match", staleEtag)
            .contentType(ContentType.JSON).body(updateBody("4", workerId, "Primera edición que mueve la versión"))
        .when().put("/warehouse/withdrawals/" + id).then().statusCode(200);

        // Reintentar con el ETag viejo.
        given().header("Authorization", "Bearer " + token).header("If-Match", staleEtag)
            .contentType(ContentType.JSON).body(updateBody("2", workerId, "Reintento con ETag viejo"))
        .when().put("/warehouse/withdrawals/" + id)
        .then().statusCode(412).body("code", equalTo("COM-004"));
    }

    @Test
    void update_reasonTooShort_returns400_COM001() {
        int productId = fixtures.seedProduct("ZTEST_WD EditReasonCorto");
        int workerId = fixtures.seedWorker("ZTESTW427");
        String token = login("admin", "Admin1234");
        fixtures.seedOpeningBalance(productId, "10", token);
        int id = createWithdrawal(productId, "3", workerId, token);
        String etag = etagOf(id, token);

        given().header("Authorization", "Bearer " + token).header("If-Match", etag)
            .contentType(ContentType.JSON).body(updateBody("2", workerId, "corto"))
        .when().put("/warehouse/withdrawals/" + id)
        .then().statusCode(400).body("code", equalTo("COM-001"));
    }

    @Test
    void update_missingReason_returns400_COM001() {
        int productId = fixtures.seedProduct("ZTEST_WD EditSinReason");
        int workerId = fixtures.seedWorker("ZTESTW428");
        String token = login("admin", "Admin1234");
        fixtures.seedOpeningBalance(productId, "10", token);
        int id = createWithdrawal(productId, "3", workerId, token);
        String etag = etagOf(id, token);

        given().header("Authorization", "Bearer " + token).header("If-Match", etag)
            .contentType(ContentType.JSON)
            .body("{\"quantity\":2,\"receivedByWorkerId\":" + workerId + "}")
        .when().put("/warehouse/withdrawals/" + id)
        .then().statusCode(400).body("code", equalTo("COM-001"));
    }

    @Test
    void update_nonexistent_returns404_WH003() {
        int workerId = fixtures.seedWorker("ZTESTW429");
        String token = login("admin", "Admin1234");
        given().header("Authorization", "Bearer " + token).header("If-Match", "\"x\"")
            .contentType(ContentType.JSON).body(updateBody("2", workerId, "Editar un retiro inexistente"))
        .when().put("/warehouse/withdrawals/999999")
        .then().statusCode(404).body("code", equalTo("WH-003"));
    }

    @Test
    void update_withoutToken_returns401() {
        given().header("If-Match", "\"x\"").contentType(ContentType.JSON)
            .body("{\"quantity\":2,\"receivedByWorkerId\":1,\"reason\":\"Sin token de acceso\"}")
        .when().put("/warehouse/withdrawals/1").then().statusCode(401);
    }

    @Test
    void update_withSalesRole_returns403_COM003() {
        String token = login("lcampos", "Sales1234");
        given().header("Authorization", "Bearer " + token).header("If-Match", "\"x\"")
            .contentType(ContentType.JSON)
            .body("{\"quantity\":2,\"receivedByWorkerId\":1,\"reason\":\"Rol sin permiso de almacen\"}")
        .when().put("/warehouse/withdrawals/1").then().statusCode(403).body("code", equalTo("COM-003"));
    }

    @Test
    void update_withWarehouseKeeperRole_returns200() {
        int productId = fixtures.seedProduct("ZTEST_WD EditRoleWK");
        int workerId = fixtures.seedWorker("ZTESTW430");
        String token = login("admin", "Admin1234");
        fixtures.seedOpeningBalance(productId, "10", token);
        int id = createWithdrawal(productId, "3", workerId, token);
        String etag = etagOf(id, token);

        given().header("Authorization", "Bearer " + fabricateTokenForUser(fixtures.adminId(), "wk_test", "warehouse_keeper"))
            .header("If-Match", etag).contentType(ContentType.JSON)
            .body(updateBody("2", workerId, "Ajuste registrado por el almacenero"))
        .when().put("/warehouse/withdrawals/" + id).then().statusCode(200);
    }

    @Test
    void update_cancelledWithStaleIfMatch_returns409_WH008_beforeIfMatch() {
        int productId = fixtures.seedProduct("ZTEST_WD EditCancelledStale");
        int workerId = fixtures.seedWorker("ZTESTW431");
        String token = login("admin", "Admin1234");
        int id = seedCancelledWithdrawal(productId, "3", workerId);

        // Un retiro anulado + If-Match invalido: el 409 WH-008 gana antes que la verificacion de
        // If-Match (412). Pinnea el orden anulado-antes-de-precondicion.
        given().header("Authorization", "Bearer " + token).header("If-Match", "\"garbage\"")
            .contentType(ContentType.JSON).body(updateBody("2", workerId, "Editar un anulado con ETag invalido"))
        .when().put("/warehouse/withdrawals/" + id)
        .then().statusCode(409).body("code", equalTo("WH-008"));
    }

    // ---------- POST /{id}/cancel -------------------------------------------------

    private String cancelBody(String reason) {
        return "{\"reason\":\"" + reason + "\"}";
    }

    @Test
    void cancel_activeWithdrawal_returns200_stockReturns_auditRow() {
        int productId = fixtures.seedProduct("ZTEST_WD Cancel");
        int workerId = fixtures.seedWorker("ZTESTW440");
        String token = login("admin", "Admin1234");
        fixtures.seedOpeningBalance(productId, "10", token);
        int id = createWithdrawal(productId, "4", workerId, token);   // stock 6
        String etag = etagOf(id, token);

        given().header("Authorization", "Bearer " + token).header("If-Match", etag)
            .contentType(ContentType.JSON).body(cancelBody("El tracto no necesitaba el repuesto, era otra falla"))
        .when().post("/warehouse/withdrawals/" + id + "/cancel")
        .then().statusCode(200)
            .header("ETag", notNullValue())
            .body("status", equalTo("CANCELLED"))
            .body("cancelReason", equalTo("El tracto no necesitaba el repuesto, era otra falla"))
            .body("cancelledBy.username", equalTo("admin"))
            .body("cancelledAt", notNullValue());

        fixtures.assertStock(productId, "10", token);   // el retiro anulado devuelve el stock
        if (countChanges(id, "CANCELLED") != 1) {
            throw new AssertionError("Esperaba 1 fila CANCELLED en audit_logs");
        }
    }

    @Test
    void cancel_alreadyCancelled_returns409_WH008() {
        int productId = fixtures.seedProduct("ZTEST_WD CancelTwice");
        int workerId = fixtures.seedWorker("ZTESTW441");
        String token = login("admin", "Admin1234");
        fixtures.seedOpeningBalance(productId, "10", token);
        int id = createWithdrawal(productId, "3", workerId, token);
        String etag = etagOf(id, token);

        given().header("Authorization", "Bearer " + token).header("If-Match", etag)
            .contentType(ContentType.JSON).body(cancelBody("Primera anulacion valida del retiro"))
        .when().post("/warehouse/withdrawals/" + id + "/cancel").then().statusCode(200);

        String newEtag = etagOf(id, token);
        given().header("Authorization", "Bearer " + token).header("If-Match", newEtag)
            .contentType(ContentType.JSON).body(cancelBody("Segundo intento sobre un retiro ya anulado"))
        .when().post("/warehouse/withdrawals/" + id + "/cancel")
        .then().statusCode(409).body("code", equalTo("WH-008"));
    }

    @Test
    void cancel_missingIfMatch_returns412_COM004() {
        int productId = fixtures.seedProduct("ZTEST_WD CancelNoIfMatch");
        int workerId = fixtures.seedWorker("ZTESTW442");
        String token = login("admin", "Admin1234");
        fixtures.seedOpeningBalance(productId, "10", token);
        int id = createWithdrawal(productId, "3", workerId, token);

        given().header("Authorization", "Bearer " + token).contentType(ContentType.JSON)
            .body(cancelBody("Anular sin If-Match, debe fallar"))
        .when().post("/warehouse/withdrawals/" + id + "/cancel")
        .then().statusCode(412).body("code", equalTo("COM-004"));
    }

    @Test
    void cancel_staleIfMatch_returns412_COM004() {
        int productId = fixtures.seedProduct("ZTEST_WD CancelStale");
        int workerId = fixtures.seedWorker("ZTESTW443");
        String token = login("admin", "Admin1234");
        fixtures.seedOpeningBalance(productId, "10", token);
        int id = createWithdrawal(productId, "3", workerId, token);
        String staleEtag = etagOf(id, token);

        // Una edicion mueve la version.
        given().header("Authorization", "Bearer " + token).header("If-Match", staleEtag)
            .contentType(ContentType.JSON).body(updateBody("4", workerId, "Edicion que mueve la version"))
        .when().put("/warehouse/withdrawals/" + id).then().statusCode(200);

        given().header("Authorization", "Bearer " + token).header("If-Match", staleEtag)
            .contentType(ContentType.JSON).body(cancelBody("Anular con un ETag viejo"))
        .when().post("/warehouse/withdrawals/" + id + "/cancel")
        .then().statusCode(412).body("code", equalTo("COM-004"));
    }

    @Test
    void cancel_nonexistent_returns404_WH003() {
        String token = login("admin", "Admin1234");
        given().header("Authorization", "Bearer " + token).header("If-Match", "\"x\"")
            .contentType(ContentType.JSON).body(cancelBody("Anular un retiro inexistente"))
        .when().post("/warehouse/withdrawals/999999/cancel")
        .then().statusCode(404).body("code", equalTo("WH-003"));
    }

    @Test
    void cancel_reasonTooShort_returns400_COM001() {
        int productId = fixtures.seedProduct("ZTEST_WD CancelReasonCorto");
        int workerId = fixtures.seedWorker("ZTESTW444");
        String token = login("admin", "Admin1234");
        fixtures.seedOpeningBalance(productId, "10", token);
        int id = createWithdrawal(productId, "3", workerId, token);
        String etag = etagOf(id, token);

        given().header("Authorization", "Bearer " + token).header("If-Match", etag)
            .contentType(ContentType.JSON).body(cancelBody("corto"))
        .when().post("/warehouse/withdrawals/" + id + "/cancel")
        .then().statusCode(400).body("code", equalTo("COM-001"));
    }

    @Test
    void cancel_missingReason_returns400_COM001() {
        int productId = fixtures.seedProduct("ZTEST_WD CancelSinReason");
        int workerId = fixtures.seedWorker("ZTESTW445");
        String token = login("admin", "Admin1234");
        fixtures.seedOpeningBalance(productId, "10", token);
        int id = createWithdrawal(productId, "3", workerId, token);
        String etag = etagOf(id, token);

        given().header("Authorization", "Bearer " + token).header("If-Match", etag)
            .contentType(ContentType.JSON).body("{}")
        .when().post("/warehouse/withdrawals/" + id + "/cancel")
        .then().statusCode(400).body("code", equalTo("COM-001"));
    }

    @Test
    void cancel_withoutToken_returns401() {
        given().header("If-Match", "\"x\"").contentType(ContentType.JSON)
            .body(cancelBody("Anular sin token de acceso"))
        .when().post("/warehouse/withdrawals/1/cancel").then().statusCode(401);
    }

    @Test
    void cancel_withSalesRole_returns403_COM003() {
        String token = login("lcampos", "Sales1234");
        given().header("Authorization", "Bearer " + token).header("If-Match", "\"x\"")
            .contentType(ContentType.JSON).body(cancelBody("Rol de ventas sin permiso de almacen"))
        .when().post("/warehouse/withdrawals/1/cancel").then().statusCode(403).body("code", equalTo("COM-003"));
    }

    @Test
    void cancel_withWarehouseKeeperRole_returns200() {
        int productId = fixtures.seedProduct("ZTEST_WD CancelRoleWK");
        int workerId = fixtures.seedWorker("ZTESTW446");
        String token = login("admin", "Admin1234");
        fixtures.seedOpeningBalance(productId, "10", token);
        int id = createWithdrawal(productId, "3", workerId, token);
        String etag = etagOf(id, token);

        given().header("Authorization", "Bearer " + fabricateTokenForUser(fixtures.adminId(), "wk_test", "warehouse_keeper"))
            .header("If-Match", etag).contentType(ContentType.JSON)
            .body(cancelBody("Anulacion registrada por el almacenero"))
        .when().post("/warehouse/withdrawals/" + id + "/cancel").then().statusCode(200);
    }
}
