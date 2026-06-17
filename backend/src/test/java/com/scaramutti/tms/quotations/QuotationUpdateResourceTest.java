package com.scaramutti.tms.quotations;

import com.scaramutti.tms.shared.entity.Currency;
import com.scaramutti.tms.shared.repository.CurrencyRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.smallrye.jwt.build.Jwt;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.matchesRegex;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Integration tests de PUT /quotations/{id} (updateQuotation): edicion completa con
 * optimistic locking (If-Match → 412 COM-004), campos inmutables (quotationType/clientId
 * → 400 QUO-004), replace de items, 404, validacion reusada y matriz de roles.
 *
 * <p>Flujo realista de cada happy path: POST (crear) → GET (capturar ETag) → PUT con
 * ese If-Match. Reusa el patron de QuotationResourceTest (helpers, cleanup ZTEST_,
 * profile anti-dup deshabilitado para crear fixtures sin chocar con la ventana de 30s).
 */
@QuarkusTest
@TestProfile(QuotationUpdateResourceTest.AntiDupDisabledProfile.class)
class QuotationUpdateResourceTest {

    public static class AntiDupDisabledProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("app.quotations.anti-duplicate-window-seconds", "0");
        }
    }

    @Inject EntityManager entityManager;
    @Inject CurrencyRepository currencyRepository;

    private static final int CLIENT_ID = 1;
    private static final int CURRENCY_ID = 1;
    private static final int PAYMENT_TERM_ID = 1;
    private static final int ST_SCB = 1;
    private static final int ST_SPL = 3;
    private static final int ST_CES = 18;
    private static final int ST_INT = 24;
    private static final String TEST_CURRENCY_CODE = "ZTC";   // throwaway propia (code CHAR(3)), no toca el id=1 compartido

    @AfterEach
    void cleanupQuotations() {
        QuarkusTransaction.requiringNew().run(() -> {
            entityManager.createNativeQuery(
                "DELETE FROM cotizaciones.quotations "
                + "WHERE contact_name LIKE 'ZTEST_%' "
                + "   OR origin LIKE 'ZTEST_%' "
                + "   OR destination LIKE 'ZTEST_%'"
            ).executeUpdate();
            // La currency throwaway se borra DESPUES de las quotations (FK currency_id). Inocuo
            // si no existe (la mayoria de los tests no la crean). Garantiza limpieza aunque el
            // test falle a mitad — no muta ni envenena el id=1 compartido.
            entityManager.createNativeQuery(
                "DELETE FROM public.currencies WHERE code = :code"
            ).setParameter("code", TEST_CURRENCY_CODE).executeUpdate();
        });
    }

    // ---------- Helpers ------------------------------------------------------

    private String loginAdmin() {
        return given().contentType(ContentType.JSON)
            .body("{\"username\":\"admin\",\"password\":\"Admin1234\"}")
        .when().post("/auth/login")
        .then().statusCode(200).extract().jsonPath().getString("token");
    }

    private String loginSales() {
        return given().contentType(ContentType.JSON)
            .body("{\"username\":\"lcampos\",\"password\":\"Sales1234\"}")
        .when().post("/auth/login")
        .then().statusCode(200).extract().jsonPath().getString("token");
    }

    private String fabricateAccessToken(String username, String role) {
        Instant now = Instant.now();
        return Jwt.subject("999").upn(username).groups(Set.of(role))
            .claim("typ", "access").issuedAt(now).expiresAt(now.plusSeconds(3600)).sign();
    }

    private long createQuotation(String token, String body) {
        return given().header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON).body(body)
        .when().post("/quotations")
        .then().statusCode(201).extract().jsonPath().getLong("id");
    }

    private String getEtag(String token, long id) {
        return given().header("Authorization", "Bearer " + token)
        .when().get("/quotations/" + id)
        .then().statusCode(200).extract().header("ETag");
    }

    /** Body TRANSPORTE base: 1 item root SCB. `contact`/`validity`/`unitPrice` parametrizables. */
    private String transporteBody(String contact, int validityDays, String unitPrice) {
        return String.format("""
            {
              "quotationType": "TRANSPORTE",
              "clientId": %d,
              "contactName": "%s",
              "currencyId": %d,
              "paymentTermId": %d,
              "validityDays": %d,
              "origin": "ZTEST_LIMA",
              "destination": "ZTEST_AREQUIPA",
              "items": [
                { "serviceTypeId": %d, "cargoTypeId": 1, "weightKg": 10.00, "quantity": 1, "unitPrice": %s }
              ]
            }
            """, CLIENT_ID, contact, CURRENCY_ID, PAYMENT_TERM_ID, validityDays, ST_SCB, unitPrice);
    }

    private String baseTransporte() {
        return transporteBody("ZTEST_CONTACT", 15, "1000.00");
    }

    /**
     * Crea una currency throwaway PROPIA (code ZTC) con el isActive dado y devuelve su id. Patron
     * de CurrenciesResourceTest: NO muta el id=1 compartido, asi un fallo no envenena otros tests.
     * flush() fuerza el INSERT (IDENTITY) para leer el id generado dentro de la tx.
     */
    private int seedTestCurrency(boolean active) {
        Integer[] idHolder = new Integer[1];
        QuarkusTransaction.requiringNew().run(() -> {
            Currency currency = new Currency();
            currency.code = TEST_CURRENCY_CODE;
            currency.symbol = "*";
            currency.name = "ZTEST Throwaway Currency";
            currency.isActive = active;
            currencyRepository.persist(currency);
            currencyRepository.flush();
            idHolder[0] = currency.id;
        });
        return idHolder[0];
    }

    /** Activa/desactiva la currency dada (commit en tx propia) para simular un catalogo retirado. */
    private void setTestCurrencyActive(int currencyId, boolean active) {
        QuarkusTransaction.requiringNew().run(() ->
            entityManager.createNativeQuery(
                "UPDATE public.currencies SET is_active = :active WHERE id = :id"
            ).setParameter("active", active).setParameter("id", currencyId).executeUpdate());
    }

    /** Fuerza el status en BD (bypassa la maquina) para fabricar terminales en una tx propia. */
    private void forceStatusInDb(long id, String status) {
        QuarkusTransaction.requiringNew().run(() ->
            entityManager.createNativeQuery(
                "UPDATE cotizaciones.quotations SET status = :status WHERE id = :id")
            .setParameter("status", status).setParameter("id", id).executeUpdate());
    }

    /** Reemplaza el currencyId del body (match key-specific) por el dado. */
    private String withCurrency(String body, int currencyId) {
        return body.replace("\"currencyId\": " + CURRENCY_ID, "\"currencyId\": " + currencyId);
    }

    // ---------- Happy paths --------------------------------------------------

    @Test
    void update_headerFields_returns200_withNewValuesAndChangedEtag() {
        String token = loginAdmin();
        long id = createQuotation(token, baseTransporte());
        String etag = getEtag(token, id);

        given().header("Authorization", "Bearer " + token).header("If-Match", etag)
            .contentType(ContentType.JSON).body(transporteBody("ZTEST_UPD_NUEVO", 30, "1000.00"))
        .when().put("/quotations/" + id)
        .then().statusCode(200)
            .body("contactName", equalTo("ZTEST_UPD_NUEVO"))
            .body("validityDays", equalTo(30))
            .header("ETag", notNullValue())
            .header("ETag", not(equalTo(etag)));   // updatedAt cambio
    }

    @Test
    void update_addRootItem_returns200_withRecalculatedTotals() {
        String token = loginAdmin();
        long id = createQuotation(token, baseTransporte());   // total 1180
        String etag = getEtag(token, id);

        String body = String.format("""
            {
              "quotationType": "TRANSPORTE", "clientId": %d, "contactName": "ZTEST_2ITEMS",
              "currencyId": %d, "validityDays": 15, "origin": "ZTEST_O", "destination": "ZTEST_D",
              "items": [
                { "serviceTypeId": %d, "cargoTypeId": 1, "weightKg": 10, "quantity": 1, "unitPrice": 1000.00 },
                { "serviceTypeId": %d, "cargoTypeId": 1, "weightKg": 10, "quantity": 1, "unitPrice": 1500.00 }
              ]
            }
            """, CLIENT_ID, CURRENCY_ID, ST_SCB, ST_SPL);

        given().header("Authorization", "Bearer " + token).header("If-Match", etag)
            .contentType(ContentType.JSON).body(body)
        .when().put("/quotations/" + id)
        .then().statusCode(200)
            .body("items.size()", equalTo(2))
            .body("totalSubtotal", equalTo(2500.00f))
            .body("totalIgv", equalTo(450.00f))
            .body("totalAmount", equalTo(2950.00f));
    }

    @Test
    void update_removeRootItems_returns200_itemsReplaced() {
        String token = loginAdmin();
        // crear con 2 items
        String createBody = String.format("""
            {
              "quotationType": "TRANSPORTE", "clientId": %d, "contactName": "ZTEST_MULTI",
              "currencyId": %d, "validityDays": 15, "origin": "ZTEST_O", "destination": "ZTEST_D",
              "items": [
                { "serviceTypeId": %d, "cargoTypeId": 1, "weightKg": 10, "quantity": 1, "unitPrice": 1000.00 },
                { "serviceTypeId": %d, "cargoTypeId": 1, "weightKg": 10, "quantity": 1, "unitPrice": 1500.00 }
              ]
            }
            """, CLIENT_ID, CURRENCY_ID, ST_SCB, ST_SPL);
        long id = createQuotation(token, createBody);
        String etag = getEtag(token, id);

        // editar a 1 solo item
        given().header("Authorization", "Bearer " + token).header("If-Match", etag)
            .contentType(ContentType.JSON).body(baseTransporte())
        .when().put("/quotations/" + id)
        .then().statusCode(200)
            .body("items.size()", equalTo(1))
            .body("totalSubtotal", equalTo(1000.00f));
    }

    @Test
    void update_modifyItemPrice_returns200_recalculated() {
        String token = loginAdmin();
        long id = createQuotation(token, baseTransporte());
        String etag = getEtag(token, id);

        given().header("Authorization", "Bearer " + token).header("If-Match", etag)
            .contentType(ContentType.JSON)
            .body(String.format("""
                {
                  "quotationType": "TRANSPORTE", "clientId": %d, "contactName": "ZTEST_PRICE",
                  "currencyId": %d, "validityDays": 15, "origin": "ZTEST_O", "destination": "ZTEST_D",
                  "items": [
                    { "serviceTypeId": %d, "cargoTypeId": 1, "weightKg": 10, "quantity": 2, "unitPrice": 2000.00 }
                  ]
                }
                """, CLIENT_ID, CURRENCY_ID, ST_SCB))
        .when().put("/quotations/" + id)
        .then().statusCode(200)
            .body("items[0].subtotal", equalTo(4000.00f))
            .body("totalSubtotal", equalTo(4000.00f))
            .body("totalIgv", equalTo(720.00f));
    }

    @Test
    void update_removeStandby_returns200_standbyNull() {
        String token = loginAdmin();
        String withStandby = String.format("""
            {
              "quotationType": "TRANSPORTE", "clientId": %d, "contactName": "ZTEST_STBY",
              "currencyId": %d, "validityDays": 15, "origin": "ZTEST_O", "destination": "ZTEST_D",
              "items": [
                { "serviceTypeId": %d, "cargoTypeId": 1, "weightKg": 12, "quantity": 1, "unitPrice": 1500.00,
                  "standby": { "pricePerDay": 200.00, "includesIgv": false } }
              ]
            }
            """, CLIENT_ID, CURRENCY_ID, ST_SCB);
        long id = createQuotation(token, withStandby);
        String etag = getEtag(token, id);

        // editar sin standby → el viejo se borra en el replace
        given().header("Authorization", "Bearer " + token).header("If-Match", etag)
            .contentType(ContentType.JSON).body(baseTransporte())
        .when().put("/quotations/" + id)
        .then().statusCode(200)
            .body("items[0].standby", equalTo(null));
    }

    @Test
    void update_integral_returns200_withChildrenReplaced() {
        String token = loginAdmin();
        String integral = String.format("""
            {
              "quotationType": "TRANSPORTE", "clientId": %d, "contactName": "ZTEST_INT",
              "currencyId": %d, "validityDays": 15, "origin": "ZTEST_O", "destination": "ZTEST_D",
              "items": [
                { "itemNumber": 1, "serviceTypeId": %d, "quantity": 1, "unitPrice": 8000.00 },
                { "itemNumber": 2, "parentItemNumber": 1, "serviceTypeId": %d, "cargoTypeId": 1, "weightKg": 25, "quantity": 1, "unitPrice": 0, "internalReferencePrice": 5000.00 },
                { "itemNumber": 3, "parentItemNumber": 1, "serviceTypeId": %d, "quantity": 1, "unitPrice": 0, "internalReferencePrice": 1500.00 }
              ]
            }
            """, CLIENT_ID, CURRENCY_ID, ST_INT, ST_SCB, ST_CES);
        long id = createQuotation(token, integral);
        String etag = getEtag(token, id);

        given().header("Authorization", "Bearer " + token).header("If-Match", etag)
            .contentType(ContentType.JSON).body(integral)   // mismo integral (replace)
        .when().put("/quotations/" + id)
        .then().statusCode(200)
            .body("items.size()", equalTo(1))
            .body("items[0].serviceType.code", equalTo("INT"))
            .body("items[0].children.size()", equalTo(2));
    }

    @Test
    void update_realisticFlow_getThenPut_etagRotates() {
        String token = loginAdmin();
        long id = createQuotation(token, baseTransporte());
        String etag0 = getEtag(token, id);

        String etag1 = given().header("Authorization", "Bearer " + token).header("If-Match", etag0)
            .contentType(ContentType.JSON).body(transporteBody("ZTEST_ROT", 15, "1000.00"))
        .when().put("/quotations/" + id)
        .then().statusCode(200).extract().header("ETag");

        org.junit.jupiter.api.Assertions.assertNotEquals(etag0, etag1, "el ETag debe rotar tras editar");
        // el GET posterior devuelve el ETag nuevo
        given().header("Authorization", "Bearer " + token)
        .when().get("/quotations/" + id)
        .then().statusCode(200).header("ETag", equalTo(etag1));
    }

    // ---------- Optimistic locking (412 COM-004) -----------------------------

    @Test
    void update_staleIfMatch_returns412_COM004() {
        String token = loginAdmin();
        long id = createQuotation(token, baseTransporte());
        String etag0 = getEtag(token, id);

        // primer PUT consume etag0 (ahora la version avanzo)
        given().header("Authorization", "Bearer " + token).header("If-Match", etag0)
            .contentType(ContentType.JSON).body(transporteBody("ZTEST_FIRST", 15, "1000.00"))
        .when().put("/quotations/" + id).then().statusCode(200);

        // segundo PUT con el etag viejo (stale) → 412
        given().header("Authorization", "Bearer " + token).header("If-Match", etag0)
            .contentType(ContentType.JSON).body(transporteBody("ZTEST_SECOND", 15, "1000.00"))
        .when().put("/quotations/" + id)
        .then().statusCode(412)
            .contentType("application/problem+json")
            .body("code", equalTo("COM-004"))
            .body("status", equalTo(412));
    }

    @Test
    void update_missingIfMatch_returns412_COM004() {
        String token = loginAdmin();
        long id = createQuotation(token, baseTransporte());

        given().header("Authorization", "Bearer " + token)   // sin If-Match
            .contentType(ContentType.JSON).body(baseTransporte())
        .when().put("/quotations/" + id)
        .then().statusCode(412).body("code", equalTo("COM-004"));
    }

    @Test
    void update_malformedIfMatch_returns412_COM004() {
        String token = loginAdmin();
        long id = createQuotation(token, baseTransporte());

        given().header("Authorization", "Bearer " + token).header("If-Match", "garbage-no-etag")
            .contentType(ContentType.JSON).body(baseTransporte())
        .when().put("/quotations/" + id)
        .then().statusCode(412).body("code", equalTo("COM-004"));
    }

    // ---------- Inmutables (400 QUO-004) -------------------------------------

    @Test
    void update_changingQuotationType_returns400_QUO004() {
        String token = loginAdmin();
        long id = createQuotation(token, baseTransporte());
        String etag = getEtag(token, id);

        String alquiler = String.format("""
            {
              "quotationType": "ALQUILER", "clientId": %d, "contactName": "ZTEST_TYPE",
              "currencyId": %d, "validityDays": 30,
              "items": [ { "serviceTypeId": 9, "quantity": 1, "unitPrice": 500.00 } ]
            }
            """, CLIENT_ID, CURRENCY_ID);

        given().header("Authorization", "Bearer " + token).header("If-Match", etag)
            .contentType(ContentType.JSON).body(alquiler)
        .when().put("/quotations/" + id)
        .then().statusCode(400).body("code", equalTo("QUO-004"));
    }

    @Test
    void update_changingClientId_returns400_QUO004() {
        String token = loginAdmin();
        long id = createQuotation(token, baseTransporte());
        String etag = getEtag(token, id);

        String otherClient = baseTransporte().replace("\"clientId\": " + CLIENT_ID, "\"clientId\": 2");

        given().header("Authorization", "Bearer " + token).header("If-Match", etag)
            .contentType(ContentType.JSON).body(otherClient)
        .when().put("/quotations/" + id)
        .then().statusCode(400).body("code", equalTo("QUO-004"));
    }

    @Test
    void update_preservesCodeCreatedBy_andSetsUpdatedBy() {
        String adminToken = loginAdmin();
        long id = createQuotation(adminToken, baseTransporte());
        // capturar code/createdBy/createdAt originales
        var detail = given().header("Authorization", "Bearer " + adminToken)
        .when().get("/quotations/" + id).then().statusCode(200).extract();
        String code0 = detail.jsonPath().getString("code");
        String createdAt0 = detail.jsonPath().getString("createdAt");
        String etag = detail.header("ETag");

        // editar con SALES (lcampos) → updatedBy cambia, createdBy se preserva
        String salesToken = loginSales();
        given().header("Authorization", "Bearer " + salesToken).header("If-Match", etag)
            .contentType(ContentType.JSON).body(transporteBody("ZTEST_PRES", 20, "1000.00"))
        .when().put("/quotations/" + id)
        .then().statusCode(200)
            .body("code", equalTo(code0))                    // code inmutable
            .body("status", equalTo("DRAFT"))                // status preservado
            .body("createdBy.username", equalTo("admin"))    // createdBy original
            .body("updatedBy.username", equalTo("lcampos"))  // updatedBy = editor
            .body("createdAt", equalTo(createdAt0));         // createdAt inmutable
    }

    // ---------- Terminalidad (409 QUO-006) -----------------------------------

    @Test
    void update_acceptedQuotation_returns409_QUO006() {
        String token = loginAdmin();
        long id = createQuotation(token, baseTransporte());
        forceStatusInDb(id, "ACCEPTED");
        String etag = getEtag(token, id);

        given().header("Authorization", "Bearer " + token).header("If-Match", etag)
            .contentType(ContentType.JSON).body(transporteBody("ZTEST_TERMACC", 30, "1000.00"))
        .when().put("/quotations/" + id)
        .then().statusCode(409)
            .contentType("application/problem+json")
            .body("code", equalTo("QUO-006"))
            .body("detail", equalTo("No se puede editar una cotizacion en estado ACCEPTED"));
    }

    @Test
    void update_rejectedQuotation_returns409_QUO006() {
        String token = loginAdmin();
        long id = createQuotation(token, baseTransporte());
        forceStatusInDb(id, "REJECTED");
        String etag = getEtag(token, id);

        given().header("Authorization", "Bearer " + token).header("If-Match", etag)
            .contentType(ContentType.JSON).body(transporteBody("ZTEST_TERMREJ", 30, "1000.00"))
        .when().put("/quotations/" + id)
        .then().statusCode(409).body("code", equalTo("QUO-006"));
    }

    @Test
    void update_expiredQuotation_returns409_QUO006() {
        String token = loginAdmin();
        long id = createQuotation(token, baseTransporte());
        forceStatusInDb(id, "EXPIRED");
        String etag = getEtag(token, id);

        given().header("Authorization", "Bearer " + token).header("If-Match", etag)
            .contentType(ContentType.JSON).body(transporteBody("ZTEST_TERMEXP", 30, "1000.00"))
        .when().put("/quotations/" + id)
        .then().statusCode(409).body("code", equalTo("QUO-006"));
    }

    @Test
    void update_draftQuotation_stillEditable_returns200() {
        // Regresion: DRAFT no es terminal, sigue editable.
        String token = loginAdmin();
        long id = createQuotation(token, baseTransporte());   // nace DRAFT
        String etag = getEtag(token, id);

        given().header("Authorization", "Bearer " + token).header("If-Match", etag)
            .contentType(ContentType.JSON).body(transporteBody("ZTEST_DRAFTOK", 20, "1000.00"))
        .when().put("/quotations/" + id)
        .then().statusCode(200).body("status", equalTo("DRAFT"));
    }

    @Test
    void update_sentQuotation_stillEditable_returns200() {
        // Regresion: SENT no es terminal, sigue editable (preserva el status SENT).
        String token = loginAdmin();
        long id = createQuotation(token, baseTransporte());
        forceStatusInDb(id, "SENT");
        String etag = getEtag(token, id);

        given().header("Authorization", "Bearer " + token).header("If-Match", etag)
            .contentType(ContentType.JSON).body(transporteBody("ZTEST_SENTOK", 20, "1000.00"))
        .when().put("/quotations/" + id)
        .then().statusCode(200).body("status", equalTo("SENT"));
    }

    // ---------- 404 ----------------------------------------------------------

    @Test
    void update_nonExistentId_returns404_QUO003() {
        String token = loginAdmin();
        given().header("Authorization", "Bearer " + token).header("If-Match", "\"x\"")
            .contentType(ContentType.JSON).body(baseTransporte())
        .when().put("/quotations/999999999")
        .then().statusCode(404)
            .contentType("application/problem+json")
            .body("code", equalTo("QUO-003"));
    }

    // ---------- Validacion ---------------------------------------------------

    @Test
    void update_transporteWithoutOrigin_returns400_COM001() {
        String token = loginAdmin();
        long id = createQuotation(token, baseTransporte());
        String etag = getEtag(token, id);

        String noOrigin = String.format("""
            {
              "quotationType": "TRANSPORTE", "clientId": %d, "contactName": "ZTEST_NOORI",
              "currencyId": %d, "validityDays": 15, "destination": "ZTEST_D",
              "items": [ { "serviceTypeId": %d, "quantity": 1, "unitPrice": 100.00 } ]
            }
            """, CLIENT_ID, CURRENCY_ID, ST_SCB);

        given().header("Authorization", "Bearer " + token).header("If-Match", etag)
            .contentType(ContentType.JSON).body(noOrigin)
        .when().put("/quotations/" + id)
        .then().statusCode(400).body("code", equalTo("COM-001"));
    }

    @Test
    void update_emptyBody_returns400() {
        String token = loginAdmin();
        long id = createQuotation(token, baseTransporte());
        String etag = getEtag(token, id);

        given().header("Authorization", "Bearer " + token).header("If-Match", etag)
            .contentType(ContentType.JSON)
        .when().put("/quotations/" + id)
        .then().statusCode(400);
    }

    @Test
    void update_moreThan5RootItems_returns400_COM001() {
        String token = loginAdmin();
        long id = createQuotation(token, baseTransporte());
        String etag = getEtag(token, id);

        StringBuilder items = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            if (i > 0) items.append(",");
            items.append(String.format("{ \"serviceTypeId\": %d, \"cargoTypeId\": 1, \"weightKg\": 10, \"quantity\": 1, \"unitPrice\": 100.00 }", ST_SCB));
        }
        String body = String.format("""
            {
              "quotationType": "TRANSPORTE", "clientId": %d, "contactName": "ZTEST_6",
              "currencyId": %d, "validityDays": 15, "origin": "ZTEST_O", "destination": "ZTEST_D",
              "items": [%s]
            }
            """, CLIENT_ID, CURRENCY_ID, items);

        given().header("Authorization", "Bearer " + token).header("If-Match", etag)
            .contentType(ContentType.JSON).body(body)
        .when().put("/quotations/" + id)
        .then().statusCode(400).body("code", equalTo("COM-001"));
    }

    // ---------- Catalogo desactivado despues de crear (decision: bloquear) ----

    @Test
    void update_whenCurrencyWasDeactivatedAfterCreation_returns400_COM001() {
        // Decision 2026-06-05: el editar valida isActive de las FKs (reusa loadFor del create).
        // Si un catalogo se DESACTIVA despues de crear la cotizacion, editarla —aunque MANTENGA el
        // mismo currency, sin cambiarlo— devuelve 400 COM-001. Asimetrico A PROPOSITO con el GET,
        // que SI la sigue mostrando (loadByIds no chequea isActive). Ver UpdateQuotationService paso 4.
        // Usa una currency throwaway propia (ZTC): se crea ACTIVA, se cotiza, se DESACTIVA y se
        // edita manteniendola. No muta el id=1 compartido; el @AfterEach la borra tras las quotations.
        String token = loginAdmin();
        int currencyId = seedTestCurrency(true);                       // throwaway ACTIVA al crear
        long id = createQuotation(token, withCurrency(baseTransporte(), currencyId));
        String etag = getEtag(token, id);

        setTestCurrencyActive(currencyId, false);                      // se desactiva DESPUES de crear

        given().header("Authorization", "Bearer " + token).header("If-Match", etag)
            .contentType(ContentType.JSON)
            .body(withCurrency(transporteBody("ZTEST_DEACT", 30, "1500.00"), currencyId))
        .when().put("/quotations/" + id)
        .then().statusCode(400)
            .body("code", equalTo("COM-001"))
            .body("detail", equalTo("currencyId esta inactivo"));
    }

    // ---------- Auth / Roles -------------------------------------------------

    @Test
    void update_withoutToken_returns401() {
        given().header("If-Match", "\"x\"").contentType(ContentType.JSON).body(baseTransporte())
        .when().put("/quotations/1")
        .then().statusCode(401);
    }

    @Test
    void update_withDispatcherRole_returns403_COM003() {
        String token = fabricateAccessToken("disp_test", "dispatcher");
        given().header("Authorization", "Bearer " + token).header("If-Match", "\"x\"")
            .contentType(ContentType.JSON).body(baseTransporte())
        .when().put("/quotations/1")
        .then().statusCode(403).body("code", equalTo("COM-003"));
    }

    @Test
    void update_withSalesRole_returns200() {
        String adminToken = loginAdmin();
        long id = createQuotation(adminToken, baseTransporte());
        String salesToken = loginSales();
        String etag = getEtag(salesToken, id);

        given().header("Authorization", "Bearer " + salesToken).header("If-Match", etag)
            .contentType(ContentType.JSON).body(transporteBody("ZTEST_SALES", 15, "1000.00"))
        .when().put("/quotations/" + id)
        .then().statusCode(200);
    }
}
