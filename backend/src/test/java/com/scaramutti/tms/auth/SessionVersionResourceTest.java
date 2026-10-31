package com.scaramutti.tms.auth;

import com.scaramutti.tms.support.WarehouseTestData;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import io.restassured.response.ExtractableResponse;
import io.restassured.response.Response;
import io.smallrye.jwt.build.Jwt;
import io.smallrye.jwt.build.JwtClaimsBuilder;
import jakarta.inject.Inject;
import jakarta.json.JsonValue;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.stream.Stream;

import static com.scaramutti.tms.support.ResponseHeaders.assertNotSent;
import static com.scaramutti.tms.support.ResponseHeaders.assertSentOnce;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * La version de las sesiones: el refresh la lleva, la renovacion la compara por igualdad y el
 * cambio de la propia contrasena la sube y responde con una sesion nueva. Sobre una cuenta de prueba
 * que no se llama como su cargo, con la contrasena de {@code sales}.
 */
@QuarkusTest
class SessionVersionResourceTest {

    private static final String USERNAME = "ztestuserversion1";
    private static final String PASSWORD = "Sales1234";
    private static final String NEW_PASSWORD = "Nueva1234";

    @Inject WarehouseTestData fixtures;
    @Inject EntityManager entityManager;

    private int userId;

    @BeforeEach
    void seedAccount() {
        cleanupFixtures();
        int workerId = fixtures.seedWorker("ZTESTVER1", "Sesion", "Version", "sales", true);
        userId = fixtures.seedUserFor(workerId, USERNAME, "sales");
        fixtures.copyPasswordHashFrom(userId, "sales");
    }

    @AfterEach
    void cleanupFixtures() {
        fixtures.deleteTestWorkerDependents();
        QuarkusTransaction.requiringNew().run(() -> fixtures.deleteTestWorkers());
    }

    // ---------- la sesion nueva del cambio ----------------------------------------

    @Test
    void changePassword_returnsANewSession_andRaisesTheVersionByOne() {
        String hashBefore = passwordHashOf(userId);
        assertEquals(0, sessionVersionOf(userId));

        ExtractableResponse<Response> changed = changePassword(login().path("token"), PASSWORD).then()
            .statusCode(200)
            .body("token", notNullValue())
            .body("expiresAt", notNullValue())
            .body("expiresIn", greaterThan(0))
            .body("user.id", equalTo(userId))
            .body("user.username", equalTo(USERNAME))
            .extract();

        assertEquals(1, sessionVersionOf(userId));
        assertNotEquals(hashBefore, passwordHashOf(userId));
        assertEquals(1, versionClaimOf(changed.path("refreshToken")));
        assertNull(claimsOf(changed.path("token")).get("sessionVersion"), "el access no lleva la version");
    }

    /** Lleva tokens, como el ingreso, y es de la cuenta del token, como /auth/me: las tres, una vez. */
    @Test
    void changePassword_sendsNoStorePragmaAndVary_onceEach() {
        Response changed = changePassword(login().path("token"), PASSWORD);

        changed.then().statusCode(200);
        assertSentOnce(changed, "Cache-Control", "no-store");
        assertSentOnce(changed, "Pragma", "no-cache");
        assertSentOnce(changed, "Vary", "Authorization");
    }

    /** Van en el exito: el error no trae tokens. */
    @Test
    void changePassword_withTheWrongCurrent_sendsNoneOfThem_andKeepsTheVersion() {
        Response rejected = changePassword(login().path("token"), "Equivocada99");

        rejected.then().statusCode(400).body("code", equalTo("AUTH-004"));
        assertNotSent(rejected, "Cache-Control", "Pragma", "Vary");
        assertEquals(0, sessionVersionOf(userId));
    }

    @Test
    void changePassword_withATokenOfAMissingAccount_returns404UserNotFound() {
        Instant now = Instant.now();
        String ghost = Jwt.subject("999999").upn("ghost").groups(java.util.Set.of("sales")).claim("typ", "access")
            .issuedAt(now).expiresAt(now.plusSeconds(3600)).sign();

        changePassword(ghost, PASSWORD).then()
            .statusCode(404)
            .body("code", equalTo("AUTH-005"))
            .body("instance", equalTo("/auth/change-password"));
    }

    // ---------- la revocacion ------------------------------------------------------

    @Test
    void aRefreshIssuedBeforeTheChange_returns401Revoked() {
        ExtractableResponse<Response> session = login();
        changePassword(session.path("token"), PASSWORD).then().statusCode(200);

        refresh(session.path("refreshToken")).then()
            .statusCode(401)
            .body("code", equalTo("AUTH-003"))
            .body("title", equalTo("Invalid refresh token"))
            .body("detail", equalTo("Refresh token inválido o revocado"));
    }

