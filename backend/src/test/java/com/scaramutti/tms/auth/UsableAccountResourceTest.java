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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Solo una cuenta utilizable usa la sesion: activa, con su trabajador activo y un cargo que inicia
 * sesion. Cada estado se arma por SQL sobre una cuenta de prueba que no se llama como su cargo, y
 * las cuatro operaciones responden 401 AUTH-002 con el mismo cuerpo en los tres.
 */
@QuarkusTest
class UsableAccountResourceTest {

    private static final String USERNAME = "ztestuserutil1";
    /** La contrasena de {@code sales}: la cuenta de prueba copia su hash. */
    private static final String PASSWORD = "Sales1234";
    private static final String WRONG_PASSWORD = "Equivocada99";
    private static final String NEW_PASSWORD = "UtilizableNueva1";

    @Inject WarehouseTestData fixtures;
    @Inject EntityManager entityManager;

    private record Account(int userId, int workerId) {}

    /** Las tres maneras de que una cuenta deje de ser utilizable. */
    enum State { ACCOUNT_INACTIVE, WORKER_INACTIVE, ROLE_WITHOUT_LOGIN }

    /**
     * Cada operacion de la sesion, con lo que necesita de cuando la cuenta todavia era utilizable:
     * el ingreso, nada; las demas, los tokens de un ingreso anterior.
     */
    enum Operation {
        LOGIN("/auth/login") {
            @Override Response call(Runnable makeUnusable) {
                makeUnusable.run();
                return postLogin(PASSWORD);
            }
        },
        REFRESH("/auth/refresh") {
            @Override Response call(Runnable makeUnusable) {
                String refreshToken = login().path("refreshToken");
                makeUnusable.run();
                return postRefresh(refreshToken);
            }
        },
        CHANGE_PASSWORD("/auth/change-password") {
            @Override Response call(Runnable makeUnusable) {
                String accessToken = login().path("token");
                makeUnusable.run();
                return postChangePassword(accessToken, PASSWORD);
            }
        },
        ME("/auth/me") {
            @Override Response call(Runnable makeUnusable) {
                String accessToken = login().path("token");
                makeUnusable.run();
                return given().header("Authorization", "Bearer " + accessToken).when().get("/auth/me");
            }
        };

        final String instance;

        Operation(String instance) {
            this.instance = instance;
        }

        abstract Response call(Runnable makeUnusable);
    }

    @BeforeEach
    @AfterEach
    void cleanupFixtures() {
        fixtures.deleteTestWorkerDependents();
        QuarkusTransaction.requiringNew().run(() -> fixtures.deleteTestWorkers());
    }

    static Stream<Arguments> everyOperationInEveryState() {
        return Arrays.stream(Operation.values())
            .flatMap(operation -> Arrays.stream(State.values()).map(state -> Arguments.of(operation, state)));
    }

    @ParameterizedTest
    @MethodSource("everyOperationInEveryState")
    void anUnusableAccount_gets401UserInactive_inEverySessionOperation(Operation operation, State state) {
        Account account = seedUsableAccount();

        Response response = operation.call(() -> makeUnusable(account, state));

        response.then()
            .statusCode(401)
            .body("code", equalTo("AUTH-002"))
            .body("title", equalTo("User inactive"))
            .body("detail", equalTo("El usuario está desactivado"))
            .body("instance", equalTo(operation.instance));
    }

    /** "El mismo cuerpo": todo menos el traceId, que cambia en cada pedido. */
    @ParameterizedTest
    @EnumSource(Operation.class)
    void theBody_isTheSameForTheThreeStates(Operation operation) {
        Map<State, Map<String, Object>> bodies = new HashMap<>();
        for (State state : State.values()) {
            cleanupFixtures();
            Account account = seedUsableAccount();
            Map<String, Object> body = new HashMap<>(
                operation.call(() -> makeUnusable(account, state)).then().statusCode(401).extract().jsonPath().getMap(""));
            assertNotNull(body.remove("traceId"), "precondicion: el cuerpo trae traceId");
            bodies.put(state, body);
        }
        assertEquals(bodies.get(State.ACCOUNT_INACTIVE), bodies.get(State.WORKER_INACTIVE));
        assertEquals(bodies.get(State.ACCOUNT_INACTIVE), bodies.get(State.ROLE_WITHOUT_LOGIN));
    }

