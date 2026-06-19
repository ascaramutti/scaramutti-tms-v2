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
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;

/**
 * Read-side de las condiciones (US-005): {@code QuotationResponse.conditions[]} en GET, en el
 * response de crear y en el de editar. Verifica el contrato de lectura (ADR-010, RN-04/05):
 * ordenadas por {@code displayOrder} ASC; incluye TODAS las linkeadas — activas E inactivas
 * (snapshot historico); lista vacia (no null) si no hay.
 *
 * <p>Seed: 3 condiciones con marcador {@code ZTEST_COND_*} y displayOrder 91001 &lt; 91002 &lt;
 * 91003, con la INACTIVA en el medio (91002) — asi un solo caso prueba orden + inclusion de
 * inactivas. Anti-duplicate deshabilitado para crear varias fixtures. Cleanup: cotizaciones
 * {@code ZTEST_} primero (cascada borra la junction), luego las condiciones de prueba.
 */
@QuarkusTest
@TestProfile(QuotationConditionsResponseResourceTest.AntiDupDisabledProfile.class)
class QuotationConditionsResponseResourceTest {

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
    private static final String ACTIVE_TEXT_2 = "ZTEST_COND_ACTIVE_2";
    private static final String INACTIVE_TEXT = "ZTEST_COND_INACTIVE";

    private Integer activeId;     // displayOrder 91001
    private Integer activeId2;    // displayOrder 91003
    private Integer inactiveId;   // displayOrder 91002 (en el medio)

    @BeforeEach
    void seedConditions() {
        QuarkusTransaction.requiringNew().run(() -> {
            conditionRepository.delete("text like ?1", "ZTEST_COND_%");
            Condition active = newCondition(ACTIVE_TEXT, 91001, true);
            Condition active2 = newCondition(ACTIVE_TEXT_2, 91003, true);
            Condition inactive = newCondition(INACTIVE_TEXT, 91002, false);
            conditionRepository.persist(active);
            conditionRepository.persist(active2);
            conditionRepository.persist(inactive);
            conditionRepository.flush();
            activeId = active.id;
            activeId2 = active2.id;
            inactiveId = inactive.id;
        });
    }