    /**
     * Con la version anterior no renueva aunque se haya emitido en el segundo del cambio, o despues:
     * es el hueco que una comparacion por la hora de emision (que va en segundos) dejaria abierto.
     */
    @Test
    void aRefreshWithThePreviousVersion_isRevoked_evenIssuedInTheSecondOfTheChange() {
        changePassword(login().path("token"), PASSWORD).then().statusCode(200);
        Instant secondOfTheChange = Instant.now().truncatedTo(ChronoUnit.SECONDS);

        refresh(refreshTokenWith(0, secondOfTheChange)).then()
            .statusCode(401)
            .body("code", equalTo("AUTH-003"));
    }

    @Test
    void theRefreshOfTheNewSession_renews_andItsRenewalKeepsTheVersion() {
        String newRefresh = changePassword(login().path("token"), PASSWORD).then().statusCode(200)
            .extract().path("refreshToken");

        String renewed = refresh(newRefresh).then().statusCode(200).extract().path("refreshToken");

        assertEquals(1, versionClaimOf(renewed));
        refresh(renewed).then().statusCode(200).body("user.id", equalTo(userId));
    }

    /** El ingreso tambien emite con la version de la cuenta, no con la inicial. */
    @Test
    void aLoginAfterTheChange_issuesARefreshWithTheCurrentVersion_thatRenews() {
        changePassword(login().path("token"), PASSWORD).then().statusCode(200);

        String refreshToken = postLogin(NEW_PASSWORD).then().statusCode(200).extract().path("refreshToken");

        assertEquals(1, versionClaimOf(refreshToken));
        refresh(refreshToken).then().statusCode(200);
    }

    /** Uno emitido antes de este cambio, sin la version: renueva hasta el primer cambio, no despues. */
    @Test
    void aRefreshWithoutTheVersion_renewsUntilTheFirstChange() {
        String withoutVersion = refreshTokenWith(null, Instant.now());

        String renewed = refresh(withoutVersion).then().statusCode(200).extract().path("refreshToken");
        assertEquals(0, versionClaimOf(renewed));

        changePassword(login().path("token"), PASSWORD).then().statusCode(200);
        refresh(withoutVersion).then().statusCode(401).body("code", equalTo("AUTH-003"));
    }

    /** Con la version nula, como sin ella: la inicial, que renueva mientras la cuenta siga en 0. */
    @Test
    void aRefreshWithANullVersion_countsAsTheInitialOne() {
        String withNull = refreshTokenWith(JsonValue.NULL, Instant.now());
        assertTrue(new String(Base64.getUrlDecoder().decode(withNull.split("\\.")[1]), StandardCharsets.UTF_8)
            .contains("\"sessionVersion\":null"), "precondicion: el claim viaja, en null");

        refresh(withNull).then().statusCode(200);
    }

    /** Un entero fuera del rango de la columna no se recorta: 2^32 no es la version 0. */
    @Test
    void aRefreshWithAVersionBeyondTheIntRange_returns401Revoked() {
        refresh(refreshTokenWith(4_294_967_296L, Instant.now())).then()
            .statusCode(401).body("code", equalTo("AUTH-003"));
    }

    /** Por igualdad: una version que la cuenta todavia no alcanzo no es de ninguna sesion emitida. */
    @Test
    void aRefreshWithAVersionTheAccountHasNotReached_returns401Revoked() {
        refresh(refreshTokenWith(1, Instant.now())).then().statusCode(401).body("code", equalTo("AUTH-003"));
    }

    static Stream<Arguments> versionsThatAreNotIntegers() {
        return Stream.of(Arguments.of("0"), Arguments.of(new BigDecimal("0.0")), Arguments.of(true));
    }

    /** Con la cuenta en 0: ni el texto "0" ni el numero 0.0 son la version 0. */
    @ParameterizedTest
    @MethodSource("versionsThatAreNotIntegers")
    void aRefreshWhoseVersionIsNotAnInteger_returns401Revoked(Object version) {
        refresh(refreshTokenWith(version, Instant.now())).then().statusCode(401).body("code", equalTo("AUTH-003"));
    }

    /**
     * La cuenta va antes que la version: un refresh revocado de una cuenta que ademas dejo de ser
     * utilizable responde AUTH-002, como cualquier otro pedido de esa cuenta.
     */
    @Test
    void aRevokedRefreshOfAnUnusableAccount_returns401UserInactive() {
        String refreshToken = login().path("refreshToken");
        changePassword(login().path("token"), PASSWORD).then().statusCode(200);
        fixtures.setUserActive(userId, false);

        refresh(refreshToken).then().statusCode(401).body("code", equalTo("AUTH-002"));
    }

