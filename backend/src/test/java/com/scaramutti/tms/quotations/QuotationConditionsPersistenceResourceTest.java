package com.scaramutti.tms.quotations;

import com.scaramutti.tms.shared.entity.Condition;
import com.scaramutti.tms.shared.repository.ConditionRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Persistencia de condiciones al CREAR (US-003) y EDITAR (US-004) cotizaciones.
 * Auto-siembra una condicion ACTIVA + una INACTIVA (marcadores {@code ZTEST_COND_*}) para no
 * depender de la semilla real; en {@code @AfterEach} borra las cotizaciones de prueba (cascada
 * limpia la junction) y luego las condiciones de prueba. Anti-duplicate deshabilitado (=0)
 * para crear varias fixtures sin chocar con el window de 30s.
 */
@QuarkusTest
@TestProfile(QuotationConditionsPersistenceResourceTest.AntiDupDisabledProfile.class)
class QuotationConditionsPersistenceResourceTest {

    public static class AntiDupDisabledProfile implements QuarkusTestProfile {
        @Override public Map<String, String> getConfigOverrides() {
            return Map.of("app.quotations.anti-duplicate-window-seconds", "0");
        }
    }

    @Inject EntityManager entityManager;
    @Inject ConditionRepository conditionRepository;

    private static final int CLIENT_ID = 1;
    private static final int CURRENCY_ID = 1;
    private static final int PAYMENT_TERM_ID = 1;
    private static final int ST_SCB = 1;

    private static final String ACTIVE_TEXT = "ZTEST_COND_ACTIVE";
    private static final String INACTIVE_TEXT = "ZTEST_COND_INACTIVE";

    private Integer activeId;
    private Integer inactiveId;

    @BeforeEach
    void seedConditions() {
        QuarkusTransaction.requiringNew().run(() -> {
            conditionRepository.delete("text like ?1", "ZTEST_COND_%");
            Condition active = newCondition(ACTIVE_TEXT, 91001, true);
            Condition inactive = newCondition(INACTIVE_TEXT, 91002, false);
            conditionRepository.persist(active);
            conditionRepository.persist(inactive);
            conditionRepository.flush();
            activeId = active.id;
            inactiveId = inactive.id;
        });
    }

    @AfterEach
    void cleanup() {
        QuarkusTransaction.requiringNew().run(() -> {
            // Cotizaciones de prueba primero (cascada borra sus filas de la junction),
            // recien despues las condiciones (FK sin cascada en condition_id).
            entityManager.createNativeQuery(
                "DELETE FROM cotizaciones.quotations WHERE contact_name LIKE 'ZTEST_%'"
            ).executeUpdate();
            conditionRepository.delete("text like ?1", "ZTEST_COND_%");
        });
    }

    private static Condition newCondition(String text, int order, boolean active) {
        Condition c = new Condition();
        c.text = text;
        c.displayOrder = order;
        c.isActive = active;
        return c;
    }

    private String loginAdmin() {
        return given().contentType(ContentType.JSON)
            .body("{\"username\":\"admin\",\"password\":\"Admin1234\"}")
        .when().post("/auth/login")
        .then().statusCode(200).extract().jsonPath().getString("token");
    }

    /** Body TRANSPORTE minimo con la lista de conditionIds dada (JSON array crudo, ej. "[1,3]"). */
    private String bodyWithConditions(String conditionIdsJson) {
        return String.format("""
            {
              "quotationType": "TRANSPORTE",
              "clientId": %d,
              "contactName": "ZTEST_COND",
              "currencyId": %d,
              "paymentTermId": %d,
              "validityDays": 15,
              "origin": "ZTEST_LIMA",
              "destination": "ZTEST_AREQUIPA",
              "items": [
                { "serviceTypeId": %d, "cargoTypeId": 1, "weightKg": 10.00, "quantity": 1, "unitPrice": 1000.00 }
              ],
              "conditionIds": %s
            }
            """, CLIENT_ID, CURRENCY_ID, PAYMENT_TERM_ID, ST_SCB, conditionIdsJson);
    }

