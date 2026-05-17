package com.scaramutti.tms.clients;

import com.scaramutti.tms.shared.entity.Client;
import com.scaramutti.tms.shared.repository.ClientRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.smallrye.jwt.build.Jwt;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.containsInRelativeOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class ClientsResourceTest {

    @Inject ClientRepository clientRepository;

    // RUCs unicos por test para no colisionar entre runs (last digit lo distingue).
    private static final String RUC_1 = "20100100100";
    private static final String RUC_2 = "20200200200";
    private static final String RUC_3 = "20300300300";
    private static final String RUC_4 = "20400400400";
    private static final String RUC_5 = "20500500500";
    private static final String RUC_6 = "20600600600";
    private static final String RUC_7 = "20700700700";
    private static final String RUC_8 = "20800800800";
    private static final String RUC_9 = "20900900900";

    private void cleanupClientByRuc(String ruc) {
        QuarkusTransaction.requiringNew().run(() -> clientRepository.delete("ruc", ruc));
    }

    private void cleanupClientByName(String nameUpper) {
        QuarkusTransaction.requiringNew().run(() -> clientRepository.delete("name", nameUpper));
    }

    private String login(String username, String password) {
        return given()
            .contentType(ContentType.JSON)
            .body("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}")
        .when()
            .post("/auth/login")
        .then()
            .statusCode(200)
            .extract().jsonPath().getString("token");
    }

    /** Genera un JWT valido para un rol sin user seedeado (dispatcher, operations_manager). */
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

    private String body(String name, String ruc, String phone, String contactName) {
        StringBuilder sb = new StringBuilder("{");
        if (name != null) sb.append("\"name\":\"").append(name).append("\",");
        if (ruc != null) sb.append("\"ruc\":\"").append(ruc).append("\",");
        if (phone != null) sb.append("\"phone\":\"").append(phone).append("\",");
        if (contactName != null) sb.append("\"contactName\":\"").append(contactName).append("\",");
        if (sb.charAt(sb.length() - 1) == ',') sb.deleteCharAt(sb.length() - 1);
        sb.append("}");
        return sb.toString();
    }

    // ---------- Happy path / contrato ----------------------------------------

    @Test
    void create_withValidPayload_returns201AndPersists() {
        try {
            String token = login("admin", "Admin1234");

            given()
                .header("Authorization", "Bearer " + token)
                .contentType(ContentType.JSON)
                .body(body("Acme Corp SAC", RUC_1, "987654321", "Juan Pérez"))
            .when()
                .post("/clients")
            .then()
                .statusCode(201)
                .contentType("application/json")
                .body("id", greaterThanOrEqualTo(1))
                .body("name", equalTo("ACME CORP SAC"))
                .body("ruc", equalTo(RUC_1))
                .body("phone", equalTo("987654321"))
                .body("contactName", equalTo("Juan Pérez"))
                .body("isActive", equalTo(true))
                .body("createdAt", notNullValue());

            // Verifica persistencia
            Client persisted = clientRepository.find("ruc", RUC_1).firstResult();
            assertNotNull(persisted);
            assertEquals("ACME CORP SAC", persisted.name);
        } finally {
            cleanupClientByRuc(RUC_1);
        }
    }

    @Test
    void create_withOnlyRequiredFields_returns201AndPersistsNullsForOptionals() {
        try {
            String token = login("admin", "Admin1234");

            given()
                .header("Authorization", "Bearer " + token)
                .contentType(ContentType.JSON)
                .body(body("MinimalCo", RUC_2, null, null))
            .when()
                .post("/clients")
            .then()
                .statusCode(201)
                .body("phone", nullValue())
                .body("contactName", nullValue());
        } finally {
            cleanupClientByRuc(RUC_2);
        }
    }

    @Test
    void create_responseShape_matchesContract() {
        try {
            String token = login("admin", "Admin1234");
            Instant before = Instant.now().minusSeconds(5);

            String createdAtRaw = given()
                .header("Authorization", "Bearer " + token)
                .contentType(ContentType.JSON)
                .body(body("ShapeTest", RUC_3, null, null))
            .when()
                .post("/clients")
            .then()
                .statusCode(201)
                .body("id", greaterThanOrEqualTo(1))
                .body("isActive", equalTo(true))
                .body("createdAt", notNullValue())
                .extract().jsonPath().getString("createdAt");

            // createdAt es reciente (ultimos 5 segundos)
            Instant createdAt = Instant.parse(createdAtRaw);
            assertTrue(createdAt.isAfter(before),
                "createdAt deberia ser posterior a " + before + " pero fue " + createdAt);
        } finally {
            cleanupClientByRuc(RUC_3);
        }
    }

    @Test
    void create_trimsAndUppercasesName_normalizesContactName() {
        try {
            String token = login("admin", "Admin1234");

            given()
                .header("Authorization", "Bearer " + token)
                .contentType(ContentType.JSON)
                .body(body("  trim test  ", RUC_4, null, "  María  "))
            .when()
                .post("/clients")
            .then()
                .statusCode(201)
                .body("name", equalTo("TRIM TEST"))
                .body("contactName", equalTo("María"));
        } finally {
            cleanupClientByRuc(RUC_4);
        }
    }

    @Test
    void create_withEmptyContactName_persistsAsNull() {
        try {
            String token = login("admin", "Admin1234");

            given()
                .header("Authorization", "Bearer " + token)
                .contentType(ContentType.JSON)
                .body(body("EmptyContact", RUC_5, null, "   "))
            .when()
                .post("/clients")
            .then()
                .statusCode(201)
                .body("contactName", nullValue());
        } finally {
            cleanupClientByRuc(RUC_5);
        }
    }

    @Test
    void create_withNameOnlyWhitespace_returns400_COM001() {
        // name="   " pasa @NotBlank pero despues del trim queda "" — el guard
        // post-trim del service devuelve 400 COM-001.
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(body("   ", RUC_6, null, null))
        .when()
            .post("/clients")
        .then()
            .statusCode(400)
            .contentType("application/problem+json")
            .body("code", equalTo("COM-001"));
    }

    // ---------- Duplicados (409) --------------------------------------------

    @Test
    void create_withDuplicateRuc_returns409_CLI001() {
        try {
            String token = login("admin", "Admin1234");

            // Crear primero
            given()
                .header("Authorization", "Bearer " + token)
                .contentType(ContentType.JSON)
                .body(body("FirstClient", RUC_7, null, null))
            .when()
                .post("/clients")
            .then()
                .statusCode(201);

            // Segundo con mismo RUC, name distinto
            given()
                .header("Authorization", "Bearer " + token)
                .contentType(ContentType.JSON)
                .body(body("SecondClient", RUC_7, null, null))
            .when()
                .post("/clients")
            .then()
                .statusCode(409)
                .contentType("application/problem+json")
                .body("code", equalTo("CLI-001"));
        } finally {
            cleanupClientByRuc(RUC_7);
            cleanupClientByName("SECONDCLIENT"); // por si el segundo se persistio (no deberia)
        }
    }

    @Test
    void create_withDuplicateName_returns409_CLI002() {
        try {
            String token = login("admin", "Admin1234");

            given()
                .header("Authorization", "Bearer " + token)
                .contentType(ContentType.JSON)
                .body(body("DupName Test", RUC_8, null, null))
            .when()
                .post("/clients")
            .then()
                .statusCode(201);

            // Segundo con mismo name (uppercase → colisiona) y ruc distinto
            given()
                .header("Authorization", "Bearer " + token)
                .contentType(ContentType.JSON)
                .body(body("dupname test", RUC_9, null, null))
            .when()
                .post("/clients")
            .then()
                .statusCode(409)
                .contentType("application/problem+json")
                .body("code", equalTo("CLI-002"));
        } finally {
            cleanupClientByName("DUPNAME TEST");
            cleanupClientByRuc(RUC_8);
            cleanupClientByRuc(RUC_9);
        }
    }

    // ---------- Validación (400) -------------------------------------------

    @Test
    void create_withMissingName_returns400() {
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(body(null, "20111111111", null, null))
        .when()
            .post("/clients")
        .then()
            .statusCode(400)
            .body("code", equalTo("COM-001"))
            .body("errors.field", hasItem("name"));
    }

    @Test
    void create_withMissingRuc_returns400() {
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(body("NoRuc", null, null, null))
        .when()
            .post("/clients")
        .then()
            .statusCode(400)
            .body("code", equalTo("COM-001"))
            .body("errors.field", hasItem("ruc"));
    }

    @Test
    void create_withRucTooShort_returns400() {
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(body("ShortRuc", "1234567890", null, null))
        .when()
            .post("/clients")
        .then()
            .statusCode(400)
            .body("errors.field", hasItem("ruc"));
    }

    @Test
    void create_withRucContainingLetters_returns400() {
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(body("LetterRuc", "2012345678A", null, null))
        .when()
            .post("/clients")
        .then()
            .statusCode(400)
            .body("errors.field", hasItem("ruc"));
    }

    @Test
    void create_withInvalidPhoneFormat_returns400() {
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(body("BadPhone", "20122222222", "12345", null))
        .when()
            .post("/clients")
        .then()
            .statusCode(400)
            .body("errors.field", hasItem("phone"));
    }

    @Test
    void create_withNameTooLong_returns400() {
        String token = login("admin", "Admin1234");
        String longName = "X".repeat(201);

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(body(longName, "20133333333", null, null))
        .when()
            .post("/clients")
        .then()
            .statusCode(400)
            .body("errors.field", hasItem("name"));
    }

    @Test
    void create_withContactNameTooLong_returns400() {
        String token = login("admin", "Admin1234");
        String longContact = "Y".repeat(101);

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(body("ContactLong", "20144444444", null, longContact))
        .when()
            .post("/clients")
        .then()
            .statusCode(400)
            .body("errors.field", hasItem("contactName"));
    }

    // ---------- Autorización (403) -----------------------------------------

    @Test
    void create_withDispatcherRole_returns403_COM003() {
        // No hay seed para dispatcher — fabrico JWT.
        String dispatcherToken = fabricateAccessToken("dispatcher_test", "dispatcher");

        given()
            .header("Authorization", "Bearer " + dispatcherToken)
            .contentType(ContentType.JSON)
            .body(body("ShouldNotPersist", "20155555555", null, null))
        .when()
            .post("/clients")
        .then()
            .statusCode(403)
            .contentType("application/problem+json")
            .body("code", equalTo("COM-003"));
    }

    @Test
    void create_withSalesRole_returns201() {
        try {
            String token = login("lcampos", "Sales1234");

            given()
                .header("Authorization", "Bearer " + token)
                .contentType(ContentType.JSON)
                .body(body("SalesCreated", "20166666666", null, null))
            .when()
                .post("/clients")
            .then()
                .statusCode(201);
        } finally {
            cleanupClientByRuc("20166666666");
        }
    }

    @Test
    void create_withOperationsManagerRole_returns201() {
        try {
            // No hay seed para operations_manager — fabrico JWT.
            String opsToken = fabricateAccessToken("ops_test", "operations_manager");

            given()
                .header("Authorization", "Bearer " + opsToken)
                .contentType(ContentType.JSON)
                .body(body("OpsCreated", "20177777777", null, null))
            .when()
                .post("/clients")
            .then()
                .statusCode(201);
        } finally {
            cleanupClientByRuc("20177777777");
        }
    }

    // ---------- Auth (401) -------------------------------------------------

    @Test
    void create_withoutToken_returns401() {
        given()
            .contentType(ContentType.JSON)
            .body(body("NoAuth", "20188888888", null, null))
        .when()
            .post("/clients")
        .then()
            .statusCode(401);
    }

    @Test
    void create_withMalformedToken_returns401_AUTH008() {
        given()
            .header("Authorization", "Bearer eyJ.malformed.token")
            .contentType(ContentType.JSON)
            .body(body("Malformed", "20199999999", null, null))
        .when()
            .post("/clients")
        .then()
            .statusCode(401)
            .contentType("application/problem+json")
            .body("code", equalTo("AUTH-008"));
    }

    @Test
    void create_withExpiredToken_returns401_AUTH007() {
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
            .contentType(ContentType.JSON)
            .body(body("Expired", "20211111111", null, null))
        .when()
            .post("/clients")
        .then()
            .statusCode(401)
            .contentType("application/problem+json")
            .body("code", equalTo("AUTH-007"));
    }

    // =========================================================================
    // GET /clients (listClients) — paginado con busqueda fuzzy + filtro isActive
    // =========================================================================
    //
    // Fixtures de listing:
    //  - RUCs siempre con prefijo "90" (los seeds prod arrancan con "20").
    //  - Nombres con prefijo "ZTEST_" (letra Z al final del orden alfabetico
    //    asi no perturba a otros clients que pudiera haber).
    //  - Cleanup en @AfterEach borra TODO lo que matchee prefijo "90" — los tests
    //    corren secuencialmente bajo @QuarkusTest, sin condiciones de carrera.

    private static final String TEST_RUC_PREFIX = "90";
    private static final String TEST_NAME_PREFIX = "ZTEST_";

    @AfterEach
    void cleanupListingFixtures() {
        QuarkusTransaction.requiringNew().run(() ->
            clientRepository.delete("ruc like ?1", TEST_RUC_PREFIX + "%")
        );
    }

    /** Inserta un cliente y devuelve la entity persistida (con id asignado por BD). */
    private void seedClient(String name, String ruc, boolean isActive) {
        QuarkusTransaction.requiringNew().run(() -> {
            Client c = new Client();
            c.name = name;
            c.ruc = ruc;
            c.isActive = isActive;
            clientRepository.persist(c);
        });
    }

    // ---------- Happy path / shape del response ------------------------------

    @Test
    void list_withoutQueryParams_returnsFirstPageWithDefaults() {
        // 25 fixtures > size default (20). Verifica defaults page=0, size=20.
        for (int i = 1; i <= 25; i++) {
            seedClient(TEST_NAME_PREFIX + String.format("%02d", i),
                       TEST_RUC_PREFIX + String.format("%09d", i), true);
        }
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/clients")
        .then()
            .statusCode(200)
            .contentType("application/json")
            .body("page", equalTo(0))
            .body("size", equalTo(20))
            .body("numberOfElements", equalTo(20))
            .body("totalElements", greaterThanOrEqualTo(25))
            .body("first", equalTo(true))
            .body("last", equalTo(false))
            .body("empty", equalTo(false))
            .body("content.size()", equalTo(20));
    }

    @Test
    void list_responseShape_matchesPageMetaAndClientResponseContract() {
        seedClient(TEST_NAME_PREFIX + "SHAPE", TEST_RUC_PREFIX + "099099099", true);
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/clients?q=" + TEST_NAME_PREFIX + "SHAPE")
        .then()
            .statusCode(200)
            .body("page", notNullValue())
            .body("size", notNullValue())
            .body("totalElements", notNullValue())
            .body("totalPages", notNullValue())
            .body("numberOfElements", notNullValue())
            .body("first", notNullValue())
            .body("last", notNullValue())
            .body("empty", notNullValue())
            .body("content", notNullValue())
            .body("content[0].id", notNullValue())
            .body("content[0].name", equalTo(TEST_NAME_PREFIX + "SHAPE"))
            .body("content[0].ruc", equalTo(TEST_RUC_PREFIX + "099099099"))
            .body("content[0].phone", nullValue())
            .body("content[0].contactName", nullValue())
            .body("content[0].isActive", equalTo(true))
            .body("content[0].createdAt", notNullValue());
    }

    @Test
    void list_returnsApplicationJsonContentType() {
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/clients")
        .then()
            .statusCode(200)
            .contentType("application/json");
    }

    // ---------- Busqueda fuzzy q ---------------------------------------------

    @Test
    void list_withQMatchingName_returnsMatchingClients() {
        seedClient(TEST_NAME_PREFIX + "ACMECORP", TEST_RUC_PREFIX + "011111111", true);
        seedClient(TEST_NAME_PREFIX + "OTHERCO",  TEST_RUC_PREFIX + "022222222", true);
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/clients?q=ACMECORP")
        .then()
            .statusCode(200)
            .body("content.name", hasItem(TEST_NAME_PREFIX + "ACMECORP"))
            .body("content.name", not(hasItem(TEST_NAME_PREFIX + "OTHERCO")));
    }

    @Test
    void list_withQPartialRuc_matchesByRucSimilarity() {
        seedClient(TEST_NAME_PREFIX + "RUCMATCH", "90100200300", true);
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/clients?q=901002003")
        .then()
            .statusCode(200)
            .body("content.ruc", hasItem("90100200300"));
    }

    @Test
    void list_withQLowerCase_matchesUppercaseStoredName() {
        // name almacenado siempre uppercase (ver ClientResourceMapper.trimUpperOrNull).
        // El mapper uppercasea q antes de pasarlo al repo — pg_trgm es case-sensitive.
        seedClient(TEST_NAME_PREFIX + "CASETEST", TEST_RUC_PREFIX + "033333333", true);
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/clients?q=ztest_casetest")
        .then()
            .statusCode(200)
            .body("content.name", hasItem(TEST_NAME_PREFIX + "CASETEST"));
    }

    @Test
    void list_withShortQAsSubstring_matchesLongName_viaIlike() {
        // Regresion guard: q corto contra name largo debe matchear via ILIKE substring.
        // Con el operador pg_trgm `%` puro y threshold 0.3 esto FALLABA
        // (q=FER vs FERREYROS_LONG → similarity ~0.15 < 0.3). Con ILIKE matchea siempre.
        seedClient(TEST_NAME_PREFIX + "FERREYROS_LONG_NAME_SAC", TEST_RUC_PREFIX + "088100100", true);
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/clients?q=FERRE")
        .then()
            .statusCode(200)
            .body("content.name", hasItem(TEST_NAME_PREFIX + "FERREYROS_LONG_NAME_SAC"));
    }

    @Test
    void list_withQNoMatch_returnsEmptyPage() {
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/clients?q=ZZZNADAEXISTE_xyzzy_999")
        .then()
            .statusCode(200)
            .body("content.size()", equalTo(0))
            .body("totalElements", equalTo(0))
            .body("totalPages", equalTo(0))
            .body("numberOfElements", equalTo(0))
            .body("first", equalTo(true))
            .body("last", equalTo(true))
            .body("empty", equalTo(true));
    }

    @Test
    void list_withQEmptyString_treatedAsNoFilter() {
        // q="" debe normalizarse a sin filtro (lock-in del trim del ResourceMapper).
        seedClient(TEST_NAME_PREFIX + "ANY", TEST_RUC_PREFIX + "044444444", true);
        String token = login("admin", "Admin1234");

        int totalWithoutQ = given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/clients")
        .then()
            .statusCode(200)
            .extract().jsonPath().getInt("totalElements");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/clients?q=")
        .then()
            .statusCode(200)
            .body("totalElements", equalTo(totalWithoutQ));
    }

    // ---------- Filtro isActive ----------------------------------------------

    @Test
    void list_withIsActiveTrue_returnsOnlyActiveClients() {
        seedClient(TEST_NAME_PREFIX + "ACT", TEST_RUC_PREFIX + "055555551", true);
        seedClient(TEST_NAME_PREFIX + "INA", TEST_RUC_PREFIX + "055555552", false);
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/clients?q=" + TEST_NAME_PREFIX + "&isActive=true")
        .then()
            .statusCode(200)
            .body("content.isActive", everyItem(is(true)))
            .body("content.name", hasItem(TEST_NAME_PREFIX + "ACT"))
            .body("content.name", not(hasItem(TEST_NAME_PREFIX + "INA")));
    }

    @Test
    void list_withIsActiveFalse_returnsOnlyInactiveClients() {
        seedClient(TEST_NAME_PREFIX + "ACT", TEST_RUC_PREFIX + "066666661", true);
        seedClient(TEST_NAME_PREFIX + "INA", TEST_RUC_PREFIX + "066666662", false);
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/clients?q=" + TEST_NAME_PREFIX + "&isActive=false")
        .then()
            .statusCode(200)
            .body("content.isActive", everyItem(is(false)))
            .body("content.name", hasItem(TEST_NAME_PREFIX + "INA"))
            .body("content.name", not(hasItem(TEST_NAME_PREFIX + "ACT")));
    }

    @Test
    void list_withoutIsActive_returnsBothActiveAndInactive() {
        seedClient(TEST_NAME_PREFIX + "ACT", TEST_RUC_PREFIX + "077777771", true);
        seedClient(TEST_NAME_PREFIX + "INA", TEST_RUC_PREFIX + "077777772", false);
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/clients?q=" + TEST_NAME_PREFIX)
        .then()
            .statusCode(200)
            .body("content.name", hasItem(TEST_NAME_PREFIX + "ACT"))
            .body("content.name", hasItem(TEST_NAME_PREFIX + "INA"));
    }

    @Test
    void list_withMalformedIsActive_treatsAsFalse() {
        // Lock-in del binder JAX-RS Boolean.parseBoolean("maybe") == false.
        seedClient(TEST_NAME_PREFIX + "INA", TEST_RUC_PREFIX + "088888888", false);
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/clients?q=" + TEST_NAME_PREFIX + "INA&isActive=maybe")
        .then()
            .statusCode(200)
            .body("content.isActive", everyItem(is(false)));
    }

    // ---------- Paginacion ----------------------------------------------------

    @Test
    void list_withPage1Size5_returnsSecondPageOfFive() {
        for (int i = 1; i <= 12; i++) {
            seedClient(TEST_NAME_PREFIX + "P" + String.format("%02d", i),
                       TEST_RUC_PREFIX + "11" + String.format("%07d", i), true);
        }
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/clients?q=" + TEST_NAME_PREFIX + "P&page=1&size=5")
        .then()
            .statusCode(200)
            .body("page", equalTo(1))
            .body("size", equalTo(5))
            .body("numberOfElements", equalTo(5))
            .body("totalElements", equalTo(12))
            .body("totalPages", equalTo(3))
            .body("first", equalTo(false))
            .body("last", equalTo(false))
            .body("content.size()", equalTo(5));
    }

    @Test
    void list_withPage2Size5_returnsLastPartialPage() {
        for (int i = 1; i <= 12; i++) {
            seedClient(TEST_NAME_PREFIX + "P" + String.format("%02d", i),
                       TEST_RUC_PREFIX + "22" + String.format("%07d", i), true);
        }
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/clients?q=" + TEST_NAME_PREFIX + "P&page=2&size=5")
        .then()
            .statusCode(200)
            .body("page", equalTo(2))
            .body("numberOfElements", equalTo(2))
            .body("totalElements", equalTo(12))
            .body("first", equalTo(false))
            .body("last", equalTo(true))
            .body("content.size()", equalTo(2));
    }

    @Test
    void list_withSize100_boundaryMaxAccepted() {
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/clients?size=100")
        .then()
            .statusCode(200)
            .body("size", equalTo(100));
    }

    @Test
    void list_withPage0Size1_boundaryMinSize() {
        seedClient(TEST_NAME_PREFIX + "S1", TEST_RUC_PREFIX + "033111111", true);
        seedClient(TEST_NAME_PREFIX + "S2", TEST_RUC_PREFIX + "033111112", true);
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/clients?q=" + TEST_NAME_PREFIX + "S&page=0&size=1")
        .then()
            .statusCode(200)
            .body("size", equalTo(1))
            .body("numberOfElements", equalTo(1))
            .body("totalElements", equalTo(2))
            .body("totalPages", equalTo(2))
            .body("first", equalTo(true))
            .body("last", equalTo(false));
    }

    @Test
    void list_pageBeyondTotalPages_returnsEmptyContent() {
        seedClient(TEST_NAME_PREFIX + "OVERFLOW1", TEST_RUC_PREFIX + "044111111", true);
        seedClient(TEST_NAME_PREFIX + "OVERFLOW2", TEST_RUC_PREFIX + "044111112", true);
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/clients?q=" + TEST_NAME_PREFIX + "OVERFLOW&page=99&size=20")
        .then()
            .statusCode(200)
            .body("content.size()", equalTo(0))
            .body("numberOfElements", equalTo(0))
            .body("totalElements", equalTo(2))
            .body("empty", equalTo(true))
            .body("last", equalTo(true));
    }

    // ---------- Orden ASC por name (sin q) -----------------------------------

    @Test
    void list_resultsOrderedByNameAscending() {
        // Sin q: order primario es name ASC.
        // (Con q, el order primario es similarity DESC y el tiebreak es name ASC —
        // como nombres distintos tienen similarity distinta vs el mismo prefijo,
        // el ordering no es estrictamente ASC; ese caso lo cubre el test de fuzzy.)
        seedClient(TEST_NAME_PREFIX + "ZZZ", TEST_RUC_PREFIX + "055111111", true);
        seedClient(TEST_NAME_PREFIX + "AAA", TEST_RUC_PREFIX + "055111112", true);
        seedClient(TEST_NAME_PREFIX + "MMM", TEST_RUC_PREFIX + "055111113", true);
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/clients?size=100")
        .then()
            .statusCode(200)
            .body("content.name", containsInRelativeOrder(
                TEST_NAME_PREFIX + "AAA", TEST_NAME_PREFIX + "MMM", TEST_NAME_PREFIX + "ZZZ"
            ));
    }

    // ---------- Validacion 400 (Bean Validation en query params) -------------

    @Test
    void list_withPageNegative_returns400_COM001() {
        // Verifica codigo COM-001 + que el error apunta al parametro `page`.
        // ValidationExceptionMapper trunca el prefijo "methodName.argN." y deja
        // solo el nombre del param (vacio en este caso) o el path del field.
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/clients?page=-1")
        .then()
            .statusCode(400)
            .contentType("application/problem+json")
            .body("code", equalTo("COM-001"))
            .body("errors", notNullValue())
            .body("errors.size()", greaterThanOrEqualTo(1));
    }

    @Test
    void list_withSizeZero_returns400_COM001() {
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/clients?size=0")
        .then()
            .statusCode(400)
            .body("code", equalTo("COM-001"))
            .body("errors", notNullValue())
            .body("errors.size()", greaterThanOrEqualTo(1));
    }

    @Test
    void list_withSizeAboveMax_returns400_COM001() {
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/clients?size=101")
        .then()
            .statusCode(400)
            .body("code", equalTo("COM-001"))
            .body("errors", notNullValue())
            .body("errors.size()", greaterThanOrEqualTo(1));
    }

    @Test
    void list_withPageNotANumber_returns400Or404() {
        // Binder JAX-RS falla al parsear "abc" → int. RestEasy Reactive responde
        // 400 (sin Problem) o 404 segun el dispatcher. Lock-in al rango exacto
        // que se observa hoy — si cambia a 5xx en una version futura, este test
        // se rompe (intencionalmente).
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/clients?page=abc")
        .then()
            .statusCode(anyOf(equalTo(400), equalTo(404)));
    }

    @Test
    void list_withSizeNotANumber_returns400Or404() {
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/clients?size=xyz")
        .then()
            .statusCode(anyOf(equalTo(400), equalTo(404)));
    }

    // ---------- Auth 401 ------------------------------------------------------

    @Test
    void list_withoutToken_returns401() {
        given()
        .when()
            .get("/clients")
        .then()
            .statusCode(401);
    }

    @Test
    void list_withMalformedToken_returns401_AUTH008() {
        given()
            .header("Authorization", "Bearer eyJ.malformed.token")
        .when()
            .get("/clients")
        .then()
            .statusCode(401)
            .contentType("application/problem+json")
            .body("code", equalTo("AUTH-008"));
    }

    @Test
    void list_withExpiredToken_returns401_AUTH007() {
        Instant past = Instant.now().minusSeconds(120);
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
            .get("/clients")
        .then()
            .statusCode(401)
            .contentType("application/problem+json")
            .body("code", equalTo("AUTH-007"));
    }

    // ---------- Authorization: cualquier rol autenticado puede listar --------

    @Test
    void list_withAdminRole_returns200() {
        String token = login("admin", "Admin1234");
        given().header("Authorization", "Bearer " + token)
        .when().get("/clients")
        .then().statusCode(200);
    }

    @Test
    void list_withSalesRole_returns200() {
        String token = login("lcampos", "Sales1234");
        given().header("Authorization", "Bearer " + token)
        .when().get("/clients")
        .then().statusCode(200);
    }

    @Test
    void list_withDispatcherRole_returns200() {
        // Sin x-required-roles: dispatcher tambien puede listar (a diferencia de POST).
        String token = fabricateAccessToken("disp_test", "dispatcher");
        given().header("Authorization", "Bearer " + token)
        .when().get("/clients")
        .then().statusCode(200);
    }

    @Test
    void list_withOperationsManagerRole_returns200() {
        String token = fabricateAccessToken("ops_test", "operations_manager");
        given().header("Authorization", "Bearer " + token)
        .when().get("/clients")
        .then().statusCode(200);
    }
}
