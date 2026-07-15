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

import static io.restassured.RestAssured.given;
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
                "DELETE FROM almacen.audit_logs WHERE entity_type = 'WITHDRAWAL' AND entity_id IN "
                    + "(SELECT id FROM almacen.withdrawals WHERE product_id IN "
                    + "(SELECT id FROM almacen.products WHERE name LIKE ?1))")
                .setParameter(1, TEST_NAME_PREFIX + "%").executeUpdate();
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

    /** Retiro CANCELLED sembrado por SQL nativo (para el detalle de un retiro ya anulado). */
    private int seedCancelledWithdrawal(int productId, String quantity, int receivedBy) {
        return QuarkusTransaction.requiringNew().call(() -> ((Number) entityManager.createNativeQuery(
            "INSERT INTO almacen.withdrawals (product_id, quantity, received_by, registered_by, status, "
                + "cancel_reason, cancelled_by, cancelled_at) "
                + "VALUES (?1, CAST(?2 AS NUMERIC), ?3, ?4, 'CANCELLED', 'ZTEST anulado antes del test', ?4, CURRENT_TIMESTAMP) "
                + "RETURNING id")
            .setParameter(1, productId).setParameter(2, quantity)
            .setParameter(3, receivedBy).setParameter(4, adminId())
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
        int productId = seedProduct("ZTEST_WD Get");
        int workerId = seedWorker("ZTESTW400");
        String token = login("admin", "Admin1234");
        seedOpeningBalance(productId, "10", token);
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
        int productId = seedProduct("ZTEST_WD GetTractor");
        int workerId = seedWorker("ZTESTW401");
        int tractorId = seedTractor("ZT0400", true);
        String token = login("admin", "Admin1234");
        seedOpeningBalance(productId, "10", token);
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
        int productId = seedProduct("ZTEST_WD GetCancelled");
        int workerId = seedWorker("ZTESTW402");
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
}