    private long countJunction(int quotationId) {
        long[] holder = new long[1];
        QuarkusTransaction.requiringNew().run(() ->
            holder[0] = ((Number) entityManager.createNativeQuery(
                "SELECT count(*) FROM cotizaciones.quotation_conditions WHERE quotation_id = :qid")
                .setParameter("qid", (long) quotationId).getSingleResult()).longValue());
        return holder[0];
    }

    private boolean junctionHas(int quotationId, int conditionId) {
        long[] holder = new long[1];
        QuarkusTransaction.requiringNew().run(() ->
            holder[0] = ((Number) entityManager.createNativeQuery(
                "SELECT count(*) FROM cotizaciones.quotation_conditions WHERE quotation_id = :qid AND condition_id = :cid")
                .setParameter("qid", (long) quotationId).setParameter("cid", conditionId).getSingleResult()).longValue());
        return holder[0] == 1L;
    }

    // ---------- US-003: crear --------------------------------------------------

    @Test
    void create_withActiveConditions_returns201AndPersistsJunction() {
        String token = loginAdmin();
        int id = given().header("Authorization", "Bearer " + token).contentType(ContentType.JSON)
            .body(bodyWithConditions("[" + activeId + "]"))
        .when().post("/quotations")
        .then().statusCode(201).extract().path("id");

        assertEquals(1, countJunction(id));
        assertTrue(junctionHas(id, activeId), "la condicion activa deberia estar linkeada");
    }

    @Test
    void create_withInactiveCondition_returns409_QUO007_andNamesIt() {
        String token = loginAdmin();
        given().header("Authorization", "Bearer " + token).contentType(ContentType.JSON)
            .body(bodyWithConditions("[" + inactiveId + "]"))
        .when().post("/quotations")
        .then().statusCode(409)
            .contentType("application/problem+json")
            .body("code", equalTo("QUO-007"))
            .body("detail", containsString(INACTIVE_TEXT));
    }

    @Test
    void create_withNonexistentCondition_returns400_validation() {
        String token = loginAdmin();
        given().header("Authorization", "Bearer " + token).contentType(ContentType.JSON)
            .body(bodyWithConditions("[999999]"))
        .when().post("/quotations")
        .then().statusCode(400).body("code", equalTo("COM-001"));
    }

    @Test
    void create_withDuplicateCondition_returns400_validation() {
        String token = loginAdmin();
        given().header("Authorization", "Bearer " + token).contentType(ContentType.JSON)
            .body(bodyWithConditions("[" + activeId + "," + activeId + "]"))
        .when().post("/quotations")
        .then().statusCode(400).body("code", equalTo("COM-001"));
    }

    @Test
    void create_withTooManyConditions_returns400_validation() {
        String token = loginAdmin();
        // 21 ids distintos exceden @Size(max=20) -> 400 por bean-validation (antes del service).
        // No necesitan existir: la validacion de tamano corre antes de consultar el catalogo.
        String ids = java.util.stream.IntStream.rangeClosed(1, 21)
            .mapToObj(Integer::toString).collect(java.util.stream.Collectors.joining(","));
        given().header("Authorization", "Bearer " + token).contentType(ContentType.JSON)
            .body(bodyWithConditions("[" + ids + "]"))
        .when().post("/quotations")
        .then().statusCode(400).body("code", equalTo("COM-001"));
    }

    @Test
    void create_withEmptyConditions_returns201AndNoJunction() {
        String token = loginAdmin();
        int id = given().header("Authorization", "Bearer " + token).contentType(ContentType.JSON)
            .body(bodyWithConditions("[]"))
        .when().post("/quotations")
        .then().statusCode(201).extract().path("id");

        assertEquals(0, countJunction(id));
    }
}
