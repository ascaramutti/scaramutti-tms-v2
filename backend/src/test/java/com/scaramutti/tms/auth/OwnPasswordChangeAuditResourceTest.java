package com.scaramutti.tms.auth;

import com.scaramutti.tms.shared.util.DateUtils;
import com.scaramutti.tms.support.WarehouseTestData;
import com.scaramutti.tms.support.WarehouseTestData.UserAuditRow;
import com.scaramutti.tms.support.WarehouseTestData.UserAuthorship;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * El cambio de la propia contrasena deja su rastro: una fila "cambio su contrasena" firmada por la
 * propia cuenta y sin valores, y la autoria de la cuenta pasa a ella. Un cambio rechazado no deja
 * nada. Todo se afirma contra las filas releidas, no contra la respuesta, que no trae ni lo uno ni
 * lo otro.
 */
@QuarkusTest
class OwnPasswordChangeAuditResourceTest {

    private static final String USERNAME = "ztestuserrastro1";
    private static final String PASSWORD = "Sales1234";
    private static final String NEW_PASSWORD = "Nueva1234";

    @Inject WarehouseTestData fixtures;
    @Inject EntityManager entityManager;

    private int workerId;
    private int userId;

    @BeforeEach
    void seedAccount() {
        cleanupFixtures();
        workerId = fixtures.seedWorker("ZTESTRAS1", "Rastro", "Cuenta", "sales", true);
        userId = fixtures.seedUserFor(workerId, USERNAME, "sales");
        fixtures.copyPasswordHashFrom(userId, "sales");
    }

    @AfterEach
    void cleanupFixtures() {
        fixtures.deleteTestWorkerDependents();
        QuarkusTransaction.requiringNew().run(() -> fixtures.deleteTestWorkers());
    }

    @Test
    void aChange_leavesOneRowWithoutValues_signedByTheAccount_andMovesItsAuthorship() {
        UserAuthorship before = fixtures.userAuthorshipOf(userId);
        assertNull(before.updatedBy(), "precondicion: nadie la cambio todavia");
        String accessToken = accessToken();
        OffsetDateTime start = DateUtils.nowUtcMicros();

        changePassword(accessToken, PASSWORD).then().statusCode(200);

        OffsetDateTime end = DateUtils.nowUtcMicros();

        List<UserAuditRow> rows = fixtures.userAuditRows(userId);
        assertEquals(1, rows.size(), "una fila por cambio");
        UserAuditRow row = rows.get(0);
        assertEquals("PASSWORD_CHANGED|password|Contraseña|null|null|" + userId, row.changeType() + "|"
            + row.fieldName() + "|" + row.fieldLabel() + "|" + row.oldValue() + "|" + row.newValue() + "|"
            + row.changedBy());
        assertWithin(start, end, row.loggedAt(), "la hora de la fila es la del cambio");
        UserAuthorship after = fixtures.userAuthorshipOf(userId);
        assertEquals(userId, after.updatedBy(), "la firma la propia cuenta");
        assertWithin(start, end, after.updatedAt(), "la marca de la cuenta es la del cambio");
        assertEquals(before.createdBy(), after.createdBy(), "quien la creo no cambia");
        assertEquals(before.createdAt(), after.createdAt());
    }

    /** Con la actual equivocada no hay cambio: ni fila ni firma. */
    @Test
    void aChangeWithTheWrongCurrent_leavesNoRow_andKeepsTheAuthorship() {
        UserAuthorship before = fixtures.userAuthorshipOf(userId);

        changePassword(accessToken(), "Otra12345").then().statusCode(400).body("code", equalTo("AUTH-004"));

        assertEquals(List.of(), fixtures.userAuditRows(userId));
        assertEquals(before, fixtures.userAuthorshipOf(userId));
    }

    /** Con la actual correcta y la cuenta ya no utilizable, el rechazo llega con la fila tomada: tampoco firma. */
    @Test
    void aChangeOfAnUnusableAccount_leavesNoRow_andKeepsTheAuthorship() {
        String accessToken = accessToken();
        fixtures.setWorkerActive(workerId, false);
        UserAuthorship before = fixtures.userAuthorshipOf(userId);

        changePassword(accessToken, PASSWORD).then().statusCode(401).body("code", equalTo("AUTH-002"));

        assertEquals(List.of(), fixtures.userAuditRows(userId));
        assertEquals(before, fixtures.userAuthorshipOf(userId));
    }

    /** Ninguna fila del rastro guarda una contrasena ni un hash, ni la de antes ni la nueva. */
    @Test
    void noRowOfTheTrail_holdsAPasswordOrAHash() {
        String hashBefore = passwordHashOf(userId);

        changePassword(accessToken(), PASSWORD).then().statusCode(200);

        String hashAfter = passwordHashOf(userId);
        List<String> rows = fixtures.userAuditRowsAsText();
        assertFalse(rows.isEmpty(), "precondicion: el cambio dejo su fila");
        for (String row : rows) {
            for (String secret : List.of(PASSWORD, NEW_PASSWORD, hashBefore, hashAfter, "$2a$", "$2b$", "$2y$")) {
                assertFalse(row.contains(secret), "una fila del rastro guarda " + secret + ": " + row);
            }
        }
    }

    // ---------- fixtures ----------------------------------------------------------

    private static void assertWithin(OffsetDateTime start, OffsetDateTime end, OffsetDateTime value, String what) {
        assertNotNull(value, what);
        assertTrue(!value.isBefore(start) && !value.isAfter(end),
            what + ": " + value + " fuera de " + start + " a " + end);
    }

    private String passwordHashOf(int id) {
        return QuarkusTransaction.requiringNew().call(() -> (String) entityManager.createNativeQuery(
                "SELECT password_hash FROM public.users WHERE id = ?1")
            .setParameter(1, id).getSingleResult());
    }

    private static String accessToken() {
        return given().contentType(ContentType.JSON)
            .body("{\"username\":\"" + USERNAME + "\",\"password\":\"" + PASSWORD + "\"}")
            .when().post("/auth/login")
            .then().statusCode(200).extract().path("token");
    }

    private static Response changePassword(String accessToken, String currentPassword) {
        return given().header("Authorization", "Bearer " + accessToken).contentType(ContentType.JSON)
            .body("{\"currentPassword\":\"" + currentPassword + "\",\"newPassword\":\"" + NEW_PASSWORD + "\"}")
            .when().post("/auth/change-password");
    }
}