    @ParameterizedTest
    @EnumSource(State.class)
    void login_withTheWrongPassword_onAnUnusableAccount_returns401InvalidCredentials(State state) {
        Account account = seedUsableAccount();
        makeUnusable(account, state);

        postLogin(WRONG_PASSWORD).then()
            .statusCode(401)
            .body("code", equalTo("AUTH-001"));
    }

    @ParameterizedTest
    @EnumSource(State.class)
    void changePassword_onAnUnusableAccount_keepsTheHash(State state) {
        Account account = seedUsableAccount();
        String hashBefore = passwordHashOf(account.userId());

        Operation.CHANGE_PASSWORD.call(() -> makeUnusable(account, state)).then().statusCode(401);

        assertEquals(hashBefore, passwordHashOf(account.userId()));
    }

    /** La vigencia se mira antes que la contrasena actual: con la actual equivocada, igual AUTH-002. */
    @ParameterizedTest
    @EnumSource(State.class)
    void changePassword_onAnUnusableAccount_withTheWrongCurrent_returns401UserInactive(State state) {
        Account account = seedUsableAccount();
        String accessToken = login().path("token");
        String hashBefore = passwordHashOf(account.userId());
        makeUnusable(account, state);

        postChangePassword(accessToken, WRONG_PASSWORD).then()
            .statusCode(401)
            .body("code", equalTo("AUTH-002"));
        assertEquals(hashBefore, passwordHashOf(account.userId()));
    }

    @Test
    void aUsableAccount_stillUsesTheFourSessionOperations() {
        Account account = seedUsableAccount();
        ExtractableResponse<Response> login = login();
        assertEquals(account.userId(), login.<Integer>path("user.id"));

        postRefresh(login.path("refreshToken")).then().statusCode(200).body("user.id", equalTo(account.userId()));
        given().header("Authorization", "Bearer " + login.path("token"))
        .when().get("/auth/me")
        .then().statusCode(200).body("id", equalTo(account.userId()));

        String hashBefore = passwordHashOf(account.userId());
        postChangePassword(login.path("token"), PASSWORD).then().statusCode(204);
        assertNotEquals(hashBefore, passwordHashOf(account.userId()));
    }

    private Account seedUsableAccount() {
        int workerId = fixtures.seedWorker("ZTESTUTIL1", "Cuenta", "Utilizable", "sales", true);
        int userId = fixtures.seedUserFor(workerId, USERNAME, "sales");
        fixtures.copyPasswordHashFrom(userId, "sales");
        return new Account(userId, workerId);
    }

    /** "operator" no inicia sesion y no pide ficha de conductor. */
    private void makeUnusable(Account account, State state) {
        switch (state) {
            case ACCOUNT_INACTIVE -> fixtures.setUserActive(account.userId(), false);
            case WORKER_INACTIVE -> fixtures.setWorkerActive(account.workerId(), false);
            case ROLE_WITHOUT_LOGIN -> fixtures.setUserRole(account.userId(), "operator");
        }
    }

    private String passwordHashOf(int userId) {
        return QuarkusTransaction.requiringNew().call(() -> (String) entityManager.createNativeQuery(
                "SELECT password_hash FROM public.users WHERE id = ?1")
            .setParameter(1, userId).getSingleResult());
    }

    private static ExtractableResponse<Response> login() {
        return postLogin(PASSWORD).then().statusCode(200).extract();
    }

    private static Response postLogin(String password) {
        return given().contentType(ContentType.JSON)
            .body("{\"username\":\"" + USERNAME + "\",\"password\":\"" + password + "\"}")
            .when().post("/auth/login");
    }

    private static Response postRefresh(String refreshToken) {
        return given().contentType(ContentType.JSON).body("{\"refreshToken\":\"" + refreshToken + "\"}")
            .when().post("/auth/refresh");
    }

    private static Response postChangePassword(String accessToken, String currentPassword) {
        return given().header("Authorization", "Bearer " + accessToken).contentType(ContentType.JSON)
            .body("{\"currentPassword\":\"" + currentPassword + "\",\"newPassword\":\"" + NEW_PASSWORD + "\"}")
            .when().post("/auth/change-password");
    }
}
