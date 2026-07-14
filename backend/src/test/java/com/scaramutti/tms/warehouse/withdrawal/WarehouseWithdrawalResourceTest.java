package com.scaramutti.tms.warehouse.withdrawal;

import com.scaramutti.tms.shared.entity.Product;
import com.scaramutti.tms.shared.entity.User;
import com.scaramutti.tms.shared.entity.Worker;
import com.scaramutti.tms.shared.repository.ProductRepository;
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
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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
            // de placa. tractors/trailers/escort viven en public COMPARTIDO con v1 y un prefijo de
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
        return seedWorker(documentNumber, true);
    }

    private int seedWorker(String documentNumber, boolean isActive) {
        return QuarkusTransaction.requiringNew().call(() -> {
            Worker worker = new Worker();
            worker.firstName = "ZTEST";
            worker.lastName = "Operario";
            worker.documentTypeId = dniDocumentTypeId();
            worker.documentNumber = documentNumber;
            worker.position = "ZTEST Operario";
            worker.isActive = isActive;
            worker.createdAt = OffsetDateTime.now();
            workerRepository.persist(worker);
            return worker.id;
        });
    }

    /**
     * Estado de recurso propio y estable ({@code ZTEST_STATUS}), sembrado get-or-create
     * como {@link #dniDocumentTypeId()}: {@code public.resource_statuses} es una tabla de
     * v1 sin seed en la BD virgen de CI, así que asumir una fila existente ({@code LIMIT 1})
     * fallaba solo en CI. La flota lo referencia por FK ({@code status_id}).
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

    private int seedTractor(String plate, boolean isActive) {
        return QuarkusTransaction.requiringNew().call(() -> ((Number) entityManager.createNativeQuery(
            "INSERT INTO public.tractors (plate, status_id, is_active) VALUES (?1, ?2, ?3) RETURNING id")
            .setParameter(1, plate).setParameter(2, resourceStatusId()).setParameter(3, isActive)
            .getSingleResult()).intValue());
    }

    private int seedTrailer(String plate, boolean isActive) {
        return QuarkusTransaction.requiringNew().call(() -> ((Number) entityManager.createNativeQuery(
            "INSERT INTO public.trailers (plate, type, status_id, is_active) VALUES (?1, 'ZTEST', ?2, ?3) RETURNING id")
            .setParameter(1, plate).setParameter(2, resourceStatusId()).setParameter(3, isActive)
            .getSingleResult()).intValue());
    }

    private int seedEscortVehicle(String plate, boolean isActive) {
        return QuarkusTransaction.requiringNew().call(() -> ((Number) entityManager.createNativeQuery(
            "INSERT INTO public.escort_vehicles (plate, status_id, is_active) VALUES (?1, ?2, ?3) RETURNING id")
            .setParameter(1, plate).setParameter(2, resourceStatusId()).setParameter(3, isActive)
            .getSingleResult()).intValue());
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

    private void seedOpeningBalance(int productId, String quantity, String token) {
        given().header("Authorization", "Bearer " + token).contentType(ContentType.JSON)
            .body("{\"productId\":" + productId + ",\"quantity\":" + quantity + "}")
        .when().post("/warehouse/opening-balances").then().statusCode(201);
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

    // ---------- POST: happy path --------------------------------------------------

    @Test
    void create_withoutFleetUnit_returns201WithEtagAndStockDecreased() {
        int productId = seedProduct("ZTEST_WD Happy");
        int workerId = seedWorker("ZTESTW300");
        String token = login("admin", "Admin1234");
        seedOpeningBalance(productId, "10", token);

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

        assertStock(productId, "7", token);   // 10 - 3
    }

    @Test
    void create_withTractor_returns201WithFleetUnitEmbedded() {
        int productId = seedProduct("ZTEST_WD Tractor");
        int workerId = seedWorker("ZTESTW301");
        int tractorId = seedTractor("ZT0001", true);
        String token = login("admin", "Admin1234");
        seedOpeningBalance(productId, "10", token);

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
        int productId = seedProduct("ZTEST_WD Escort");
        int workerId = seedWorker("ZTESTW302");
        int escortId = seedEscortVehicle("ZT0002", true);
        String token = login("admin", "Admin1234");
        seedOpeningBalance(productId, "10", token);

        given().header("Authorization", "Bearer " + token).contentType(ContentType.JSON)
            .body("{\"productId\":" + productId + ",\"quantity\":1,\"receivedByWorkerId\":" + workerId
                + ",\"escortVehicleId\":" + escortId + "}")
        .when().post("/warehouse/withdrawals")
        .then().statusCode(201).body("fleetUnit.kind", equalTo("ESCORT"));
    }

    @Test
    void create_withTrailer_returns201WithTrailerKind() {
        int productId = seedProduct("ZTEST_WD Trailer");
        int workerId = seedWorker("ZTESTW315");
        int trailerId = seedTrailer("ZT0009", true);
        String token = login("admin", "Admin1234");
        seedOpeningBalance(productId, "10", token);

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
        int productId = seedProduct("ZTEST_WD Exact");
        int workerId = seedWorker("ZTESTW303");
        String token = login("admin", "Admin1234");
        seedOpeningBalance(productId, "5", token);

        given().header("Authorization", "Bearer " + token).contentType(ContentType.JSON)
            .body("{\"productId\":" + productId + ",\"quantity\":5,\"receivedByWorkerId\":" + workerId + "}")
        .when().post("/warehouse/withdrawals").then().statusCode(201);

        assertStock(productId, "0", token);
    }

    // ---------- POST: WH-001 (stock insuficiente) --------------------------------

    @Test
    void create_quantityExceedsStock_returns409_WH001() {
        int productId = seedProduct("ZTEST_WD Exceso");
        int workerId = seedWorker("ZTESTW304");
        String token = login("admin", "Admin1234");
        seedOpeningBalance(productId, "4", token);

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
            int productId = seedProduct("ZTEST_WD Concurrente " + i);
            int workerId = seedWorker("ZTESTWC" + i);
            seedOpeningBalance(productId, "5", token);   // alcanza para UN retiro de 3, no dos

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
                assertStock(productId, "2", token);   // 5 - 3: el retiro perdedor no descontó
            } finally {
                pool.shutdownNow();
            }
        }
    }

    @Test
    void create_twoSequentialWithdrawalsExceedingStock_secondReturns409_WH001() {
        int productId = seedProduct("ZTEST_WD Secuencial");
        int workerId = seedWorker("ZTESTW305");
        String token = login("admin", "Admin1234");
        seedOpeningBalance(productId, "5", token);

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
        int productId = seedProduct("ZTEST_WD DosUnidades");
        int workerId = seedWorker("ZTESTW306");
        int tractorId = seedTractor("ZT0003", true);
        int trailerId = seedTrailer("ZT0004", true);
        String token = login("admin", "Admin1234");
        seedOpeningBalance(productId, "10", token);

        given().header("Authorization", "Bearer " + token).contentType(ContentType.JSON)
            .body("{\"productId\":" + productId + ",\"quantity\":1,\"receivedByWorkerId\":" + workerId
                + ",\"tractorId\":" + tractorId + ",\"trailerId\":" + trailerId + "}")
        .when().post("/warehouse/withdrawals")
        .then().statusCode(400).body("code", equalTo("WH-005"));
    }

    @Test
    void create_allThreeUnits_returns400_WH005() {
        int productId = seedProduct("ZTEST_WD TresUnidades");
        int workerId = seedWorker("ZTESTW307");
        int tractorId = seedTractor("ZT0005", true);
        int trailerId = seedTrailer("ZT0006", true);
        int escortId = seedEscortVehicle("ZT0007", true);
        String token = login("admin", "Admin1234");
        seedOpeningBalance(productId, "10", token);

        given().header("Authorization", "Bearer " + token).contentType(ContentType.JSON)
            .body("{\"productId\":" + productId + ",\"quantity\":1,\"receivedByWorkerId\":" + workerId
                + ",\"tractorId\":" + tractorId + ",\"trailerId\":" + trailerId + ",\"escortVehicleId\":" + escortId + "}")
        .when().post("/warehouse/withdrawals")
        .then().statusCode(400).body("code", equalTo("WH-005"));
    }

    // ---------- POST: WH-004 (FK inexistente/inactiva) ---------------------------

    @Test
    void create_nonexistentProduct_returns400_WH004() {
        int workerId = seedWorker("ZTESTW308");
        String token = login("admin", "Admin1234");
        given().header("Authorization", "Bearer " + token).contentType(ContentType.JSON)
            .body("{\"productId\":999999,\"quantity\":1,\"receivedByWorkerId\":" + workerId + "}")
        .when().post("/warehouse/withdrawals").then().statusCode(400).body("code", equalTo("WH-004"));
    }

    @Test
    void create_nonexistentWorker_returns400_WH004() {
        int productId = seedProduct("ZTEST_WD NoWorker");
        String token = login("admin", "Admin1234");
        seedOpeningBalance(productId, "10", token);
        given().header("Authorization", "Bearer " + token).contentType(ContentType.JSON)
            .body("{\"productId\":" + productId + ",\"quantity\":1,\"receivedByWorkerId\":999999}")
        .when().post("/warehouse/withdrawals").then().statusCode(400).body("code", equalTo("WH-004"));
    }

    @Test
    void create_inactiveWorker_returns400_WH004() {
        int productId = seedProduct("ZTEST_WD WorkerInactivo");
        int workerId = seedWorker("ZTESTW309", false);
        String token = login("admin", "Admin1234");
        seedOpeningBalance(productId, "10", token);
        given().header("Authorization", "Bearer " + token).contentType(ContentType.JSON)
            .body("{\"productId\":" + productId + ",\"quantity\":1,\"receivedByWorkerId\":" + workerId + "}")
        .when().post("/warehouse/withdrawals").then().statusCode(400).body("code", equalTo("WH-004"));
    }

    @Test
    void create_nonexistentTractor_returns400_WH004() {
        int productId = seedProduct("ZTEST_WD NoTractor");
        int workerId = seedWorker("ZTESTW310");
        String token = login("admin", "Admin1234");
        seedOpeningBalance(productId, "10", token);
        given().header("Authorization", "Bearer " + token).contentType(ContentType.JSON)
            .body("{\"productId\":" + productId + ",\"quantity\":1,\"receivedByWorkerId\":" + workerId + ",\"tractorId\":999999}")
        .when().post("/warehouse/withdrawals").then().statusCode(400).body("code", equalTo("WH-004"));
    }

    @Test
    void create_inactiveTractor_returns400_WH004() {
        int productId = seedProduct("ZTEST_WD TractorInactivo");
        int workerId = seedWorker("ZTESTW311");
        int tractorId = seedTractor("ZT0008", false);
        String token = login("admin", "Admin1234");
        seedOpeningBalance(productId, "10", token);
        given().header("Authorization", "Bearer " + token).contentType(ContentType.JSON)
            .body("{\"productId\":" + productId + ",\"quantity\":1,\"receivedByWorkerId\":" + workerId + ",\"tractorId\":" + tractorId + "}")
        .when().post("/warehouse/withdrawals").then().statusCode(400).body("code", equalTo("WH-004"));
    }

    @Test
    void create_inactiveTrailer_returns400_WH004() {
        int productId = seedProduct("ZTEST_WD TrailerInactivo");
        int workerId = seedWorker("ZTESTW316");
        int trailerId = seedTrailer("ZT0010", false);
        String token = login("admin", "Admin1234");
        seedOpeningBalance(productId, "10", token);
        given().header("Authorization", "Bearer " + token).contentType(ContentType.JSON)
            .body("{\"productId\":" + productId + ",\"quantity\":1,\"receivedByWorkerId\":" + workerId + ",\"trailerId\":" + trailerId + "}")
        .when().post("/warehouse/withdrawals").then().statusCode(400).body("code", equalTo("WH-004"));
    }

    // ---------- POST: validación (COM-001) ---------------------------------------

    @Test
    void create_missingReceivedByWorkerId_returns400_COM001() {
        int productId = seedProduct("ZTEST_WD SinWorker");
        String token = login("admin", "Admin1234");
        seedOpeningBalance(productId, "10", token);
        given().header("Authorization", "Bearer " + token).contentType(ContentType.JSON)
            .body("{\"productId\":" + productId + ",\"quantity\":1}")
        .when().post("/warehouse/withdrawals").then().statusCode(400).body("code", equalTo("COM-001"));
    }

    @Test
    void create_quantityZero_returns400_COM001() {
        int productId = seedProduct("ZTEST_WD QtyCero");
        int workerId = seedWorker("ZTESTW312");
        String token = login("admin", "Admin1234");
        seedOpeningBalance(productId, "10", token);
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
        int productId = seedProduct("ZTEST_WD RoleWK");
        int workerId = seedWorker("ZTESTW313");
        String token = login("admin", "Admin1234");
        seedOpeningBalance(productId, "10", token);
        given().header("Authorization", "Bearer " + fabricateTokenForUser(adminId(), "wk_test", "warehouse_keeper"))
            .contentType(ContentType.JSON)
            .body("{\"productId\":" + productId + ",\"quantity\":1,\"receivedByWorkerId\":" + workerId + "}")
        .when().post("/warehouse/withdrawals").then().statusCode(201);
    }

    @Test
    void create_withFinanceManagerRole_returns201() {
        int productId = seedProduct("ZTEST_WD RoleFM");
        int workerId = seedWorker("ZTESTW314");
        String token = login("admin", "Admin1234");
        seedOpeningBalance(productId, "10", token);
        given().header("Authorization", "Bearer " + fabricateTokenForUser(adminId(), "fm_test", "finance_manager"))
            .contentType(ContentType.JSON)
            .body("{\"productId\":" + productId + ",\"quantity\":1,\"receivedByWorkerId\":" + workerId + "}")
        .when().post("/warehouse/withdrawals").then().statusCode(201);
    }
}
