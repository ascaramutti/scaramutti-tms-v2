package com.scaramutti.tms.quotations;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.smallrye.jwt.build.Jwt;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.matchesRegex;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

@QuarkusTest
class QuotationResourceTest {

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

    // ---------- Anti-duplicado (QUO-002) -------------------------------------

    @Test
    void create_sameClientAndServiceTypesWithin30s_returns409_QUO002() {
        String token = loginAdmin();
        String body = transporteBody("ZTEST_DUP1", "ZTEST_DUP2", ST_SCB, "100.00");

        // Primer POST OK.
        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(body)
        .when()
            .post("/quotations")
        .then()
            .statusCode(201);

        // Segundo POST inmediato con mismo client + mismo serviceType → 409.
        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(body)
        .when()
            .post("/quotations")
        .then()
            .statusCode(409)
            .body("code", equalTo("QUO-002"));
    }

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
}