    /** El cambio de cargo no revoca: la cuenta sigue siendo la misma persona con la misma clave. */
    @Test
    void aChangeOfCargo_doesNotRevoke() {
        String refreshToken = login().path("refreshToken");

        fixtures.setUserRole(userId, "dispatcher");

        refresh(refreshToken).then().statusCode(200).body("user.role", equalTo("dispatcher"));
        assertEquals(0, sessionVersionOf(userId));
    }

    /**
     * La ventana que queda, a proposito: el access ya emitido no se revoca y vale hasta su
     * vencimiento, mas la tolerancia de reloj del verificador (60 s). Lo que corta el cambio es la renovacion.
     */
    @Test
    void theAccessTokenOfARevokedSession_staysValidUntilItExpires() {
        ExtractableResponse<Response> session = login();
        changePassword(session.path("token"), PASSWORD).then().statusCode(200);

        given().header("Authorization", "Bearer " + session.path("token"))
        .when().get("/auth/me")
        .then().statusCode(200).body("id", equalTo(userId));
        refresh(session.path("refreshToken")).then().statusCode(401);
    }

    /**
     * La tolerancia de reloj que el contrato suma a la ventana: un access vencido hace 50 s todavia
     * entra, uno vencido hace 70 s ya no. Lejos del borde de los 60 s, para que el tiempo del pedido
     * no lo mueva.
     */
    @Test
    void anExpiredAccessToken_isAcceptedOnlyWithinTheClockSkew() {
        given().header("Authorization", "Bearer " + accessTokenExpiredSecondsAgo(50))
        .when().get("/auth/me")
        .then().statusCode(200);
        given().header("Authorization", "Bearer " + accessTokenExpiredSecondsAgo(70))
        .when().get("/auth/me")
        .then().statusCode(401).body("code", equalTo("AUTH-007"));
    }

    // ---------- fixtures ----------------------------------------------------------

    private String accessTokenExpiredSecondsAgo(int seconds) {
        Instant expiresAt = Instant.now().minusSeconds(seconds);
        return Jwt.subject(String.valueOf(userId)).upn(USERNAME).groups(java.util.Set.of("sales"))
            .claim("typ", "access").issuedAt(expiresAt.minusSeconds(3600)).expiresAt(expiresAt).sign();
    }

    private int sessionVersionOf(int id) {
        return QuarkusTransaction.requiringNew().call(() -> ((Number) entityManager.createNativeQuery(
                "SELECT session_version FROM public.users WHERE id = ?1")
            .setParameter(1, id).getSingleResult()).intValue());
    }

    private String passwordHashOf(int id) {
        return QuarkusTransaction.requiringNew().call(() -> (String) entityManager.createNativeQuery(
                "SELECT password_hash FROM public.users WHERE id = ?1")
            .setParameter(1, id).getSingleResult());
    }

    /** Un refresh firmado a mano para la cuenta de prueba, con esa version (o sin ella, con null). */
    private String refreshTokenWith(Object sessionVersion, Instant issuedAt) {
        JwtClaimsBuilder claims = Jwt.subject(String.valueOf(userId)).upn(USERNAME).claim("typ", "refresh");
        if (sessionVersion != null) {
            claims.claim("sessionVersion", sessionVersion);
        }
        return claims.issuedAt(issuedAt).expiresAt(issuedAt.plusSeconds(3600)).sign();
    }

    private static JsonPath claimsOf(String token) {
        return JsonPath.from(new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]), StandardCharsets.UTF_8));
    }

    private static int versionClaimOf(String refreshToken) {
        JsonPath claims = claimsOf(refreshToken);
        assertFalse(claims.get("sessionVersion") == null, "el refresh no trae la version");
        return claims.getInt("sessionVersion");
    }

    private static ExtractableResponse<Response> login() {
        return postLogin(PASSWORD).then().statusCode(200).extract();
    }

    private static Response postLogin(String password) {
        return given().contentType(ContentType.JSON)
            .body("{\"username\":\"" + USERNAME + "\",\"password\":\"" + password + "\"}")
            .when().post("/auth/login");
    }

    private static Response refresh(String refreshToken) {
        return given().contentType(ContentType.JSON).body("{\"refreshToken\":\"" + refreshToken + "\"}")
            .when().post("/auth/refresh");
    }

    private static Response changePassword(String accessToken, String currentPassword) {
        return given().header("Authorization", "Bearer " + accessToken).contentType(ContentType.JSON)
            .body("{\"currentPassword\":\"" + currentPassword + "\",\"newPassword\":\"" + NEW_PASSWORD + "\"}")
            .when().post("/auth/change-password");
    }
}
