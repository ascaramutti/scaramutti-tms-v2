package com.scaramutti.tms.auth;

import com.scaramutti.tms.support.WarehouseTestData;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * BCrypt procesa hasta 72 bytes UTF-8 y lanza desde 73. El pedido rechaza la clave que no cabe
 * antes de buscar al usuario: en el login, la respuesta no cambia segun exista o no.
 *
 * <p>Las claves se cambian solo en una cuenta de prueba (ztestuser%), borrada antes y despues de
 * cada caso: una corrida cortada no deja ninguna cuenta sembrada con otra clave.
 */
@QuarkusTest
class PasswordByteLimitTest {

    private static final String TEST_USERNAME = "ztestuserclave1";
    /** La contrasena de {@code sales}: la cuenta de prueba copia su hash. */
    private static final String TEST_PASSWORD = "Sales1234";

    private static final int ENYE = 0xF1;
    private static final int A_WITH_ACCENT = 0xE1;
    private static final int EMOJI = 0x1F600;
    private static final String TOO_LONG_MESSAGE =
        "debe tener como maximo 72 bytes en UTF-8 (una tilde ocupa 2 y un emoji, 3 o mas), sin caracteres nulos ni surrogates sueltos";

    @Inject WarehouseTestData fixtures;
    @Inject EntityManager entityManager;

    @BeforeEach
    @AfterEach
    void cleanupFixtures() {
        fixtures.deleteTestWorkerDependents();
        QuarkusTransaction.requiringNew().run(() -> fixtures.deleteTestWorkers());
    }

    // ---------- login ---------------------------------------------------------

    /**
     * La validacion corre antes de buscar al usuario: con una clave que no cabe, el login responde
     * igual a una cuenta activa, a una inactiva y a una que no existe, salvo el traceId del pedido.
     */
    @Test
    void login_withAPasswordOver72Bytes_answersTheSame_whetherTheUserExistsOrNot() {
        assertEquals(List.of(false), isActiveOf("inactivo"), "precondicion: inactivo existe y esta inactiva");
        assertEquals(List.of(), isActiveOf("ztestnoexiste"), "precondicion: ztestnoexiste no existe");
        String tooLong = "a".repeat(73);

        Response active = login("sales", tooLong);
        Response inactive = login("inactivo", tooLong);
        Response missing = login("ztestnoexiste", tooLong);

        active.then()
            .statusCode(400)
            .contentType("application/problem+json")
            .body("code", equalTo("COM-001"))
            .body("errors.field", contains("password"))
            .body("errors.message", contains(TOO_LONG_MESSAGE));
        assertEquals(comparable(active), comparable(inactive));
        assertEquals(comparable(active), comparable(missing));
    }

    /** El minimo lo cuenta el servidor en unidades UTF-16: 4 de estos emojis son 8 y llegan a BCrypt. */
    @Test
    void login_countsTheMinimumInUtf16Units_withItsOwnMessage() {
        login("sales", repeat(EMOJI, 4)).then()
            .statusCode(401)
            .body("code", equalTo("AUTH-001"));
        login("sales", repeat(EMOJI, 3) + "a").then()
            .statusCode(400)
            .body("errors.field", contains("password"))
            .body("errors.message", contains("debe tener al menos 8 caracteres"));
    }

    /** Sin la clave, el error sale una sola vez: lo da @NotBlank y la regla de BCrypt no lo repite. */
    @Test
    void login_withoutPassword_reportsItOnce() {
        given()
            .contentType("application/json; charset=UTF-8")
            .body("{\"username\":\"sales\"}")
        .when()
            .post("/auth/login")
        .then()
            .statusCode(400)
            .body("errors.field", contains("password"));
    }

