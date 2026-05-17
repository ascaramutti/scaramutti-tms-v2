package com.scaramutti.tms.clients;

import com.scaramutti.tms.shared.entity.Client;
import com.scaramutti.tms.shared.repository.ClientRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.smallrye.jwt.build.Jwt;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
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
}
