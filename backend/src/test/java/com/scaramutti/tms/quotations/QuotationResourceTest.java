package com.scaramutti.tms.quotations;

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
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.Set;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsInRelativeOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.matchesRegex;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

@QuarkusTest
@TestProfile(QuotationResourceTest.AntiDupDisabledProfile.class)
class QuotationResourceTest {

    /**
     * Deshabilita el anti-duplicate window (=0) para esta clase: los tests de
     * listado crean multiples cotizaciones identicas como fixtures y el window
     * de 30s las rechazaria con 409. El anti-duplicate se verifica aparte en
     * {@link QuotationAntiDuplicateResourceTest} (con su propio profile que lo
     * reactiva). Via @TestProfile (no application-test.properties) porque es el
     * mecanismo garantizado de override de config en QuarkusTest.
     */
    public static class AntiDupDisabledProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("app.quotations.anti-duplicate-window-seconds", "0");
        }
    }

    @Inject EntityManager entityManager;

    // IDs reales de la BD prod (compartida con dev). Verificados al armar tests:
    // - clientId 1: IPH TRANSPORTES SAC
    // - currencyId 1: USD, 2: PEN
    // - paymentTermId 1: Contado
    // - serviceTypeId 1: SCB (kind=SERVICIO/TRANSPORTE)
    // - serviceTypeId 3: SPL (kind=SERVICIO)
    // - serviceTypeId 6: SCH (kind=SERVICIO)
    // - serviceTypeId 18: CES (kind=COMPLEMENTARIO)
    // - serviceTypeId 24: INT (kind=INTEGRAL)
    private static final int CLIENT_ID = 1;
    private static final int CURRENCY_ID = 1;
    private static final int PAYMENT_TERM_ID = 1;
    private static final int ST_SCB = 1;
    private static final int ST_SPL = 3;
    private static final int ST_CES = 18;
    private static final int ST_INT = 24;

    /** Zona del negocio — los filtros de fecha del listado se interpretan aca (igual que el backend). */
    private static final ZoneId LIMA = ZoneId.of("America/Lima");

    @AfterEach
    void cleanupQuotations() {
        // Borra cotizaciones creadas por estos tests. Como dev DB es compartida
        // con prod, identificamos por ZTEST_ en cualquiera de los campos textuales:
        // contact_name, origin, destination. Cualquier fixture nuevo debe usar
        // ZTEST_ en al menos uno de esos campos.
        QuarkusTransaction.requiringNew().run(() -> {
            entityManager.createNativeQuery(
                "DELETE FROM cotizaciones.quotations "
                + "WHERE contact_name LIKE 'ZTEST_%' "
                + "   OR origin LIKE 'ZTEST_%' "
                + "   OR destination LIKE 'ZTEST_%'"
            ).executeUpdate();
        });
    }

    private String loginAdmin() {
        return given()
            .contentType(ContentType.JSON)
            .body("{\"username\":\"admin\",\"password\":\"Admin1234\"}")
        .when()
            .post("/auth/login")
        .then()
            .statusCode(200)
            .extract().jsonPath().getString("token");
    }

    private String fabricateAccessToken(String username, String role) {
        Instant now = Instant.now();
        return Jwt.subject("999")
            .upn(username)
            .groups(Set.of(role))
            .claim("typ", "access")
            .issuedAt(now)
            .expiresAt(now.plusSeconds(3600))
            .sign();
    }

    /**
     * Body minimo TRANSPORTE con 1 item root. Asume el serviceTypeId es de
     * kind=SERVICIO (transporte) — incluye weight + cargoTypeId obligatorios.
     */
    private String transporteBody(String origin, String destination, int serviceTypeId, String unitPrice) {
        return String.format("""
            {
              "quotationType": "TRANSPORTE",
              "clientId": %d,
              "contactName": "ZTEST_CONTACT",
              "currencyId": %d,
              "paymentTermId": %d,
              "validityDays": 15,
              "origin": "%s",
              "destination": "%s",
              "items": [
                {
                  "serviceTypeId": %d,
                  "cargoTypeId": 1,
                  "weightKg": 10.00,
                  "quantity": 1,
                  "unitPrice": %s
                }
              ]
            }
            """, CLIENT_ID, CURRENCY_ID, PAYMENT_TERM_ID, origin, destination, serviceTypeId, unitPrice);
    }

    // ---------- Happy path TRANSPORTE ----------------------------------------

    @Test
    void create_withValidTransporte_returns201AndPersists() {
        String token = loginAdmin();

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(transporteBody("ZTEST_LIMA", "ZTEST_AREQUIPA", ST_SCB, "1000.00"))
        .when()
            .post("/quotations")
        .then()
            .statusCode(201)
            .contentType("application/json")
            .body("id", greaterThanOrEqualTo(1))
            .body("code", matchesRegex("\\d{4}-\\d{5}"))
            .body("quotationType", equalTo("TRANSPORTE"))
            .body("status", equalTo("DRAFT"))
            .body("client.id", equalTo(CLIENT_ID))
            .body("currency.id", equalTo(CURRENCY_ID))
            .body("origin", equalTo("ZTEST_LIMA"))
            .body("destination", equalTo("ZTEST_AREQUIPA"))
            .body("items.size()", equalTo(1))
            .body("items[0].itemNumber", equalTo(1))
            .body("items[0].unitPrice", equalTo(1000.00f))
            .body("items[0].subtotal", equalTo(1000.00f))
            .body("totalSubtotal", equalTo(1000.00f))
            .body("totalIgv", equalTo(180.00f))
            .body("totalAmount", equalTo(1180.00f))
            .body("createdBy", notNullValue())
            .body("expiresAt", notNullValue())
            .body("isExpired", equalTo(false));
    }

    @Test
    void create_withEmptyBody_returns400() {
        // POST sin body — debe rechazarse en la frontera de validacion (Bean Validation)
        // ANTES de tocar el service. Sin esto, el null se propagaba y crasheaba el
        // service con NPE (regression que se observo en testing manual).
        String token = loginAdmin();

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
        .when()
            .post("/quotations")
        .then()
            .statusCode(400);
    }

    @Test
    void create_withExplicitNullBody_returns400() {
        // POST con body "null" literal — Jackson parsea como null. Mismo path
        // que el caso anterior pero por una via diferente.
        String token = loginAdmin();

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body("null")
        .when()
            .post("/quotations")
        .then()
            .statusCode(400);
    }

    @Test
    void create_withValidContactPhone_persistsAndReturnsIt() {
        // Happy path E2E del nuevo snapshot contactPhone:
        // payload con contactPhone=987654321 → 201 → response trae el mismo valor.
        String token = loginAdmin();
        String body = String.format("""
            {
              "quotationType": "TRANSPORTE",
              "clientId": %d,
              "contactName": "ZTEST_CP",
              "contactPhone": "987654321",
              "currencyId": %d,
              "paymentTermId": %d,
              "validityDays": 15,
              "origin": "ZTEST_LIMA",
              "destination": "ZTEST_AREQUIPA",
              "items": [
                { "serviceTypeId": %d, "cargoTypeId": 1, "weightKg": 10.00, "quantity": 1, "unitPrice": 1000.00 }
              ]
            }
            """, CLIENT_ID, CURRENCY_ID, PAYMENT_TERM_ID, ST_SCB);

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(body)
        .when()
            .post("/quotations")
        .then()
            .statusCode(201)
            .body("contactName", equalTo("ZTEST_CP"))
            .body("contactPhone", equalTo("987654321"));
    }

    @Test
    void create_withNotes_returns201AndEchoesThem() {
        // Round-trip de las observaciones: se envían en el body, se persisten y vuelven en el response.
        String token = loginAdmin();
        String body = String.format("""
            {
              "quotationType": "TRANSPORTE",
              "clientId": %d,
              "contactName": "ZTEST_NOTES",
              "currencyId": %d,
              "paymentTermId": %d,
              "validityDays": 15,
              "origin": "ZTEST_LIMA",
              "destination": "ZTEST_AREQUIPA",
              "clientNote": "Precio sujeto a variacion del combustible.",
              "internalNote": "Margen ajustado por volatilidad.",
              "items": [
                { "serviceTypeId": %d, "cargoTypeId": 1, "weightKg": 10.00, "quantity": 1, "unitPrice": 1000.00 }
              ]
            }
            """, CLIENT_ID, CURRENCY_ID, PAYMENT_TERM_ID, ST_SCB);

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(body)
        .when()
            .post("/quotations")
        .then()
            .statusCode(201)
            .body("clientNote", equalTo("Precio sujeto a variacion del combustible."))
            .body("internalNote", equalTo("Margen ajustado por volatilidad."));
    }

    @Test
    void create_withClientNoteTooLong_returns400() {
        // @Size(max=500) en el request: 501 chars debe rechazarse en la frontera (400), no en el service.
        String token = loginAdmin();
        String longNote = "x".repeat(501);
        String body = String.format("""
            {
              "quotationType": "TRANSPORTE",
              "clientId": %d,
              "contactName": "ZTEST_NOTELEN",
              "currencyId": %d,
              "paymentTermId": %d,
              "validityDays": 15,
              "origin": "ZTEST_LIMA",
              "destination": "ZTEST_AREQUIPA",
              "clientNote": "%s",
              "items": [
                { "serviceTypeId": %d, "cargoTypeId": 1, "weightKg": 10.00, "quantity": 1, "unitPrice": 1000.00 }
              ]
            }
            """, CLIENT_ID, CURRENCY_ID, PAYMENT_TERM_ID, longNote, ST_SCB);

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(body)
        .when()
            .post("/quotations")
        .then()
            .statusCode(400);
    }

    @Test
    void create_withInvalidContactPhone_returns400() {
        // Bean Validation @Pattern("^\\d{9}$") rechaza formatos invalidos:
        // letras, menos/mas de 9 digitos, caracteres especiales.
        String token = loginAdmin();
        String body = String.format("""
            {
              "quotationType": "TRANSPORTE",
              "clientId": %d,
              "contactName": "ZTEST_BAD_CP",
              "contactPhone": "12345",
              "currencyId": %d,
              "validityDays": 15,
              "origin": "ZTEST_LIMA",
              "destination": "ZTEST_AREQUIPA",
              "items": [
                { "serviceTypeId": %d, "quantity": 1, "unitPrice": 1000.00 }
              ]
            }
            """, CLIENT_ID, CURRENCY_ID, ST_SCB);

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(body)
        .when()
            .post("/quotations")
        .then()
            .statusCode(400);
    }

    @Test
    void create_withoutContactName_returns400() {
        // contactName es obligatorio — la cotizacion siempre se dirige a alguien.
        // Bean Validation @NotBlank rechaza null / "" / "   ".
        String token = loginAdmin();
        String body = String.format("""
            {
              "quotationType": "TRANSPORTE",
              "clientId": %d,
              "currencyId": %d,
              "paymentTermId": %d,
              "validityDays": 15,
              "origin": "ZTEST_LIMA",
              "destination": "ZTEST_AREQUIPA",
              "items": [
                { "serviceTypeId": %d, "cargoTypeId": 1, "weightKg": 10.00, "quantity": 1, "unitPrice": 1000.00 }
              ]
            }
            """, CLIENT_ID, CURRENCY_ID, PAYMENT_TERM_ID, ST_SCB);

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(body)
        .when()
            .post("/quotations")
        .then()
            .statusCode(400);
    }

    @Test
    void create_withBlankContactName_returns400() {
        // contactName="   " (solo whitespace) → 400 por @NotBlank.
        String token = loginAdmin();
        String body = String.format("""
            {
              "quotationType": "TRANSPORTE",
              "clientId": %d,
              "contactName": "   ",
              "currencyId": %d,
              "validityDays": 15,
              "origin": "ZTEST_LIMA",
              "destination": "ZTEST_AREQUIPA",
              "items": [
                { "serviceTypeId": %d, "cargoTypeId": 1, "weightKg": 10.00, "quantity": 1, "unitPrice": 1000.00 }
              ]
            }
            """, CLIENT_ID, CURRENCY_ID, ST_SCB);

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(body)
        .when()
            .post("/quotations")
        .then()
            .statusCode(400);
    }

    @Test
    void create_alquilerItemWithWeight_returns400_COM001() {
        // ALQUILER no admite medidas: si llega weight → 400 COM-001.
        // Pin del contrato HTTP de la regla validateMeasurementsAndCargoType.
        String token = loginAdmin();
        String body = String.format("""
            {
              "quotationType": "ALQUILER",
              "clientId": %d,
              "contactName": "ZTEST_AQ_WEIGHT",
              "currencyId": %d,
              "validityDays": 30,
              "items": [
                { "serviceTypeId": %d, "weightKg": 10.00, "quantity": 1, "unitPrice": 500.00 }
              ]
            }
            """, CLIENT_ID, CURRENCY_ID, 9 /* ACB - kind=ALQUILER */);

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(body)
        .when()
            .post("/quotations")
        .then()
            .statusCode(400)
            .body("code", equalTo("COM-001"));
    }

    @Test
    void create_complementarioItemWithCargoTypeId_returns400_COM001() {
        // COMPLEMENTARIO no admite cargoTypeId: si llega → 400 COM-001.
        String token = loginAdmin();
        String body = String.format("""
            {
              "quotationType": "TRANSPORTE",
              "clientId": %d,
              "contactName": "ZTEST_CES_CARGO",
              "currencyId": %d,
              "validityDays": 15,
              "origin": "ZTEST_O",
              "destination": "ZTEST_D",
              "items": [
                { "serviceTypeId": %d, "cargoTypeId": 1, "quantity": 1, "unitPrice": 200.00 }
              ]
            }
            """, CLIENT_ID, CURRENCY_ID, ST_CES);

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(body)
        .when()
            .post("/quotations")
        .then()
            .statusCode(400)
            .body("code", equalTo("COM-001"));
    }

    @Test
    void create_clientSummary_doesNotLeakClientResponseFields() {
        // Anti-Corruption Layer: el `client` embebido en QuotationResponse es
        // QuotationClientSummary (id+name+ruc), NO ClientResponse. No debe
        // tener phone, contactName, isActive ni createdAt del master del cliente.
        String token = loginAdmin();

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(transporteBody("ZTEST_ACL_O", "ZTEST_ACL_D", ST_SCB, "500.00"))
        .when()
            .post("/quotations")
        .then()
            .statusCode(201)
            // Summary expone solo subset:
            .body("client.id", notNullValue())
            .body("client.name", notNullValue())
            .body("client.ruc", notNullValue())
            // ACL: estos campos del master NO deben filtrarse al response de cotizacion.
            .body("client.phone", nullValue())
            .body("client.contactName", nullValue())
            .body("client.isActive", nullValue())
            .body("client.createdAt", nullValue());
    }

    @Test
    void create_response_includesLocationAndEtagHeaders() {
        String token = loginAdmin();

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(transporteBody("ZTEST_HDR_O", "ZTEST_HDR_D", ST_SCB, "500.00"))
        .when()
            .post("/quotations")
        .then()
            .statusCode(201)
            // Location apunta al recurso recien creado.
            .header("Location", matchesRegex(".*/api/v1/quotations/\\d+"))
            // ETag = "\"<updatedAt>\"" — source para optimistic locking en futuros PUT.
            .header("ETag", notNullValue())
            .header("ETag", matchesRegex("\".+\""));
    }

    @Test
    void create_withValidAlquiler_returns201_withoutOriginDestination() {
        String token = loginAdmin();
        String body = String.format("""
            {
              "quotationType": "ALQUILER",
              "clientId": %d,
              "contactName": "ZTEST_ALQ",
              "currencyId": %d,
              "validityDays": 30,
              "items": [
                { "serviceTypeId": %d, "quantity": 5, "unitPrice": 500.00 }
              ]
            }
            """, CLIENT_ID, CURRENCY_ID, 9 /* ACB - kind=ALQUILER */);

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(body)
        .when()
            .post("/quotations")
        .then()
            .statusCode(201)
            .body("quotationType", equalTo("ALQUILER"))
            .body("totalSubtotal", equalTo(2500.00f))
            .body("totalIgv", equalTo(450.00f))
            .body("totalAmount", equalTo(2950.00f));
    }

    // ---------- Servicio Integral --------------------------------------------

    @Test
    void create_withValidServicioIntegral_returns201_withChildrenInResponse() {
        String token = loginAdmin();
        String body = String.format("""
            {
              "quotationType": "TRANSPORTE",
              "clientId": %d,
              "contactName": "ZTEST_INT",
              "currencyId": %d,
              "validityDays": 15,
              "origin": "ZTEST_ORI",
              "destination": "ZTEST_DEST",
              "items": [
                { "itemNumber": 1, "serviceTypeId": %d, "quantity": 1, "unitPrice": 5000.00 },
                { "itemNumber": 2, "parentItemNumber": 1, "serviceTypeId": %d, "cargoTypeId": 1, "weightKg": 10.00, "quantity": 1, "unitPrice": 0, "internalReferencePrice": 3000.00 },
                { "itemNumber": 3, "parentItemNumber": 1, "serviceTypeId": %d, "quantity": 1, "unitPrice": 0, "internalReferencePrice": 1000.00 }
              ]
            }
            """, CLIENT_ID, CURRENCY_ID, ST_INT, ST_SCB, ST_CES);

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(body)
        .when()
            .post("/quotations")
        .then()
            .statusCode(201)
            // El total se computa solo del item root (Integral): 5000 + IGV.
            .body("totalSubtotal", equalTo(5000.00f))
            .body("totalIgv", equalTo(900.00f))
            .body("totalAmount", equalTo(5900.00f))
            // Items en response: el INT con children embebidos.
            .body("items.size()", equalTo(1))
            .body("items[0].serviceType.code", equalTo("INT"))
            .body("items[0].children.size()", equalTo(2));
    }

    @Test
    void create_servicioIntegralWithoutTransporteChild_returns400_COM001() {
        // Integral con 2 hijos COMPLEMENTARIOs (sin SERVICIO/TRANSPORTE) → debe rechazar.
        String token = loginAdmin();
        String body = String.format("""
            {
              "quotationType": "TRANSPORTE",
              "clientId": %d,
              "contactName": "ZTEST_INVALIDINT",
              "currencyId": %d,
              "validityDays": 15,
              "origin": "ZTEST_O",
              "destination": "ZTEST_D",
              "items": [
                { "itemNumber": 1, "serviceTypeId": %d, "quantity": 1, "unitPrice": 5000.00 },
                { "itemNumber": 2, "parentItemNumber": 1, "serviceTypeId": %d, "quantity": 1 },
                { "itemNumber": 3, "parentItemNumber": 1, "serviceTypeId": %d, "quantity": 1 }
              ]
            }
            """, CLIENT_ID, CURRENCY_ID, ST_INT, ST_CES, ST_CES);

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(body)
        .when()
            .post("/quotations")
        .then()
            .statusCode(400)
            .body("code", equalTo("COM-001"));
    }

    @Test
    void create_servicioIntegralWithLessThanTwoChildren_returns400() {
        // Integral con 1 solo hijo → debe rechazar (necesita >=2).
        String token = loginAdmin();
        String body = String.format("""
            {
              "quotationType": "TRANSPORTE",
              "clientId": %d,
              "contactName": "ZTEST_INT_1CHILD",
              "currencyId": %d,
              "validityDays": 15,
              "origin": "ZTEST_O",
              "destination": "ZTEST_D",
              "items": [
                { "itemNumber": 1, "serviceTypeId": %d, "quantity": 1, "unitPrice": 5000.00 },
                { "itemNumber": 2, "parentItemNumber": 1, "serviceTypeId": %d, "cargoTypeId": 1, "weightKg": 10.00, "quantity": 1 }
              ]
            }
            """, CLIENT_ID, CURRENCY_ID, ST_INT, ST_SCB);

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(body)
        .when()
            .post("/quotations")
        .then()
            .statusCode(400)
            .body("code", equalTo("COM-001"));
    }

    // ---------- Validation 400 -----------------------------------------------

    @Test
    void create_transporteWithoutOrigin_returns400_COM001() {
        String token = loginAdmin();
        String body = String.format("""
            {
              "quotationType": "TRANSPORTE",
              "clientId": %d,
              "contactName": "ZTEST_NOORIGIN",
              "currencyId": %d,
              "validityDays": 15,
              "destination": "ZTEST_D",
              "items": [
                { "serviceTypeId": %d, "quantity": 1, "unitPrice": 100.00 }
              ]
            }
            """, CLIENT_ID, CURRENCY_ID, ST_SCB);

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(body)
        .when()
            .post("/quotations")
        .then()
            .statusCode(400)
            .body("code", equalTo("COM-001"));
    }

    @Test
    void create_withMoreThan5RootItems_returns400_COM001() {
        String token = loginAdmin();
        StringBuilder items = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            if (i > 0) items.append(",\n");
            items.append(String.format(
                "{ \"serviceTypeId\": %d, \"quantity\": 1, \"unitPrice\": 100.00 }", ST_SCB
            ));
        }
        String body = String.format("""
            {
              "quotationType": "TRANSPORTE",
              "clientId": %d,
              "contactName": "ZTEST_TOOMANY",
              "currencyId": %d,
              "validityDays": 15,
              "origin": "ZTEST_O", "destination": "ZTEST_D",
              "items": [%s]
            }
            """, CLIENT_ID, CURRENCY_ID, items);

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(body)
        .when()
            .post("/quotations")
        .then()
            .statusCode(400)
            .body("code", equalTo("COM-001"));
    }

    @Test
    void create_withoutItems_returns400_COM001() {
        String token = loginAdmin();
        String body = String.format("""
            {
              "quotationType": "TRANSPORTE",
              "clientId": %d,
              "contactName": "ZTEST_NOITEMS",
              "currencyId": %d,
              "validityDays": 15,
              "origin": "ZTEST_O", "destination": "ZTEST_D",
              "items": []
            }
            """, CLIENT_ID, CURRENCY_ID);

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(body)
        .when()
            .post("/quotations")
        .then()
            .statusCode(400)
            .body("code", equalTo("COM-001"));
    }

    @Test
    void create_withInvalidClientId_returns400_COM001() {
        String token = loginAdmin();
        String body = transporteBody("ZTEST_L", "ZTEST_A", ST_SCB, "100.00")
            .replace("\"clientId\": " + CLIENT_ID, "\"clientId\": 999999");

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(body)
        .when()
            .post("/quotations")
        .then()
            .statusCode(400)
            .body("code", equalTo("COM-001"));
    }

    // ---------- Auth ---------------------------------------------------------

    @Test
    void create_withoutToken_returns401() {
        given()
            .contentType(ContentType.JSON)
            .body(transporteBody("ZTEST_L", "ZTEST_A", ST_SCB, "100.00"))
        .when()
            .post("/quotations")
        .then()
            .statusCode(401);
    }

    @Test
    void create_withDispatcherRole_returns403_COM003() {
        String dispatcherToken = fabricateAccessToken("disp_test", "dispatcher");
        given()
            .header("Authorization", "Bearer " + dispatcherToken)
            .contentType(ContentType.JSON)
            .body(transporteBody("ZTEST_L", "ZTEST_A", ST_SCB, "100.00"))
        .when()
            .post("/quotations")
        .then()
            .statusCode(403)
            .body("code", equalTo("COM-003"));
    }

    @Test
    void create_withSalesRole_returns201() {
        String token = given()
            .contentType(ContentType.JSON)
            .body("{\"username\":\"lcampos\",\"password\":\"Sales1234\"}")
        .when()
            .post("/auth/login")
        .then()
            .statusCode(200)
            .extract().jsonPath().getString("token");

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(transporteBody("ZTEST_SAL_O", "ZTEST_SAL_D", ST_SCB, "100.00"))
        .when()
            .post("/quotations")
        .then()
            .statusCode(201);
    }

    // Anti-duplicado (QUO-002): el test vive en QuotationAntiDuplicateResourceTest
    // (clase aparte con @TestProfile que reactiva el window a 30s — la suite
    // general lo deshabilita para poder crear fixtures multiples del listado).

    // ---------- Code formato YYYY-NNNNN --------------------------------------

    @Test
    void create_generatesSequentialCodesForSameYear() {
        String token = loginAdmin();

        String code1 = given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(transporteBody("ZTEST_SEQ1", "ZTEST_X", ST_SCB, "100.00"))
        .when().post("/quotations")
        .then().statusCode(201).extract().jsonPath().getString("code");

        String code2 = given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(transporteBody("ZTEST_SEQ2", "ZTEST_X", ST_SPL, "200.00"))
        .when().post("/quotations")
        .then().statusCode(201).extract().jsonPath().getString("code");

        // Ambos del mismo anio, code2 > code1 (numero incremental).
        int year = Integer.parseInt(code1.substring(0, 4));
        int year2 = Integer.parseInt(code2.substring(0, 4));
        int n1 = Integer.parseInt(code1.substring(5));
        int n2 = Integer.parseInt(code2.substring(5));
        org.junit.jupiter.api.Assertions.assertEquals(year, year2, "ambos codes del mismo anio");
        org.junit.jupiter.api.Assertions.assertEquals(n1 + 1, n2, "code secuencial");
    }

    // =========================================================================
    // GET /quotations/{id}
    // =========================================================================

    // ---------- Helpers de creacion para GET tests --------------------------

    /** Crea una quotation con el body dado y devuelve el id persistido. */
    private long createQuotationAndReturnId(String token, String body) {
        return given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(body)
        .when()
            .post("/quotations")
        .then()
            .statusCode(201)
            .extract().jsonPath().getLong("id");
    }

    private String alquilerBody() {
        // ALQUILER no requiere origin/destination/cargoType/weight — todo nullable.
        // serviceTypeId 9 es ACB (kind=ALQUILER, verificado en BD prod).
        return String.format("""
            {
              "quotationType": "ALQUILER",
              "clientId": %d,
              "contactName": "ZTEST_ALQ",
              "currencyId": %d,
              "validityDays": 30,
              "items": [
                { "serviceTypeId": 9, "quantity": 5, "unitPrice": 500.00 }
              ]
            }
            """, CLIENT_ID, CURRENCY_ID);
    }

    private String integralBody() {
        // INT (24) root + SCB (1) transporte + CES (18) complementario.
        return String.format("""
            {
              "quotationType": "TRANSPORTE",
              "clientId": %d,
              "contactName": "ZTEST_INT",
              "currencyId": %d,
              "validityDays": 15,
              "origin": "ZTEST_LIMA",
              "destination": "ZTEST_CUSCO",
              "items": [
                { "itemNumber": 1, "serviceTypeId": %d, "quantity": 1, "unitPrice": 8000.00 },
                { "itemNumber": 2, "parentItemNumber": 1, "serviceTypeId": %d, "cargoTypeId": 1,
                  "weightKg": 25.00, "quantity": 1, "unitPrice": 0, "internalReferencePrice": 5000.00 },
                { "itemNumber": 3, "parentItemNumber": 1, "serviceTypeId": %d, "quantity": 1,
                  "unitPrice": 0, "internalReferencePrice": 1500.00 }
              ]
            }
            """, CLIENT_ID, CURRENCY_ID, ST_INT, ST_SCB, ST_CES);
    }

    private String transporteWithStandbyBody() {
        return String.format("""
            {
              "quotationType": "TRANSPORTE",
              "clientId": %d,
              "contactName": "ZTEST_STBY",
              "currencyId": %d,
              "validityDays": 15,
              "origin": "ZTEST_PIURA",
              "destination": "ZTEST_TUMBES",
              "items": [
                {
                  "serviceTypeId": %d, "cargoTypeId": 1, "weightKg": 12.00,
                  "quantity": 1, "unitPrice": 1500.00,
                  "standby": { "pricePerDay": 200.00, "includesIgv": false }
                }
              ]
            }
            """, CLIENT_ID, CURRENCY_ID, ST_SCB);
    }

    private String multiRootBody() {
        // 3 items root con itemNumber explicito.
        return String.format("""
            {
              "quotationType": "TRANSPORTE",
              "clientId": %d,
              "contactName": "ZTEST_MULTI",
              "currencyId": %d,
              "validityDays": 15,
              "origin": "ZTEST_LIMA",
              "destination": "ZTEST_TRUJILLO",
              "items": [
                { "itemNumber": 1, "serviceTypeId": %d, "cargoTypeId": 1, "weightKg": 10, "quantity": 1, "unitPrice": 1000.00 },
                { "itemNumber": 2, "serviceTypeId": %d, "cargoTypeId": 1, "weightKg": 10, "quantity": 1, "unitPrice": 1500.00 },
                { "itemNumber": 3, "serviceTypeId": %d, "cargoTypeId": 1, "weightKg": 10, "quantity": 1, "unitPrice": 2000.00 }
              ]
            }
            """, CLIENT_ID, CURRENCY_ID, ST_SCB, ST_SPL, 6 /* ST_SCH */);
    }

    private String transporteBodyWithoutPaymentTerm() {
        // Sin paymentTermId, sin tentativeServiceDate, sin contactPhone (todos nullable).
        return String.format("""
            {
              "quotationType": "TRANSPORTE",
              "clientId": %d,
              "contactName": "ZTEST_NULL",
              "currencyId": %d,
              "validityDays": 15,
              "origin": "ZTEST_LIMA",
              "destination": "ZTEST_ICA",
              "items": [
                { "serviceTypeId": %d, "cargoTypeId": 1, "weightKg": 10, "quantity": 1, "unitPrice": 1000.00 }
              ]
            }
            """, CLIENT_ID, CURRENCY_ID, ST_SCB);
    }

    // ---------- Happy paths -------------------------------------------------

    @Test
    void get_existingTransporteQuotation_returns200_withCompleteShape() {
        String token = loginAdmin();
        long id = createQuotationAndReturnId(token,
            transporteBody("ZTEST_LIMA", "ZTEST_AREQUIPA", ST_SCB, "1000.00"));

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/quotations/" + id)
        .then()
            .statusCode(200)
            .contentType("application/json")
            .body("id", equalTo((int) id))
            .body("code", matchesRegex("\\d{4}-\\d{5}"))
            .body("quotationType", equalTo("TRANSPORTE"))
            .body("status", equalTo("DRAFT"))
            .body("client.id", equalTo(CLIENT_ID))
            .body("currency.id", equalTo(CURRENCY_ID))
            .body("paymentTerm.id", equalTo(PAYMENT_TERM_ID))
            .body("origin", equalTo("ZTEST_LIMA"))
            .body("destination", equalTo("ZTEST_AREQUIPA"))
            .body("contactName", equalTo("ZTEST_CONTACT"))
            .body("totalSubtotal", equalTo(1000.00f))
            .body("totalIgv", equalTo(180.00f))
            .body("totalAmount", equalTo(1180.00f))
            .body("items.size()", equalTo(1))
            .body("items[0].subtotal", equalTo(1000.00f))
            .body("expiresAt", notNullValue())
            .body("isExpired", equalTo(false))
            .body("createdBy.username", equalTo("admin"))
            .body("updatedBy.username", equalTo("admin"))
            .body("createdAt", notNullValue())
            .body("updatedAt", notNullValue());
    }

    @Test
    void get_existingAlquilerQuotation_returns200_withNullOptionalFields() {
        String token = loginAdmin();
        long id = createQuotationAndReturnId(token, alquilerBody());

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/quotations/" + id)
        .then()
            .statusCode(200)
            .body("quotationType", equalTo("ALQUILER"))
            .body("origin", nullValue())
            .body("destination", nullValue())
            .body("paymentTerm", nullValue())
            .body("items[0].cargoType", nullValue())
            .body("items[0].weightKg", nullValue())
            .body("items[0].lengthMeters", nullValue())
            .body("items[0].widthMeters", nullValue())
            .body("items[0].heightMeters", nullValue());
    }

    @Test
    void get_existingIntegralQuotation_returns200_withChildrenEmbedded() {
        String token = loginAdmin();
        long id = createQuotationAndReturnId(token, integralBody());

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/quotations/" + id)
        .then()
            .statusCode(200)
            // El response trae 1 item root (el INT). Los 2 hijos embebidos en children.
            .body("items.size()", equalTo(1))
            .body("items[0].serviceType.code", equalTo("INT"))
            .body("items[0].children.size()", equalTo(2))
            .body("items[0].children[0].subtotal", equalTo(0))
            .body("items[0].children[1].subtotal", equalTo(0))
            .body("items[0].children[0].parentItemId", notNullValue())
            .body("items[0].children[1].parentItemId", notNullValue())
            // El total solo cuenta el root (los hijos suman 0).
            .body("totalSubtotal", equalTo(8000.00f));
    }

    @Test
    void get_quotationWithStandby_returns200_withStandbyPopulated() {
        String token = loginAdmin();
        long id = createQuotationAndReturnId(token, transporteWithStandbyBody());

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/quotations/" + id)
        .then()
            .statusCode(200)
            .body("items[0].standby", notNullValue())
            .body("items[0].standby.pricePerDay", equalTo(200.00f))
            .body("items[0].standby.includesIgv", equalTo(false))
            .body("items[0].standby.id", notNullValue());
    }

    @Test
    void get_quotationFreshlyCreated_isExpiredFalse() {
        String token = loginAdmin();
        long id = createQuotationAndReturnId(token,
            transporteBody("ZTEST_LIMA", "ZTEST_AREQUIPA", ST_SCB, "1000.00"));

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/quotations/" + id)
        .then()
            .statusCode(200)
            .body("isExpired", equalTo(false));
        // Cotizacion vieja con isExpired=true se valida en el unit test del service
        // (GetQuotationServiceTest) — no requiere insertar fixture con createdAt
        // manipulado via JDBC, mucho mas estable.
    }

    @Test
    void get_paymentTermNull_returnsNull() {
        String token = loginAdmin();
        long id = createQuotationAndReturnId(token, transporteBodyWithoutPaymentTerm());

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/quotations/" + id)
        .then()
            .statusCode(200)
            .body("paymentTerm", nullValue())
            .body("tentativeServiceDate", nullValue())
            .body("contactPhone", nullValue());
    }

    @Test
    void get_clientSummary_doesNotLeakClientResponseFields() {
        // Anti-leak ACL: client embebido debe ser Summary {id, name, ruc} — sin
        // phone, contactName, isActive, createdAt del catalogo Clients.
        String token = loginAdmin();
        long id = createQuotationAndReturnId(token,
            transporteBody("ZTEST_LIMA", "ZTEST_AREQUIPA", ST_SCB, "1000.00"));

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/quotations/" + id)
        .then()
            .statusCode(200)
            .body("client.id", notNullValue())
            .body("client.name", notNullValue())
            .body("client.ruc", notNullValue())
            .body("client.phone", nullValue())
            .body("client.contactName", nullValue())
            .body("client.isActive", nullValue())
            .body("client.createdAt", nullValue());
    }

    @Test
    void get_includesETagHeader_matchingPostFormat() {
        // POST devuelve ETag con formato "<updatedAt>". GET debe devolver mismo
        // formato exacto para que un futuro PUT/PATCH con If-Match matchee.
        String token = loginAdmin();
        // POST y capturamos el ETag + id en una sola operacion.
        var postResponse = given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(transporteBody("ZTEST_LIMA", "ZTEST_AREQUIPA", ST_SCB, "1000.00"))
        .when()
            .post("/quotations")
        .then()
            .statusCode(201)
            .extract();
        long id = postResponse.jsonPath().getLong("id");
        String postedEtag = postResponse.header("ETag");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/quotations/" + id)
        .then()
            .statusCode(200)
            .header("ETag", notNullValue())
            .header("ETag", matchesRegex("\".+\""))      // quoted format
            .header("ETag", equalTo(postedEtag));         // exacto = POST (no fue updated)
    }

    @Test
    void get_multiRootItems_orderedByItemNumber() {
        String token = loginAdmin();
        long id = createQuotationAndReturnId(token, multiRootBody());

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/quotations/" + id)
        .then()
            .statusCode(200)
            .body("items.size()", equalTo(3))
            .body("items[0].itemNumber", equalTo(1))
            .body("items[1].itemNumber", equalTo(2))
            .body("items[2].itemNumber", equalTo(3))
            .body("items[0].subtotal", equalTo(1000.00f))
            .body("items[1].subtotal", equalTo(1500.00f))
            .body("items[2].subtotal", equalTo(2000.00f))
            .body("totalSubtotal", equalTo(4500.00f));
    }

    // ---------- 404 Not Found -----------------------------------------------

    @Test
    void get_nonExistentId_returns404_QUO003() {
        String token = loginAdmin();

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/quotations/999999999")
        .then()
            .statusCode(404)
            .contentType("application/problem+json")
            .body("code", equalTo("QUO-003"))
            .body("status", equalTo(404))
            .body("detail", equalTo("La cotizacion con id 999999999 no existe"))
            .body("traceId", notNullValue());
    }

    @Test
    void get_idZero_returns404_QUO003() {
        String token = loginAdmin();

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/quotations/0")
        .then()
            .statusCode(404)
            .body("code", equalTo("QUO-003"))
            .body("detail", equalTo("La cotizacion con id 0 no existe"));
    }

    // ---------- 401 Unauthorized --------------------------------------------

    @Test
    void get_withoutToken_returns401() {
        // Sin token — el filtro de Quarkus Security responde 401 antes del endpoint.
        // No hay body Problem JSON (mismo patron que en el resto de los tests).
        given()
        .when()
            .get("/quotations/1")
        .then()
            .statusCode(401);
    }

    @Test
    void get_withMalformedToken_returns401_AUTH008() {
        given()
            .header("Authorization", "Bearer eyJ.malformed.token")
        .when()
            .get("/quotations/1")
        .then()
            .statusCode(401)
            .body("code", equalTo("AUTH-008"));
    }

    @Test
    void get_withExpiredToken_returns401_AUTH007() {
        Instant past = Instant.now().minusSeconds(60);
        String expiredToken = Jwt.subject("1")
            .upn("admin")
            .groups(Set.of("admin"))
            .claim("typ", "access")
            .issuedAt(past.minusSeconds(3600))
            .expiresAt(past)
            .sign();

        given()
            .header("Authorization", "Bearer " + expiredToken)
        .when()
            .get("/quotations/1")
        .then()
            .statusCode(401)
            .body("code", equalTo("AUTH-007"));
    }

    // ---------- 403 Forbidden -----------------------------------------------

    @Test
    void get_withDispatcherRole_returns403_COM003() {
        String token = fabricateAccessToken("disp_test", "dispatcher");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/quotations/1")
        .then()
            .statusCode(403)
            .contentType("application/problem+json")
            .body("code", equalTo("COM-003"));
    }

    // ---------- Roles permitidos --------------------------------------------

    @Test
    void get_withSalesRole_returns200() {
        // lcampos esta seeded como sales en DevDataSeeder.
        String adminToken = loginAdmin();
        long id = createQuotationAndReturnId(adminToken,
            transporteBody("ZTEST_LIMA", "ZTEST_AREQUIPA", ST_SCB, "1000.00"));

        String salesToken = given()
            .contentType(ContentType.JSON)
            .body("{\"username\":\"lcampos\",\"password\":\"Sales1234\"}")
        .when()
            .post("/auth/login")
        .then()
            .statusCode(200)
            .extract().jsonPath().getString("token");

        given()
            .header("Authorization", "Bearer " + salesToken)
        .when()
            .get("/quotations/" + id)
        .then()
            .statusCode(200);
    }

    @Test
    void get_withGeneralManagerRole_returns200() {
        String adminToken = loginAdmin();
        long id = createQuotationAndReturnId(adminToken,
            transporteBody("ZTEST_LIMA", "ZTEST_AREQUIPA", ST_SCB, "1000.00"));

        String token = fabricateAccessToken("gm_test", "general_manager");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/quotations/" + id)
        .then()
            .statusCode(200);
    }

    @Test
    void get_withOperationsManagerRole_returns200() {
        String adminToken = loginAdmin();
        long id = createQuotationAndReturnId(adminToken,
            transporteBody("ZTEST_LIMA", "ZTEST_AREQUIPA", ST_SCB, "1000.00"));

        String token = fabricateAccessToken("ops_test", "operations_manager");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/quotations/" + id)
        .then()
            .statusCode(200);
    }

    // =========================================================================
    // GET /quotations  (listado paginado con multifiltro)
    // =========================================================================
    //
    // Aislamiento de la dev DB compartida con prod: cada test crea sus fixtures
    // con un origin ZTEST_LST_<caso> unico y FILTRA por q=<ese token> para que el
    // content contenga SOLO sus propios fixtures (sin ruido prod ni de otros tests).

    /** TRANSPORTE en USD con origin/destination y currency custom (helper de listado). */
    private String transporteListBody(String origin, int currencyId, int serviceTypeId, String unitPrice) {
        return String.format("""
            {
              "quotationType": "TRANSPORTE",
              "clientId": %d,
              "contactName": "ZTEST_CONTACT",
              "currencyId": %d,
              "validityDays": 15,
              "origin": "%s",
              "destination": "%s_DEST",
              "items": [
                { "serviceTypeId": %d, "cargoTypeId": 1, "weightKg": 10.00, "quantity": 1, "unitPrice": %s }
              ]
            }
            """, CLIENT_ID, currencyId, origin, origin, serviceTypeId, unitPrice);
    }

    // ---------- Paginación + shape ------------------------------------------

    @Test
    void list_withoutParams_returns200WithDefaults() {
        String token = loginAdmin();
        createQuotationAndReturnId(token, transporteListBody("ZTEST_LST_DEF", CURRENCY_ID, ST_SCB, "1000.00"));

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/quotations")
        .then()
            .statusCode(200)
            .contentType("application/json")
            .body("page", equalTo(0))
            .body("size", equalTo(20))
            .body("totalElements", greaterThanOrEqualTo(1));
    }

    @Test
    void list_summaryShape_hasAllContractFields() {
        String token = loginAdmin();
        createQuotationAndReturnId(token, transporteListBody("ZTEST_LST_SHAPE", CURRENCY_ID, ST_SCB, "1000.00"));

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/quotations?q=ZTEST_LST_SHAPE")
        .then()
            .statusCode(200)
            .body("content[0].id", notNullValue())
            .body("content[0].code", matchesRegex("\\d{4}-\\d{5}"))
            .body("content[0].quotationType", equalTo("TRANSPORTE"))
            .body("content[0].status", equalTo("DRAFT"))
            .body("content[0].client.id", equalTo(CLIENT_ID))
            .body("content[0].client.name", notNullValue())
            .body("content[0].client.ruc", notNullValue())
            .body("content[0].currencyCode", equalTo("USD"))
            .body("content[0].totalAmount", equalTo(1180.00f))
            .body("content[0].itemsCount", equalTo(1))
            .body("content[0].validityDays", equalTo(15))
            .body("content[0].isExpired", equalTo(false))
            .body("content[0].origin", equalTo("ZTEST_LST_SHAPE"))
            .body("content[0].createdAt", notNullValue())
            .body("content[0].createdBy.username", equalTo("admin"));
    }

    @Test
    void list_clientSummary_doesNotLeakMasterFields() {
        String token = loginAdmin();
        createQuotationAndReturnId(token, transporteListBody("ZTEST_LST_ACL", CURRENCY_ID, ST_SCB, "1000.00"));

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/quotations?q=ZTEST_LST_ACL")
        .then()
            .statusCode(200)
            .body("content[0].client.id", notNullValue())
            .body("content[0].client.phone", nullValue())
            .body("content[0].client.contactName", nullValue())
            .body("content[0].client.isActive", nullValue());
    }

    @Test
    void list_emptySlice_returns200NotEmpty() {
        String token = loginAdmin();

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/quotations?q=ZZZNADAEXISTE_xyz_999")
        .then()
            .statusCode(200)
            .body("content.size()", is(0))
            .body("totalElements", equalTo(0))
            .body("totalPages", equalTo(0))
            .body("empty", equalTo(true))
            .body("first", equalTo(true))
            .body("last", equalTo(true));
    }

    @Test
    void list_pagination_page1Size5() {
        String token = loginAdmin();
        for (int i = 0; i < 12; i++) {
            createQuotationAndReturnId(token,
                transporteListBody(String.format("ZTEST_LST_PG_%02d", i), CURRENCY_ID, ST_SCB, "1000.00"));
        }

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/quotations?q=ZTEST_LST_PG&page=1&size=5")
        .then()
            .statusCode(200)
            .body("page", equalTo(1))
            .body("size", equalTo(5))
            .body("numberOfElements", equalTo(5))
            .body("totalElements", equalTo(12))
            .body("totalPages", equalTo(3))
            .body("first", equalTo(false))
            .body("last", equalTo(false));
    }

    @Test
    void list_pageBeyondTotal_returnsEmptyContentLastTrue() {
        String token = loginAdmin();
        createQuotationAndReturnId(token, transporteListBody("ZTEST_LST_OVF", CURRENCY_ID, ST_SCB, "1000.00"));

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/quotations?q=ZTEST_LST_OVF&page=99&size=20")
        .then()
            .statusCode(200)
            .body("content.size()", is(0))
            .body("totalElements", equalTo(1))
            .body("empty", equalTo(true))
            .body("last", equalTo(true));
    }

    @Test
    void list_sizeAboveMax_returns400() {
        given()
            .header("Authorization", "Bearer " + loginAdmin())
        .when()
            .get("/quotations?size=101")
        .then()
            .statusCode(400)
            .body("code", equalTo("COM-001"));
    }

    @Test
    void list_pageNegative_returns400() {
        given()
            .header("Authorization", "Bearer " + loginAdmin())
        .when()
            .get("/quotations?page=-1")
        .then()
            .statusCode(400)
            .body("code", equalTo("COM-001"));
    }

    // ---------- Orden fijo createdAt DESC -----------------------------------

    @Test
    void list_orderedByCreatedAtDescending() {
        String token = loginAdmin();
        createQuotationAndReturnId(token, transporteListBody("ZTEST_LST_ORD_A", CURRENCY_ID, ST_SCB, "1000.00"));
        createQuotationAndReturnId(token, transporteListBody("ZTEST_LST_ORD_B", CURRENCY_ID, ST_SCB, "1000.00"));
        createQuotationAndReturnId(token, transporteListBody("ZTEST_LST_ORD_C", CURRENCY_ID, ST_SCB, "1000.00"));

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/quotations?q=ZTEST_LST_ORD&size=100")
        .then()
            .statusCode(200)
            // C es el más reciente → primero. Orden relativo C, B, A.
            .body("content[0].origin", equalTo("ZTEST_LST_ORD_C"))
            .body("content.origin", containsInRelativeOrder(
                "ZTEST_LST_ORD_C", "ZTEST_LST_ORD_B", "ZTEST_LST_ORD_A"));
    }

    // ---------- Filtros individuales ----------------------------------------

    @Test
    void list_filterByStatusDraft_returnsOnlyDraft() {
        String token = loginAdmin();
        createQuotationAndReturnId(token, transporteListBody("ZTEST_LST_ST", CURRENCY_ID, ST_SCB, "1000.00"));

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/quotations?q=ZTEST_LST_ST&status=DRAFT")
        .then()
            .statusCode(200)
            .body("content.status", everyItem(equalTo("DRAFT")))
            .body("totalElements", greaterThanOrEqualTo(1));
    }

    @Test
    void list_filterByStatusSent_excludesDraftFixture() {
        String token = loginAdmin();
        createQuotationAndReturnId(token, transporteListBody("ZTEST_LST_STS", CURRENCY_ID, ST_SCB, "1000.00"));

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/quotations?q=ZTEST_LST_STS&status=SENT")
        .then()
            .statusCode(200)
            .body("content.size()", is(0));   // el fixture es DRAFT, no aparece bajo SENT
    }

    @Test
    void list_filterByClientId() {
        String token = loginAdmin();
        createQuotationAndReturnId(token, transporteListBody("ZTEST_LST_CID", CURRENCY_ID, ST_SCB, "1000.00"));

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/quotations?q=ZTEST_LST_CID&clientId=" + CLIENT_ID)
        .then()
            .statusCode(200)
            .body("content.client.id", everyItem(equalTo(CLIENT_ID)))
            .body("totalElements", greaterThanOrEqualTo(1));
    }

    @Test
    void list_filterByNonExistentClientId_returnsEmpty() {
        given()
            .header("Authorization", "Bearer " + loginAdmin())
        .when()
            .get("/quotations?clientId=999999&q=ZTEST_LST_NOCLI")
        .then()
            .statusCode(200)
            .body("content.size()", is(0));
    }

    @Test
    void list_filterByCreatedById_returnsOnlyThatCreator() {
        String token = loginAdmin();
        long id = createQuotationAndReturnId(token, transporteListBody("ZTEST_LST_CBID", CURRENCY_ID, ST_SCB, "1000.00"));
        // El createdBy.id no es determinista (restore de prod) — lo extraemos del recurso.
        int adminId = given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/quotations/" + id)
        .then()
            .extract().jsonPath().getInt("createdBy.id");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/quotations?q=ZTEST_LST_CBID&createdById=" + adminId)
        .then()
            .statusCode(200)
            .body("content.createdBy.id", everyItem(equalTo(adminId)))
            .body("content.origin", hasItem("ZTEST_LST_CBID"));
    }

    @Test
    void list_filterByCurrencyId_returnsOnlyThatCurrency() {
        String token = loginAdmin();
        createQuotationAndReturnId(token, transporteListBody("ZTEST_LST_CUR_USD", CURRENCY_ID, ST_SCB, "1000.00"));
        createQuotationAndReturnId(token, transporteListBody("ZTEST_LST_CUR_PEN", 2, ST_SCB, "1000.00"));

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/quotations?q=ZTEST_LST_CUR&currencyId=2")
        .then()
            .statusCode(200)
            .body("content.currencyCode", everyItem(equalTo("PEN")))
            .body("content.origin", not(hasItem("ZTEST_LST_CUR_USD")));
    }

    @Test
    void list_filterByServiceTypeId_returnsOnlyHavingThatType() {
        String token = loginAdmin();
        createQuotationAndReturnId(token, transporteListBody("ZTEST_LST_STID_SPL", CURRENCY_ID, ST_SPL, "1000.00"));
        createQuotationAndReturnId(token, transporteListBody("ZTEST_LST_STID_SCB", CURRENCY_ID, ST_SCB, "1000.00"));

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/quotations?q=ZTEST_LST_STID&serviceTypeId=" + ST_SPL)
        .then()
            .statusCode(200)
            .body("content.origin", hasItem("ZTEST_LST_STID_SPL"))
            .body("content.origin", not(hasItem("ZTEST_LST_STID_SCB")));
    }

    @Test
    void list_filterByServiceTypeId_matchesIntegralChild() {
        // El Integral tiene hijos SCB(1) + CES(18). Filtrar por CES debe encontrarlo
        // (el EXISTS incluye items hijos, no solo root).
        String token = loginAdmin();
        createQuotationAndReturnId(token, integralListBody("ZTEST_LST_STINT"));

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/quotations?q=ZTEST_LST_STINT&serviceTypeId=" + ST_CES)
        .then()
            .statusCode(200)
            .body("content.origin", hasItem("ZTEST_LST_STINT"));
    }

    @Test
    void list_filterByCargoTypeId() {
        String token = loginAdmin();
        createQuotationAndReturnId(token, transporteListBody("ZTEST_LST_CTID", CURRENCY_ID, ST_SCB, "1000.00"));

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/quotations?q=ZTEST_LST_CTID&cargoTypeId=1")
        .then()
            .statusCode(200)
            .body("content.origin", hasItem("ZTEST_LST_CTID"));
    }

    @Test
    void list_filterByDatePastFrom_includesTodayFixture() {
        // dateFrom = ayer (zona Lima, como el backend) → el fixture de hoy queda incluido.
        // Margen de 1 día evita el borde de medianoche por zona horaria.
        String token = loginAdmin();
        createQuotationAndReturnId(token, transporteListBody("ZTEST_LST_DFROM", CURRENCY_ID, ST_SCB, "1000.00"));

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/quotations?q=ZTEST_LST_DFROM&dateFrom=" + LocalDate.now(LIMA).minusDays(1))
        .then()
            .statusCode(200)
            .body("totalElements", greaterThanOrEqualTo(1));
    }

    @Test
    void list_filterByFutureDateFrom_excludesTodayFixture() {
        // dateFrom = pasado mañana (zona Lima) → el fixture de hoy queda excluido.
        // Margen de 2 días evita el borde de medianoche por zona horaria.
        String token = loginAdmin();
        createQuotationAndReturnId(token, transporteListBody("ZTEST_LST_DFUT", CURRENCY_ID, ST_SCB, "1000.00"));

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/quotations?q=ZTEST_LST_DFUT&dateFrom=" + LocalDate.now(LIMA).plusDays(2))
        .then()
            .statusCode(200)
            .body("content.size()", is(0));
    }

    @Test
    void list_filterByDateToToday_includesTodayFixture() {
        // dateTo = hoy (zona Lima). dateTo es INCLUSIVO del día completo → el
        // fixture creado hoy (cualquier hora) debe aparecer. Pin del borde de
        // inclusividad (< dateTo+1día Lima).
        String token = loginAdmin();
        createQuotationAndReturnId(token, transporteListBody("ZTEST_LST_DTO", CURRENCY_ID, ST_SCB, "1000.00"));

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/quotations?q=ZTEST_LST_DTO&dateTo=" + LocalDate.now(LIMA))
        .then()
            .statusCode(200)
            .body("totalElements", greaterThanOrEqualTo(1));
    }

    // ---------- Búsqueda q ---------------------------------------------------

    @Test
    void list_qMatchingOrigin_returnsMatch() {
        String token = loginAdmin();
        createQuotationAndReturnId(token, transporteListBody("ZTEST_LST_QORIG", CURRENCY_ID, ST_SCB, "1000.00"));

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/quotations?q=ZTEST_LST_QORIG")
        .then()
            .statusCode(200)
            .body("content.origin", hasItem("ZTEST_LST_QORIG"));
    }

    @Test
    void list_qMatchingCode_returnsThatQuotation() {
        String token = loginAdmin();
        long id = createQuotationAndReturnId(token, transporteListBody("ZTEST_LST_QCODE", CURRENCY_ID, ST_SCB, "1000.00"));
        String code = given().header("Authorization", "Bearer " + token)
            .when().get("/quotations/" + id)
            .then().extract().jsonPath().getString("code");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/quotations?q=" + code)
        .then()
            .statusCode(200)
            .body("content.code", hasItem(code));
    }

    @Test
    void list_qTooShort_returns400() {
        given()
            .header("Authorization", "Bearer " + loginAdmin())
        .when()
            .get("/quotations?q=ab")
        .then()
            .statusCode(400)
            .body("code", equalTo("COM-001"));
    }

    @Test
    void list_qWithUnderscore_treatedAsLiteralNotWildcard() {
        // El `_` del q debe buscarse literal, NO como comodin de LIKE. Si no se
        // escapara, q="ESCA_B" matchearia tambien "ESCA1B" (_ = cualquier char).
        String token = loginAdmin();
        createQuotationAndReturnId(token, transporteListBody("ZTEST_ESCA1B", CURRENCY_ID, ST_SCB, "1000.00"));
        createQuotationAndReturnId(token, transporteListBody("ZTEST_ESCA_B", CURRENCY_ID, ST_SCB, "1000.00"));

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/quotations?q=ESCA_B")
        .then()
            .statusCode(200)
            .body("content.origin", hasItem("ZTEST_ESCA_B"))
            .body("content.origin", not(hasItem("ZTEST_ESCA1B")));   // el _ no comodineo
    }

    // ---------- Multifiltro (AND) -------------------------------------------

    @Test
    void list_multifilter_statusClientType_combinesWithAnd() {
        String token = loginAdmin();
        createQuotationAndReturnId(token, transporteListBody("ZTEST_LST_MULTI_T", CURRENCY_ID, ST_SCB, "1000.00"));

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/quotations?q=ZTEST_LST_MULTI&status=DRAFT&clientId=" + CLIENT_ID + "&quotationType=TRANSPORTE")
        .then()
            .statusCode(200)
            .body("content.origin", hasItem("ZTEST_LST_MULTI_T"))
            .body("content.quotationType", everyItem(equalTo("TRANSPORTE")))
            .body("content.status", everyItem(equalTo("DRAFT")));
    }

    @Test
    void list_multifilter_currencyAndServiceType_combinesWithAnd() {
        String token = loginAdmin();
        createQuotationAndReturnId(token, transporteListBody("ZTEST_LST_M2_HIT", 2, ST_SPL, "1000.00"));      // PEN + SPL → matchea
        createQuotationAndReturnId(token, transporteListBody("ZTEST_LST_M2_USD", CURRENCY_ID, ST_SPL, "1000.00")); // USD → excluido
        createQuotationAndReturnId(token, transporteListBody("ZTEST_LST_M2_SCB", 2, ST_SCB, "1000.00"));      // SCB → excluido

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/quotations?q=ZTEST_LST_M2&currencyId=2&serviceTypeId=" + ST_SPL)
        .then()
            .statusCode(200)
            .body("content.origin", hasItem("ZTEST_LST_M2_HIT"))
            .body("content.origin", not(hasItem("ZTEST_LST_M2_USD")))
            .body("content.origin", not(hasItem("ZTEST_LST_M2_SCB")));
    }

    // ---------- Reglas del summary ------------------------------------------

    @Test
    void list_totalAmount_correctForKnownQuotation() {
        String token = loginAdmin();
        createQuotationAndReturnId(token, transporteListBody("ZTEST_LST_TOTAL", CURRENCY_ID, ST_SCB, "1000.00"));

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/quotations?q=ZTEST_LST_TOTAL")
        .then()
            .statusCode(200)
            .body("content[0].totalAmount", equalTo(1180.00f));   // 1000 + 18% IGV
    }

    @Test
    void list_itemsCount_integralCountsRootOnly() {
        // CASO CRÍTICO: un Integral tiene 1 root (INT) + 2 hijos. itemsCount debe ser 1, no 3.
        String token = loginAdmin();
        createQuotationAndReturnId(token, integralListBody("ZTEST_LST_INTCNT"));

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/quotations?q=ZTEST_LST_INTCNT")
        .then()
            .statusCode(200)
            .body("content[0].itemsCount", equalTo(1))
            .body("content[0].totalAmount", equalTo(9440.00f));   // root 8000 + 18% IGV
    }

    // ---------- Auth + roles -------------------------------------------------

    @Test
    void list_withoutToken_returns401() {
        given()
        .when()
            .get("/quotations")
        .then()
            .statusCode(401);
    }

    @Test
    void list_withMalformedToken_returns401_AUTH008() {
        given()
            .header("Authorization", "Bearer eyJ.malformed.token")
        .when()
            .get("/quotations")
        .then()
            .statusCode(401)
            .body("code", equalTo("AUTH-008"));
    }

    @Test
    void list_withDispatcherRole_returns403_COM003() {
        given()
            .header("Authorization", "Bearer " + fabricateAccessToken("disp_test", "dispatcher"))
        .when()
            .get("/quotations")
        .then()
            .statusCode(403)
            .body("code", equalTo("COM-003"));
    }

    @Test
    void list_withSalesRole_returns200() {
        String salesToken = given()
            .contentType(ContentType.JSON)
            .body("{\"username\":\"lcampos\",\"password\":\"Sales1234\"}")
        .when()
            .post("/auth/login")
        .then()
            .statusCode(200)
            .extract().jsonPath().getString("token");

        given()
            .header("Authorization", "Bearer " + salesToken)
        .when()
            .get("/quotations")
        .then()
            .statusCode(200);
    }

    @Test
    void list_withGeneralManagerRole_returns200() {
        given()
            .header("Authorization", "Bearer " + fabricateAccessToken("gm_test", "general_manager"))
        .when()
            .get("/quotations")
        .then()
            .statusCode(200);
    }

    /** Integral con origin custom (1 INT root + SCB + CES hijos). Para tests de listado. */
    private String integralListBody(String origin) {
        return String.format("""
            {
              "quotationType": "TRANSPORTE",
              "clientId": %d,
              "contactName": "ZTEST_INT",
              "currencyId": %d,
              "validityDays": 15,
              "origin": "%s",
              "destination": "%s_DEST",
              "items": [
                { "itemNumber": 1, "serviceTypeId": %d, "quantity": 1, "unitPrice": 8000.00 },
                { "itemNumber": 2, "parentItemNumber": 1, "serviceTypeId": %d, "cargoTypeId": 1,
                  "weightKg": 25.00, "quantity": 1, "unitPrice": 0, "internalReferencePrice": 5000.00 },
                { "itemNumber": 3, "parentItemNumber": 1, "serviceTypeId": %d, "quantity": 1,
                  "unitPrice": 0, "internalReferencePrice": 1500.00 }
              ]
            }
            """, CLIENT_ID, CURRENCY_ID, origin, origin, ST_INT, ST_SCB, ST_CES);
    }
}