    /** Salvo las ASCII, ninguna llega a 73 caracteres ni unidades UTF-16: el limite cuenta bytes. */
    static Stream<Arguments> over72Bytes() {
        return Stream.of(
            Arguments.of("ASCII x73", "a".repeat(73)),
            Arguments.of("enie x37 (74 bytes)", repeat(ENYE, 37)),
            Arguments.of("71 ASCII y una enie (73 bytes)", "a".repeat(71) + repeat(ENYE, 1)),
            Arguments.of("emoji x19 (76 bytes)", repeat(EMOJI, 19)),
            Arguments.of("ASCII x100, el maximo de antes", "a".repeat(100)),
            // Con el maximo de antes de vuelta en @Size, el campo traeria dos errores.
            Arguments.of("ASCII x101", "a".repeat(101)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("over72Bytes")
    void login_withAPasswordOver72Bytes_returns400InThePasswordField(String name, String password) {
        login("sales", password).then()
            .statusCode(400)
            .body("code", equalTo("COM-001"))
            .body("errors.field", contains("password"));
    }

    static Stream<Arguments> exactly72Bytes() {
        return Stream.of(
            Arguments.of("ASCII x72", "a".repeat(72)),
            Arguments.of("enie x36", repeat(ENYE, 36)),
            Arguments.of("emoji x18", repeat(EMOJI, 18)));
    }

    /** Con 72 bytes la clave llega a BCrypt: equivocada, es el 401 de siempre, no un 400. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("exactly72Bytes")
    void login_withExactly72Bytes_reachesThePasswordCheck(String name, String password) {
        login("sales", password).then()
            .statusCode(401)
            .body("code", equalTo("AUTH-001"));
    }

    /**
     * Sin la regla entrarian los dos: la libreria da por buena una clave de 71 bytes con un nulo al
     * final, y el surrogate suelto llega como el signo de pregunta de la clave real.
     */
    @Test
    void login_withANulOrALoneSurrogate_returns400() {
        seedTestAccount();
        String seventyOne = "a".repeat(71);
        changePassword(accessToken(TEST_USERNAME, TEST_PASSWORD), TEST_PASSWORD, seventyOne).then().statusCode(204);
        login(TEST_USERNAME, seventyOne + jsonEscape(0x0000)).then()
            .statusCode(400)
            .body("errors.field", contains("password"));

        changePassword(accessToken(TEST_USERNAME, seventyOne), seventyOne, "Clave123?").then().statusCode(204);
        login(TEST_USERNAME, "Clave123" + jsonEscape(0xD800)).then()
            .statusCode(400)
            .body("errors.field", contains("password"));
    }

    // ---------- cambiar contrasena -------------------------------------------

    @ParameterizedTest(name = "{0}")
    @MethodSource("over72Bytes")
    void changePassword_withACurrentPasswordOver72Bytes_returns400InThatField_andKeepsTheHash(String name, String password) {
        int userId = seedTestAccount();
        String token = accessToken(TEST_USERNAME, TEST_PASSWORD);
        String hashBefore = passwordHashOf(userId);

        changePassword(token, password, "OtraClave123").then()
            .statusCode(400)
            .body("code", equalTo("COM-001"))
            .body("errors.field", contains("currentPassword"));

        assertEquals(hashBefore, passwordHashOf(userId));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("over72Bytes")
    void changePassword_withANewPasswordOver72Bytes_returns400InThatField_andKeepsTheHash(String name, String password) {
        int userId = seedTestAccount();
        String token = accessToken(TEST_USERNAME, TEST_PASSWORD);
        String hashBefore = passwordHashOf(userId);

        changePassword(token, TEST_PASSWORD, password).then()
            .statusCode(400)
            .body("code", equalTo("COM-001"))
            .body("errors.field", contains("newPassword"));

        assertEquals(hashBefore, passwordHashOf(userId));
    }

    /** Los dos campos cuentan el minimo en unidades UTF-16 y lo avisan con su propio texto. */
    @Test
    void changePassword_countsTheMinimumInUtf16Units_withItsOwnMessage() {
        seedTestAccount();
        String token = accessToken(TEST_USERNAME, TEST_PASSWORD);
        String sevenUnits = repeat(EMOJI, 3) + "a";

        changePassword(token, sevenUnits, "OtraClave123").then()
            .statusCode(400)
            .body("errors.field", contains("currentPassword"))
            .body("errors.message", contains("debe tener al menos 8 caracteres"));
        changePassword(token, TEST_PASSWORD, sevenUnits).then()
            .statusCode(400)
            .body("errors.field", contains("newPassword"))
            .body("errors.message", contains("debe tener al menos 8 caracteres"));
        changePassword(token, repeat(EMOJI, 4), "OtraClave123").then()
            .statusCode(400)
            .body("code", equalTo("AUTH-004"));
        changePassword(token, TEST_PASSWORD, repeat(EMOJI, 4)).then()
            .statusCode(204);
    }

    @Test
    void changePassword_withANulOrALoneSurrogate_returns400InThatField() {
        seedTestAccount();
        String token = accessToken(TEST_USERNAME, TEST_PASSWORD);

        changePassword(token, TEST_PASSWORD, "OtraClave123" + jsonEscape(0x0000)).then()
            .statusCode(400)
            .body("errors.field", contains("newPassword"));
        changePassword(token, TEST_PASSWORD + jsonEscape(0xDC00), "OtraClave123").then()
            .statusCode(400)
            .body("errors.field", contains("currentPassword"));
    }

    /** 72 bytes entran como nueva, sirven para entrar y despues como la actual. */
    @Test
    void changePassword_toA72BytePassword_works_andTheAccountLogsInWithIt() {
        seedTestAccount();
        String emojis = repeat(EMOJI, 18);
        String accents = repeat(A_WITH_ACCENT, 36);

        changePassword(accessToken(TEST_USERNAME, TEST_PASSWORD), TEST_PASSWORD, emojis).then()
            .statusCode(204);
        String token = accessToken(TEST_USERNAME, emojis);

        changePassword(token, emojis, accents).then()
            .statusCode(204);
        accessToken(TEST_USERNAME, accents);
    }

    // ---------- ayudas ---------------------------------------------------------

    private static String repeat(int codePoint, int times) {
        return new String(Character.toChars(codePoint)).repeat(times);
    }

    /**
     * El escape de JSON de esa unidad: escritos tal cual no llegan como tales, porque un nulo crudo
     * no es JSON valido y el surrogate suelto sale como "?" al codificar el cuerpo en UTF-8.
     */
    private static String jsonEscape(int codeUnit) {
        return String.format("\\u%04x", codeUnit);
    }

    /** Las claves de esta clase no llevan comillas, y sus barras son los escapes de jsonEscape: van tal cual. */
    private static String json(String first, String firstValue, String second, String secondValue) {
        return "{\"" + first + "\":\"" + firstValue + "\",\"" + second + "\":\"" + secondValue + "\"}";
    }

    private static Response login(String username, String password) {
        return given()
            .contentType("application/json; charset=UTF-8")
            .body(json("username", username, "password", password).getBytes(StandardCharsets.UTF_8))
        .when()
            .post("/auth/login");
    }

    private static Response changePassword(String token, String currentPassword, String newPassword) {
        return given()
            .header("Authorization", "Bearer " + token)
            .contentType("application/json; charset=UTF-8")
            .body(json("currentPassword", currentPassword, "newPassword", newPassword).getBytes(StandardCharsets.UTF_8))
        .when()
            .post("/auth/change-password");
    }

    private static String accessToken(String username, String password) {
        return login(username, password).then().statusCode(200).extract().path("token");
    }

    /** Lo que ve quien llama, sin el traceId, que es distinto en cada pedido. */
    private static List<Object> comparable(Response response) {
        Map<String, Object> body = new HashMap<>(response.jsonPath().getMap("$"));
        body.remove("traceId");
        return List.of(response.statusCode(), response.contentType(), body);
    }

    private int seedTestAccount() {
        int workerId = fixtures.seedWorker("ZTESTCLAVE1", "Clave", "Larga", "sales", true);
        int userId = fixtures.seedUserFor(workerId, TEST_USERNAME, "sales");
        fixtures.copyPasswordHashFrom(userId, "sales");
        return userId;
    }

    private List<?> isActiveOf(String username) {
        return QuarkusTransaction.requiringNew().call(() -> entityManager.createNativeQuery(
                "SELECT is_active FROM public.users WHERE username = ?1")
            .setParameter(1, username).getResultList());
    }

    private String passwordHashOf(int userId) {
        return QuarkusTransaction.requiringNew().call(() -> (String) entityManager.createNativeQuery(
                "SELECT password_hash FROM public.users WHERE id = ?1")
            .setParameter(1, userId).getSingleResult());
    }
}
