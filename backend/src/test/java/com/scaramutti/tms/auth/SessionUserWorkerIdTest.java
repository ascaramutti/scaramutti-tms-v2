package com.scaramutti.tms.auth;

import com.scaramutti.tms.support.WarehouseTestData;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.ExtractableResponse;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * La sesion trae el id del trabajador de la cuenta en el ingreso, la renovacion y /auth/me. La
 * cuenta se siembra con un id distinto del de su trabajador: en admin y sales, las cuentas de los
 * tests, coinciden (1 y 1, 2 y 2), y con ellas mandar el id de la cuenta pasaria verde.
 */
@QuarkusTest
class SessionUserWorkerIdTest {

    private static final String USERNAME = "ztestusersesion1";
    /** La contrasena de {@code sales}: la cuenta de prueba copia su hash. */
    private static final String PASSWORD = "Sales1234";

    @Inject WarehouseTestData fixtures;
    @Inject EntityManager entityManager;

    private record Account(int userId, int workerId) {}

    @BeforeEach
    @AfterEach
    void cleanupFixtures() {
        fixtures.deleteTestWorkerDependents();
        QuarkusTransaction.requiringNew().run(() -> fixtures.deleteTestWorkers());
    }

    @Test
    void login_bringsTheWorkerOfTheAccount_notTheAccountId() {
        Account account = seedAccountWhoseIdIsNotItsWorkers();

        given().contentType(ContentType.JSON).body(loginBody())
        .when().post("/auth/login")
        .then()
            .statusCode(200)
            .body("user.id", equalTo(account.userId()))
            .body("user.workerId", equalTo(account.workerId()));
    }

    @Test
    void refresh_bringsTheWorkerOfTheAccount_notTheAccountId() {
        Account account = seedAccountWhoseIdIsNotItsWorkers();
        String refreshToken = login().path("refreshToken");

        given().contentType(ContentType.JSON).body("{\"refreshToken\":\"" + refreshToken + "\"}")
        .when().post("/auth/refresh")
        .then()
            .statusCode(200)
            .body("user.id", equalTo(account.userId()))
            .body("user.workerId", equalTo(account.workerId()));
    }

    @Test
    void me_bringsTheWorkerOfTheAccount_notTheAccountId() {
        Account account = seedAccountWhoseIdIsNotItsWorkers();
        String accessToken = login().path("token");

        given().header("Authorization", "Bearer " + accessToken)
        .when().get("/auth/me")
        .then()
            .statusCode(200)
            .body("id", equalTo(account.userId()))
            .body("workerId", equalTo(account.workerId()));
    }

    private static String loginBody() {
        return "{\"username\":\"" + USERNAME + "\",\"password\":\"" + PASSWORD + "\"}";
    }

    private static ExtractableResponse<Response> login() {
        return given().contentType(ContentType.JSON).body(loginBody())
            .when().post("/auth/login").then().statusCode(200).extract();
    }

    /**
     * Las secuencias de cuentas y de trabajadores pueden ir parejas (por eso admin y sales salen 1 y
     * 1, 2 y 2), y entonces la cuenta nueva sale con el id de su trabajador. En ese caso pasa a un
     * trabajador sembrado despues, cuyo id ya es mayor: la condicion no depende de las secuencias.
     */
    private Account seedAccountWhoseIdIsNotItsWorkers() {
        int firstWorkerId = fixtures.seedWorker("ZTESTSES1", "Sesion", "Propia", "sales", true);
        int userId = fixtures.seedUserFor(firstWorkerId, USERNAME, "sales");
        int workerId = firstWorkerId;
        if (userId == firstWorkerId) {
            int laterWorkerId = fixtures.seedWorker("ZTESTSES2", "Sesion", "Propia", "sales", true);
            int moved = QuarkusTransaction.requiringNew().call(() -> entityManager.createNativeQuery(
                    "UPDATE public.users SET worker_id = ?1 WHERE id = ?2 AND username LIKE 'ztestuser%'")
                .setParameter(1, laterWorkerId).setParameter(2, userId).executeUpdate());
            assertEquals(1, moved, "no se pudo pasar la cuenta de prueba al otro trabajador");
            workerId = laterWorkerId;
        }
        fixtures.copyPasswordHashFrom(userId, "sales");
        assertNotEquals(userId, workerId, "precondicion: la cuenta y su trabajador tienen que tener ids distintos");
        return new Account(userId, workerId);
    }
}
