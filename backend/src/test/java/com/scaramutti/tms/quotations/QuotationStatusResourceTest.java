package com.scaramutti.tms.quotations;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import com.scaramutti.tms.quotations.service.QuotationExpiryJob;
import com.scaramutti.tms.shared.repository.QuotationRepository;
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
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests de PATCH /quotations/{id}/status (updateQuotationStatus): maquina de
 * estados (ADR-004), motivo de rechazo (ADR-007), optimistic locking (If-Match → 412),
 * terminalidad, roles, isExpired derivado del estado (true sii EXPIRED) y filtro ?status.
 *
 * <p>Flujo de cada caso: POST (crea DRAFT) → GET (captura ETag) → PATCH. Para fabricar
 * estados que el PATCH no puede alcanzar desde DRAFT en un paso (terminales) o cotizaciones
 * vencidas, se fuerzan en BD con {@code forceStatusInDb}/{@code forceCreatedAtInDb} en una
 * tx propia. Cleanup por {@code ZTEST_} en contactName/origin/destination.
 */
@QuarkusTest
@TestProfile(QuotationStatusResourceTest.AntiDupDisabledProfile.class)
class QuotationStatusResourceTest {

    public static class AntiDupDisabledProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("app.quotations.anti-duplicate-window-seconds", "0");
        }
    }

    @Inject EntityManager entityManager;
    @Inject QuotationRepository quotationRepository;
    @Inject QuotationExpiryJob quotationExpiryJob;

    private static final int CLIENT_ID = 1;
    private static final int CURRENCY_ID = 1;
    private static final int PAYMENT_TERM_ID = 1;
    private static final int ST_SCB = 1;

    @AfterEach
    void cleanupQuotations() {
        QuarkusTransaction.requiringNew().run(() ->
            entityManager.createNativeQuery(
                "DELETE FROM cotizaciones.quotations "
                + "WHERE contact_name LIKE 'ZTEST_%' "
                + "   OR origin LIKE 'ZTEST_%' "
                + "   OR destination LIKE 'ZTEST_%'"
            ).executeUpdate());
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

    /**
     * Token con un {@code subject} = id de un usuario REAL seedeado (asi {@code updatedBy}
     * resuelve la FK al persistir), pero con el {@code role} dado en los groups (drivea
     * {@code @RolesAllowed}). Necesario para probar 200 con roles sin usuario propio en dev
     * (general_manager / operations_manager): el rol importa para el guard, el subject para la FK.
     */
    private String fabricateTokenForRealUser(int userId, String username, String role) {
        Instant now = Instant.now();
        return Jwt.subject(String.valueOf(userId)).upn(username).groups(Set.of(role))
            .claim("typ", "access").issuedAt(now).expiresAt(now.plusSeconds(3600)).sign();
    }

    private long createQuotation(String token, String contact) {
        return given().header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON).body(transporteBody(contact))
        .when().post("/quotations")
        .then().statusCode(201).extract().jsonPath().getLong("id");
    }

    private String getEtag(String token, long id) {
        return given().header("Authorization", "Bearer " + token)
        .when().get("/quotations/" + id)
        .then().statusCode(200).extract().header("ETag");
    }

    private String transporteBody(String contact) {
        return String.format("""
            {
              "quotationType": "TRANSPORTE",
              "clientId": %d,
              "contactName": "%s",
              "currencyId": %d,
              "paymentTermId": %d,
              "validityDays": 15,
              "origin": "ZTEST_LIMA",
              "destination": "ZTEST_AREQUIPA",
              "items": [
                { "serviceTypeId": %d, "cargoTypeId": 1, "weightKg": 10.00, "quantity": 1, "unitPrice": 1000.00 }
              ]
            }
            """, CLIENT_ID, contact, CURRENCY_ID, PAYMENT_TERM_ID, ST_SCB);
    }

    /** Fuerza el status en BD (bypassa la maquina) para fabricar terminales/SENT en una tx propia. */
    private void forceStatusInDb(long id, String status) {
        QuarkusTransaction.requiringNew().run(() ->
            entityManager.createNativeQuery(
                "UPDATE cotizaciones.quotations SET status = :status WHERE id = :id")
            .setParameter("status", status).setParameter("id", id).executeUpdate());
    }

    /** Antiguo created_at (now - daysAgo dias) para fabricar cotizaciones cuya validez ya paso. */
    private void forceCreatedAtInDb(long id, int daysAgo) {
        QuarkusTransaction.requiringNew().run(() ->
            entityManager.createNativeQuery(
                "UPDATE cotizaciones.quotations SET created_at = now() - (:days || ' days')::interval WHERE id = :id")
            .setParameter("days", String.valueOf(daysAgo)).setParameter("id", id).executeUpdate());
    }

    /** Lee el status persistido directo de BD (para verificar el efecto del job). */
    private String statusInDb(long id) {
        String[] holder = new String[1];
        QuarkusTransaction.requiringNew().run(() ->
            holder[0] = (String) entityManager.createNativeQuery(
                "SELECT status FROM cotizaciones.quotations WHERE id = :id")
            .setParameter("id", id).getSingleResult());
        return holder[0];
    }

    /** Lee el updated_at persistido (para verificar que el job NO lo toca). */
    private java.time.OffsetDateTime updatedAtInDb(long id) {
        java.time.OffsetDateTime[] holder = new java.time.OffsetDateTime[1];
        QuarkusTransaction.requiringNew().run(() -> {
            Object v = entityManager.createNativeQuery(
                "SELECT updated_at FROM cotizaciones.quotations WHERE id = :id")
                .setParameter("id", id).getSingleResult();
            if (v instanceof java.time.OffsetDateTime odt) holder[0] = odt;
            else if (v instanceof java.time.Instant inst) holder[0] = inst.atOffset(java.time.ZoneOffset.UTC);
            else if (v instanceof java.sql.Timestamp ts) holder[0] = ts.toInstant().atOffset(java.time.ZoneOffset.UTC);
            else throw new IllegalStateException("Unexpected updated_at type: " + v.getClass().getName());
        });
        return holder[0];
    }

    private io.restassured.response.Response patchStatus(String token, long id, String etag, String body) {
        var req = given().header("Authorization", "Bearer " + token).contentType(ContentType.JSON).body(body);
        if (etag != null) req = req.header("If-Match", etag);
        return req.when().patch("/quotations/" + id + "/status");
    }

    // =========================================================================
    // Transiciones validas
    // =========================================================================

    @Test
    void patch_draftToSent_returns200_statusSent_etagRotates() {
        String token = loginAdmin();
        long id = createQuotation(token, "ZTEST_D2S");
        String etag = getEtag(token, id);

        patchStatus(token, id, etag, "{\"status\":\"SENT\"}")
        .then().statusCode(200)
            .body("status", equalTo("SENT"))
            .header("ETag", notNullValue())
            .header("ETag", not(equalTo(etag)));
    }

    @Test
    void patch_sentToAccepted_returns200() {
        String token = loginAdmin();
        long id = createQuotation(token, "ZTEST_S2A");
        forceStatusInDb(id, "SENT");
        String etag = getEtag(token, id);

        patchStatus(token, id, etag, "{\"status\":\"ACCEPTED\"}")
        .then().statusCode(200)
            .body("status", equalTo("ACCEPTED"))
            .body("rejectionReason", equalTo(null));
    }

    @Test
    void patch_sentToRejected_withReason_returns200_persistsReason() {
        String token = loginAdmin();
        long id = createQuotation(token, "ZTEST_S2R");
        forceStatusInDb(id, "SENT");
        String etag = getEtag(token, id);

        patchStatus(token, id, etag, "{\"status\":\"REJECTED\",\"rejectionReason\":\"Eligio otra propuesta\"}")
        .then().statusCode(200)
            .body("status", equalTo("REJECTED"))
            .body("rejectionReason", equalTo("Eligio otra propuesta"));
    }

    // =========================================================================
    // Transiciones invalidas → QUO-005
    // =========================================================================

    @Test
    void patch_draftToAccepted_returns409_QUO005() {
        String token = loginAdmin();
        long id = createQuotation(token, "ZTEST_D2ACC");
        String etag = getEtag(token, id);

        patchStatus(token, id, etag, "{\"status\":\"ACCEPTED\"}")
        .then().statusCode(409)
            .contentType("application/problem+json")
            .body("code", equalTo("QUO-005"))
            .body("detail", equalTo("No se puede pasar de DRAFT a ACCEPTED"));
    }

    @Test
    void patch_draftToRejected_returns409_QUO005() {
        String token = loginAdmin();
        long id = createQuotation(token, "ZTEST_D2REJ");
        String etag = getEtag(token, id);

        patchStatus(token, id, etag, "{\"status\":\"REJECTED\",\"rejectionReason\":\"x\"}")
        .then().statusCode(409).body("code", equalTo("QUO-005"));
    }

    @Test
    void patch_sentToSent_returns409_QUO005_selfTransition() {
        String token = loginAdmin();
        long id = createQuotation(token, "ZTEST_S2S");
        forceStatusInDb(id, "SENT");
        String etag = getEtag(token, id);

        patchStatus(token, id, etag, "{\"status\":\"SENT\"}")
        .then().statusCode(409).body("code", equalTo("QUO-005"));
    }

    @Test
    void patch_outOfAccepted_returns409_QUO005() {
        String token = loginAdmin();
        long id = createQuotation(token, "ZTEST_OUTACC");
        forceStatusInDb(id, "ACCEPTED");
        String etag = getEtag(token, id);

        patchStatus(token, id, etag, "{\"status\":\"SENT\"}")
        .then().statusCode(409).body("code", equalTo("QUO-005"));
    }

    @Test
    void patch_outOfRejected_returns409_QUO005() {
        String token = loginAdmin();
        long id = createQuotation(token, "ZTEST_OUTREJ");
        forceStatusInDb(id, "REJECTED");
        String etag = getEtag(token, id);

        patchStatus(token, id, etag, "{\"status\":\"ACCEPTED\"}")
        .then().statusCode(409).body("code", equalTo("QUO-005"));
    }

    @Test
    void patch_outOfExpired_returns409_QUO005() {
        String token = loginAdmin();
        long id = createQuotation(token, "ZTEST_OUTEXP");
        forceStatusInDb(id, "EXPIRED");
        String etag = getEtag(token, id);

        patchStatus(token, id, etag, "{\"status\":\"SENT\"}")
        .then().statusCode(409).body("code", equalTo("QUO-005"));
    }

    @Test
    void patch_userRequestsExpired_returns409_QUO005_notUserDestination() {
        String token = loginAdmin();
        long id = createQuotation(token, "ZTEST_UEXP");
        forceStatusInDb(id, "SENT");
        String etag = getEtag(token, id);

        // EXPIRED no es destino de usuario (solo el job) → el body enum del contrato no lo
        // permite; el backend igual lo rechaza con QUO-005 si llega.
        patchStatus(token, id, etag, "{\"status\":\"EXPIRED\"}")
        .then().statusCode(409).body("code", equalTo("QUO-005"));
    }

    // =========================================================================
    // Motivo de rechazo (ADR-007): obligatorio Y exclusivo
    // =========================================================================

    @Test
    void patch_rejectedWithoutReason_returns400_COM001() {
        String token = loginAdmin();
        long id = createQuotation(token, "ZTEST_REJNOR");
        forceStatusInDb(id, "SENT");
        String etag = getEtag(token, id);

        patchStatus(token, id, etag, "{\"status\":\"REJECTED\"}")
        .then().statusCode(400).body("code", equalTo("COM-001"));
    }

    @Test
    void patch_rejectedWithBlankReason_returns400_COM001() {
        String token = loginAdmin();
        long id = createQuotation(token, "ZTEST_REJBLANK");
        forceStatusInDb(id, "SENT");
        String etag = getEtag(token, id);

        patchStatus(token, id, etag, "{\"status\":\"REJECTED\",\"rejectionReason\":\"   \"}")
        .then().statusCode(400).body("code", equalTo("COM-001"));
    }

    @Test
    void patch_acceptedWithReason_returns400_COM001_reasonExclusiveToRejected() {
        String token = loginAdmin();
        long id = createQuotation(token, "ZTEST_ACCR");
        forceStatusInDb(id, "SENT");
        String etag = getEtag(token, id);

        patchStatus(token, id, etag, "{\"status\":\"ACCEPTED\",\"rejectionReason\":\"no deberia\"}")
        .then().statusCode(400).body("code", equalTo("COM-001"));
    }

    @Test
    void patch_sentWithReason_returns400_COM001() {
        String token = loginAdmin();
        long id = createQuotation(token, "ZTEST_SENTR");
        String etag = getEtag(token, id);

        patchStatus(token, id, etag, "{\"status\":\"SENT\",\"rejectionReason\":\"huerfano\"}")
        .then().statusCode(400).body("code", equalTo("COM-001"));
    }

    @Test
    void patch_rejectedWithControlCharReason_returns400() {
        String token = loginAdmin();
        long id = createQuotation(token, "ZTEST_REJCTRL");
        forceStatusInDb(id, "SENT");
        String etag = getEtag(token, id);

        // \u0000 (NUL) es caracter de control → lo bloquea el @Pattern del DTO (400).
        patchStatus(token, id, etag, "{\"status\":\"REJECTED\",\"rejectionReason\":\"bad\\u0000char\"}")
        .then().statusCode(400);
    }

    @Test
    void patch_rejectedWithReasonOver500_returns400() {
        String token = loginAdmin();
        long id = createQuotation(token, "ZTEST_REJ501");
        forceStatusInDb(id, "SENT");
        String etag = getEtag(token, id);

        String reason = "a".repeat(501);
        patchStatus(token, id, etag, "{\"status\":\"REJECTED\",\"rejectionReason\":\"" + reason + "\"}")
        .then().statusCode(400);
    }

    @Test
    void patch_rejectedWithReasonExactly500_returns200() {
        String token = loginAdmin();
        long id = createQuotation(token, "ZTEST_REJ500");
        forceStatusInDb(id, "SENT");
        String etag = getEtag(token, id);

        String reason = "a".repeat(500);
        patchStatus(token, id, etag, "{\"status\":\"REJECTED\",\"rejectionReason\":\"" + reason + "\"}")
        .then().statusCode(200)
            .body("status", equalTo("REJECTED"))
            .body("rejectionReason.length()", equalTo(500));
    }

    @Test
    void patch_rejectedWithPaddedReason_returns200_persistedTrimmed() {
        String token = loginAdmin();
        long id = createQuotation(token, "ZTEST_REJTRIM");
        forceStatusInDb(id, "SENT");
        String etag = getEtag(token, id);

        patchStatus(token, id, etag, "{\"status\":\"REJECTED\",\"rejectionReason\":\"   motivo con espacios   \"}")
        .then().statusCode(200)
            .body("rejectionReason", equalTo("motivo con espacios"));
    }

    // =========================================================================
    // Optimistic locking (412 COM-004)
    // =========================================================================

    @Test
    void patch_staleIfMatch_returns412_COM004() {
        String token = loginAdmin();
        long id = createQuotation(token, "ZTEST_STALE");
        String etag0 = getEtag(token, id);

        // primer PATCH consume etag0 (la version avanza)
        patchStatus(token, id, etag0, "{\"status\":\"SENT\"}").then().statusCode(200);

        // segundo PATCH con el etag viejo → 412
        patchStatus(token, id, etag0, "{\"status\":\"ACCEPTED\"}")
        .then().statusCode(412)
            .contentType("application/problem+json")
            .body("code", equalTo("COM-004"));
    }

    @Test
    void patch_missingIfMatch_returns412_COM004() {
        String token = loginAdmin();
        long id = createQuotation(token, "ZTEST_NOIM");

        patchStatus(token, id, null, "{\"status\":\"SENT\"}")
        .then().statusCode(412).body("code", equalTo("COM-004"));
    }

    // =========================================================================
    // 404 (precede al If-Match)
    // =========================================================================

    @Test
    void patch_nonExistentId_returns404_QUO003_precedesIfMatch() {
        String token = loginAdmin();
        // Sin If-Match valido: igual debe dar 404 (el 404 precede al If-Match).
        patchStatus(token, 999999999L, "\"x\"", "{\"status\":\"SENT\"}")
        .then().statusCode(404)
            .contentType("application/problem+json")
            .body("code", equalTo("QUO-003"));
    }

    // =========================================================================
    // Validacion del body
    // =========================================================================

    @Test
    void patch_emptyBody_returns400() {
        String token = loginAdmin();
        long id = createQuotation(token, "ZTEST_EMPTY");
        String etag = getEtag(token, id);

        given().header("Authorization", "Bearer " + token).header("If-Match", etag)
            .contentType(ContentType.JSON)
        .when().patch("/quotations/" + id + "/status")
        .then().statusCode(400);
    }

    @Test
    void patch_invalidStatusValue_returns400() {
        String token = loginAdmin();
        long id = createQuotation(token, "ZTEST_BADSTATUS");
        String etag = getEtag(token, id);

        patchStatus(token, id, etag, "{\"status\":\"BANANA\"}")
        .then().statusCode(400);
    }

    // =========================================================================
    // Roles
    // =========================================================================

    @Test
    void patch_withoutToken_returns401() {
        given().header("If-Match", "\"x\"").contentType(ContentType.JSON).body("{\"status\":\"SENT\"}")
        .when().patch("/quotations/1/status")
        .then().statusCode(401);
    }

    @Test
    void patch_withDispatcherRole_returns403_COM003() {
        String token = fabricateAccessToken("disp_test", "dispatcher");
        given().header("Authorization", "Bearer " + token).header("If-Match", "\"x\"")
            .contentType(ContentType.JSON).body("{\"status\":\"SENT\"}")
        .when().patch("/quotations/1/status")
        .then().statusCode(403).body("code", equalTo("COM-003"));
    }

    @Test
    void patch_withSalesRole_returns200() {
        String adminToken = loginAdmin();
        long id = createQuotation(adminToken, "ZTEST_SALES");
        String salesToken = loginSales();
        String etag = getEtag(salesToken, id);

        patchStatus(salesToken, id, etag, "{\"status\":\"SENT\"}")
        .then().statusCode(200).body("status", equalTo("SENT"));
    }

    @Test
    void patch_withGeneralManagerRole_returns200() {
        String adminToken = loginAdmin();
        long id = createQuotation(adminToken, "ZTEST_GM");
        // subject=1 (admin, usuario real → FK updatedBy resuelve), role=general_manager (guard).
        String gmToken = fabricateTokenForRealUser(1, "admin", "general_manager");
        String etag = getEtag(gmToken, id);

        patchStatus(gmToken, id, etag, "{\"status\":\"SENT\"}")
        .then().statusCode(200);
    }

    @Test
    void patch_withOperationsManagerRole_returns200() {
        String adminToken = loginAdmin();
        long id = createQuotation(adminToken, "ZTEST_OM");
        String omToken = fabricateTokenForRealUser(1, "admin", "operations_manager");
        String etag = getEtag(omToken, id);

        patchStatus(omToken, id, etag, "{\"status\":\"SENT\"}")
        .then().statusCode(200);
    }

    // =========================================================================
    // isExpired derivado del estado (ADR-005): true sii status == EXPIRED
    // =========================================================================

    @Test
    void getExpired_statusExpired_isExpiredTrue() {
        // EXPIRED es el UNICO estado con isExpired=true (lo deja el job, no el read-path).
        String token = loginAdmin();
        long id = createQuotation(token, "ZTEST_EXPIRED");
        forceStatusInDb(id, "EXPIRED");

        given().header("Authorization", "Bearer " + token)
        .when().get("/quotations/" + id)
        .then().statusCode(200)
            .body("status", equalTo("EXPIRED"))
            .body("isExpired", equalTo(true));
    }

    @Test
    void getExpired_sentPastValidity_isExpiredFalse_untilJobRuns() {
        // Cambio de semantica (ADR-005): una SENT vencida sigue isExpired=false hasta que
        // el QuotationExpiryJob la pase a EXPIRED (ventana ≤24h). Ya NO se computa por fechas.
        String token = loginAdmin();
        long id = createQuotation(token, "ZTEST_EXPSENT");   // validityDays=15
        forceStatusInDb(id, "SENT");
        forceCreatedAtInDb(id, 20);                          // creada hace 20 dias → vencida por fecha

        given().header("Authorization", "Bearer " + token)
        .when().get("/quotations/" + id)
        .then().statusCode(200)
            .body("status", equalTo("SENT"))
            .body("isExpired", equalTo(false));
    }

    @Test
    void getExpired_draftPastValidity_isExpiredFalse() {
        String token = loginAdmin();
        long id = createQuotation(token, "ZTEST_EXPDRAFT");   // queda DRAFT
        forceCreatedAtInDb(id, 20);                           // vieja pero no EXPIRED

        given().header("Authorization", "Bearer " + token)
        .when().get("/quotations/" + id)
        .then().statusCode(200)
            .body("status", equalTo("DRAFT"))
            .body("isExpired", equalTo(false));
    }

    @Test
    void getExpired_terminalAcceptedPastValidity_isExpiredFalse() {
        String token = loginAdmin();
        long id = createQuotation(token, "ZTEST_EXPTERM");
        forceStatusInDb(id, "ACCEPTED");   // terminal != EXPIRED
        forceCreatedAtInDb(id, 20);        // vieja, pero isExpired solo mira status

        given().header("Authorization", "Bearer " + token)
        .when().get("/quotations/" + id)
        .then().statusCode(200)
            .body("status", equalTo("ACCEPTED"))
            .body("isExpired", equalTo(false));
    }

    // =========================================================================
    // Filtro ?status (los 5 valores)
    // =========================================================================

    @Test
    void list_filterByAccepted_returnsOnlyAccepted() {
        String token = loginAdmin();
        long id = createQuotation(token, "ZTEST_FACC");
        forceStatusInDb(id, "ACCEPTED");

        given().header("Authorization", "Bearer " + token)
        .when().get("/quotations?status=ACCEPTED&size=100")
        .then().statusCode(200)
            .body("totalElements", greaterThanOrEqualTo(1))
            .body("content.findAll { it.status != 'ACCEPTED' }.size()", equalTo(0));
    }

    @Test
    void list_filterByRejected_returnsOnlyRejected() {
        String token = loginAdmin();
        long id = createQuotation(token, "ZTEST_FREJ");
        forceStatusInDb(id, "REJECTED");

        given().header("Authorization", "Bearer " + token)
        .when().get("/quotations?status=REJECTED&size=100")
        .then().statusCode(200)
            .body("content.findAll { it.status != 'REJECTED' }.size()", equalTo(0));
    }

    @Test
    void list_filterByExpired_returnsOnlyExpired() {
        String token = loginAdmin();
        long id = createQuotation(token, "ZTEST_FEXP");
        forceStatusInDb(id, "EXPIRED");

        given().header("Authorization", "Bearer " + token)
        .when().get("/quotations?status=EXPIRED&size=100")
        .then().statusCode(200)
            .body("content.findAll { it.status != 'EXPIRED' }.size()", equalTo(0));
    }

    // =========================================================================
    // Job de vencimiento (QuotationExpiryJob / expireSentQuotations)
    // Invoca el metodo del repo/job directo (sin esperar el @Scheduled).
    // =========================================================================

    @Test
    void expiryJob_sentPastValidity_markedExpired() {
        String token = loginAdmin();
        long id = createQuotation(token, "ZTEST_JOBSENT");   // validityDays=15
        forceStatusInDb(id, "SENT");
        forceCreatedAtInDb(id, 20);                          // vencida hace 5 dias
        java.time.OffsetDateTime before = updatedAtInDb(id);

        int expired = quotationRepository.expireSentQuotations();

        assertTrue(expired >= 1, "debe marcar al menos la SENT vencida fabricada");
        assertEquals("EXPIRED", statusInDb(id));
        // El job NO toca updated_at (la expiracion es del sistema, no un cambio de usuario).
        assertEquals(before, updatedAtInDb(id),
            "el bulk update no debe disparar @PreUpdate ni tocar updated_at");
    }

    @Test
    void expiryJob_viaScheduledMethod_marksExpired() {
        // Mismo efecto invocando el metodo del job directamente (no espera al cron).
        String token = loginAdmin();
        long id = createQuotation(token, "ZTEST_JOBRUN");
        forceStatusInDb(id, "SENT");
        forceCreatedAtInDb(id, 20);

        quotationExpiryJob.expireQuotations();

        assertEquals("EXPIRED", statusInDb(id));
    }

    @Test
    void expiryJob_doesNotTouchDraftSentFreshOrTerminal() {
        String token = loginAdmin();
        // DRAFT vencida por fecha: NO vence (solo SENT).
        long draft = createQuotation(token, "ZTEST_JOBDRAFT");
        forceCreatedAtInDb(draft, 20);
        // SENT fresca (vigente): NO vence.
        long sentFresh = createQuotation(token, "ZTEST_JOBFRESH");
        forceStatusInDb(sentFresh, "SENT");                 // created_at = hoy → vigente
        // ACCEPTED vencida por fecha: terminal, NO vence.
        long accepted = createQuotation(token, "ZTEST_JOBACC");
        forceStatusInDb(accepted, "ACCEPTED");
        forceCreatedAtInDb(accepted, 20);
        // EXPIRED ya: idempotente, sigue EXPIRED.
        long alreadyExpired = createQuotation(token, "ZTEST_JOBEXP");
        forceStatusInDb(alreadyExpired, "EXPIRED");
        forceCreatedAtInDb(alreadyExpired, 20);

        quotationRepository.expireSentQuotations();

        assertEquals("DRAFT", statusInDb(draft));
        assertEquals("SENT", statusInDb(sentFresh));
        assertEquals("ACCEPTED", statusInDb(accepted));
        assertEquals("EXPIRED", statusInDb(alreadyExpired));
    }

    @Test
    void expiryJob_idempotent_secondRunMarksZero() {
        String token = loginAdmin();
        long id = createQuotation(token, "ZTEST_JOBIDEM");
        forceStatusInDb(id, "SENT");
        forceCreatedAtInDb(id, 20);

        int first = quotationRepository.expireSentQuotations();
        assertTrue(first >= 1, "1ra corrida marca la SENT vencida");
        assertEquals("EXPIRED", statusInDb(id));

        // 2da corrida: ya no quedan SENT vencidas (los tests corren en serie) → 0 filas.
        int second = quotationRepository.expireSentQuotations();
        assertEquals(0, second, "la 2da corrida es idempotente (nada nuevo que vencer)");
        assertEquals("EXPIRED", statusInDb(id));
    }
}