    @AfterEach
    void cleanup() {
        QuarkusTransaction.requiringNew().run(() -> {
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

    private int createWith(String token, String conditionIdsJson) {
        return given().header("Authorization", "Bearer " + token).contentType(ContentType.JSON)
            .body(bodyWithConditions(conditionIdsJson))
        .when().post("/quotations")
        .then().statusCode(201).extract().jsonPath().getInt("id");
    }

    private String getEtag(String token, int id) {
        return given().header("Authorization", "Bearer " + token)
        .when().get("/quotations/" + id)
        .then().statusCode(200).extract().header("ETag");
    }

    /** Desactiva/activa una condicion del catalogo en tx propia (simula retiro tras linkear). */
    private void setConditionActive(int conditionId, boolean active) {
        QuarkusTransaction.requiringNew().run(() ->
            entityManager.createNativeQuery(
                "UPDATE cotizaciones.conditions SET is_active = :a WHERE id = :id")
            .setParameter("a", active).setParameter("id", conditionId).executeUpdate());
    }

    /** Linkea una condicion directo por SQL (saltea la validacion de escritura que rechaza inactivas). */
    private void linkConditionDirect(int quotationId, int conditionId) {
        QuarkusTransaction.requiringNew().run(() ->
            entityManager.createNativeQuery(
                "INSERT INTO cotizaciones.quotation_conditions (quotation_id, condition_id) VALUES (:q, :c)")
            .setParameter("q", (long) quotationId).setParameter("c", conditionId).executeUpdate());
    }

    // ---------- GET ------------------------------------------------------------

    @Test
    void get_returnsConditions_orderedByDisplayOrderAsc() {
        String token = loginAdmin();
        // Mando los ids en orden INVERSO al displayOrder: el orden del response debe venir del
        // displayOrder (91001, 91003), no del orden de insercion.
        int id = createWith(token, "[" + activeId2 + "," + activeId + "]");

        given().header("Authorization", "Bearer " + token)
        .when().get("/quotations/" + id)
        .then().statusCode(200)
            .body("conditions.size()", equalTo(2))
            .body("conditions.id", contains(activeId, activeId2))
            .body("conditions[0].displayOrder", equalTo(91001))
            .body("conditions[1].displayOrder", equalTo(91003))
            .body("conditions[0].isActive", equalTo(true))
            .body("conditions[1].isActive", equalTo(true));
    }

    @Test
    void get_includesCondition_deactivatedAfterLinking_withIsActiveFalse() {
        String token = loginAdmin();
        int id = createWith(token, "[" + activeId + "]");

        // La condicion se desactiva en el catalogo DESPUES de quedar linkeada.
        setConditionActive(activeId, false);

        given().header("Authorization", "Bearer " + token)
        .when().get("/quotations/" + id)
        .then().statusCode(200)
            // El read NO filtra por isActive: sigue apareciendo (snapshot, RN-05).
            .body("conditions.size()", equalTo(1))
            .body("conditions[0].id", equalTo(activeId))
            .body("conditions[0].isActive", equalTo(false))
            .body("conditions[0].text", equalTo(ACTIVE_TEXT));   // texto = snapshot del catalogo
    }

    @Test
    void get_returnsActiveAndInactive_orderedWithCorrectFlags() {
        String token = loginAdmin();
        int id = createWith(token, "[]");
        // Linkeo las 3 directo (la inactiva no pasaria la validacion de escritura).
        linkConditionDirect(id, activeId);     // 91001
        linkConditionDirect(id, inactiveId);   // 91002 (inactiva, en el medio)
        linkConditionDirect(id, activeId2);    // 91003

        given().header("Authorization", "Bearer " + token)
        .when().get("/quotations/" + id)
        .then().statusCode(200)
            .body("conditions.size()", equalTo(3))
            .body("conditions.id", contains(activeId, inactiveId, activeId2))
            .body("conditions[1].isActive", equalTo(false))   // la del medio (91002)
            .body("conditions[0].isActive", equalTo(true))
            .body("conditions[2].isActive", equalTo(true));
    }

    @Test
    void get_withoutConditions_returnsEmptyList() {
        String token = loginAdmin();
        int id = createWith(token, "[]");

        given().header("Authorization", "Bearer " + token)
        .when().get("/quotations/" + id)
        .then().statusCode(200)
            .body("conditions", org.hamcrest.Matchers.notNullValue())
            .body("conditions.size()", equalTo(0));
    }

    // ---------- Response de crear / editar -------------------------------------

    @Test
    void create_responseBody_includesConditions_ordered() {
        String token = loginAdmin();

        given().header("Authorization", "Bearer " + token).contentType(ContentType.JSON)
            .body(bodyWithConditions("[" + activeId2 + "," + activeId + "]"))
        .when().post("/quotations")
        .then().statusCode(201)
            .body("conditions.size()", equalTo(2))
            .body("conditions.id", contains(activeId, activeId2))   // ordenado por displayOrder
            .body("conditions[0].displayOrder", equalTo(91001));
    }

    @Test
    void update_responseBody_reflectsReplacedConditions() {
        String token = loginAdmin();
        int id = createWith(token, "[" + activeId + "]");
        String etag = getEtag(token, id);

        given().header("Authorization", "Bearer " + token).header("If-Match", etag)
            .contentType(ContentType.JSON).body(bodyWithConditions("[" + activeId2 + "]"))
        .when().put("/quotations/" + id)
        .then().statusCode(200)
            .body("conditions.size()", equalTo(1))
            .body("conditions[0].id", equalTo(activeId2));   // refleja el set tras el REPLACE
    }
}
