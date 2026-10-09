package com.scaramutti.tms.auth;

import com.scaramutti.tms.shared.repository.UserRepository;
import com.scaramutti.tms.support.HermeticTestData;
import com.scaramutti.tms.support.TestAuth;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import io.restassured.response.ValidatableResponse;
import io.smallrye.jwt.build.Jwt;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Solo el token de acceso es credencial del API. El de renovación, firmado con la misma clave,
 * entraba a toda ruta sin rol; ahora toda ruta con sesión lo rechaza como token inválido (o
 * vencido, si ya venció), y su único uso sigue siendo el cuerpo de {@code /auth/refresh}.
 */
@QuarkusTest
class AccessTokenOnlyResourceTest {

    /**
     * Las rutas GET que solo exigían sesión, sin rol: las que el token de renovación abría. Los
     * clientes y el cambio de contraseña, que también lo eran, tienen su propio caso.
     */
    private static final String[] SESSION_ONLY_PATHS = {
        "/auth/me",
        "/currencies",
        "/payment-terms",
        "/quotation-conditions",
        "/quotation-service-types",
        "/cargo-types",
    };

    @Inject HermeticTestData data;
    @Inject UserRepository userRepository;

    @AfterEach
    void cleanup() {
        data.cleanup();
    }

    @Test
    void refreshTokenAsCredential_onEverySessionOnlyRoute_returns401TokenInvalid() {
        String refreshToken = adminSession().getString("refreshToken");

        for (String path : SESSION_ONLY_PATHS) {
            expectTokenInvalid(given().header("Authorization", "Bearer " + refreshToken).when().get(path).then());
        }
    }

    @Test
    void refreshTokenAsCredential_onClients_returns401TokenInvalid() {
        int clientId = data.seedClient();
        String refreshToken = adminSession().getString("refreshToken");

        expectTokenInvalid(given().header("Authorization", "Bearer " + refreshToken)
            .queryParam("q", HermeticTestData.PREFIX).when().get("/clients").then());
        expectTokenInvalid(given().header("Authorization", "Bearer " + refreshToken)
            .when().get("/clients/" + clientId).then());
    }

    /** Antes respondía 403 por casualidad: el token de renovación no lleva roles. */
    @Test
    void refreshTokenAsCredential_onRoleProtectedRoute_returns401NotForbidden() {
        String refreshToken = adminSession().getString("refreshToken");

        expectTokenInvalid(given().header("Authorization", "Bearer " + refreshToken)
            .queryParam("q", "admin").when().get("/workers").then());
    }

    /**
     * La contraseña nueva es la misma: si la regla fallara, admin seguiría entrando con la suya
     * aunque la corrida se cortara. El hash cambiaría igual (otra sal), y eso es lo que se mide; el
     * cierre restaura el hash exacto desde la base.
     */
    @Test
    void refreshTokenAsCredential_onChangePassword_returns401AndThePasswordStaysTheSame() {
        String refreshToken = adminSession().getString("refreshToken");
        String hashBefore = adminPasswordHash();
        try {
            expectTokenInvalid(given().header("Authorization", "Bearer " + refreshToken)
                .contentType(ContentType.JSON)
                .body("{\"currentPassword\":\"" + TestAuth.ADMIN_PASSWORD
                    + "\",\"newPassword\":\"" + TestAuth.ADMIN_PASSWORD + "\"}")
                .when().post("/auth/change-password").then());

            assertEquals(hashBefore, adminPasswordHash());
        } finally {
            restoreAdminPasswordHash(hashBefore);
        }
    }

    @Test
    void accessToken_stillOpensEverySessionOnlyRoute_andTheRoleProtectedOne() {
        int clientId = data.seedClient();
        String accessToken = adminSession().getString("token");

        for (String path : SESSION_ONLY_PATHS) {
            given().header("Authorization", "Bearer " + accessToken).when().get(path).then().statusCode(200);
        }
        given().header("Authorization", "Bearer " + accessToken)
            .queryParam("q", HermeticTestData.PREFIX).when().get("/clients").then().statusCode(200);
        given().header("Authorization", "Bearer " + accessToken)
            .when().get("/clients/" + clientId).then().statusCode(200);
        given().header("Authorization", "Bearer " + accessToken)
            .queryParam("q", "admin").when().get("/workers").then().statusCode(200);
    }

    /**
     * Sin token sigue siendo la identidad anónima: el 401 sale sin cuerpo, no como token inválido.
     * Sin la guarda del anónimo, la regla lo rechazaría con AUTH-008.
     */
    @Test
    void noToken_isStillAnonymous_notAnInvalidToken() {
        given().when().get("/auth/me")
            .then().statusCode(401).header("WWW-Authenticate", "Bearer").body(emptyString());
    }

    /** Caracterización: la salud es pública y, con la autenticación perezosa, la regla ni corre. */
    @Test
    void characterization_healthWithoutToken_isPublic() {
        given().when().get("/q/health").then().statusCode(200);
    }

    /** Con la autenticación anticipada, el login con este token en la cabecera respondería 401. */
    @Test
    void publicRoutes_ignoreARefreshTokenInTheHeader() {
        String refreshToken = adminSession().getString("refreshToken");

        given().header("Authorization", "Bearer " + refreshToken)
            .contentType(ContentType.JSON)
            .body(credentials())
            .when().post("/auth/login")
            .then().statusCode(200).body("token", notNullValue());
        given().header("Authorization", "Bearer " + refreshToken)
            .when().get("/q/health").then().statusCode(200);
        given().header("Authorization", "Bearer " + refreshToken)
            .contentType(ContentType.JSON)
            .body("{\"refreshToken\":\"" + refreshToken + "\"}")
            .when().post("/auth/refresh")
            .then().statusCode(200).body("token", notNullValue());
    }

    @Test
    void signedTokenWithoutTypeClaim_isNotAnAccessCredential() {
        Instant now = Instant.now();
        String untyped = Jwt.subject("1").upn("admin").groups(Set.of("admin"))
            .issuedAt(now).expiresAt(now.plusSeconds(3600)).sign();

        expectTokenInvalid(given().header("Authorization", "Bearer " + untyped).when().get("/auth/me").then());
    }

    /** La comparación es exacta: ni otra marca, ni la misma en mayúsculas, ni con algo de más. */
    @ParameterizedTest
    @ValueSource(strings = {"otro", "ACCESS", "refresh", "accessx", " access", ""})
    void signedTokenWithAnotherTypeClaim_isNotAnAccessCredential(String type) {
        Instant now = Instant.now();
        String token = Jwt.subject("1").upn("admin").groups(Set.of("admin")).claim("typ", type)
            .issuedAt(now).expiresAt(now.plusSeconds(3600)).sign();

        expectTokenInvalid(given().header("Authorization", "Bearer " + token).when().get("/auth/me").then());
    }

    /** Una marca que no es texto no es la de acceso: ni un número, ni una que envuelva la palabra. */
    @ParameterizedTest
    @MethodSource("nonTextualTypeClaims")
    void signedTokenWithANonTextualTypeClaim_isNotAnAccessCredential(Object type) {
        Instant now = Instant.now();
        String token = Jwt.subject("1").upn("admin").groups(Set.of("admin")).claim("typ", type)
            .issuedAt(now).expiresAt(now.plusSeconds(3600)).sign();

        expectTokenInvalid(given().header("Authorization", "Bearer " + token).when().get("/auth/me").then());
    }

    static Stream<Object> nonTextualTypeClaims() {
        return Stream.of(1, true, List.of("access"), Map.of("v", "access"));
    }

    /**
     * Caracterización, no guarda del augmentor: el vencimiento se revisa antes de que corra, así que
     * un token de renovación vencido responde vencido. Fija lo que el contrato promete.
     */
    @Test
    void characterization_expiredRefreshTokenAsCredential_isReportedAsExpired() {
        Instant past = Instant.now().minusSeconds(60);
        String expiredRefresh = Jwt.subject("1").upn("admin").claim("typ", "refresh")
            .issuedAt(past.minusSeconds(60)).expiresAt(past).sign();

        given().header("Authorization", "Bearer " + expiredRefresh).when().get("/auth/me")
            .then().statusCode(401).body("code", equalTo("AUTH-007"));
    }

    private static String credentials() {
        return "{\"username\":\"" + TestAuth.ADMIN_USERNAME + "\",\"password\":\"" + TestAuth.ADMIN_PASSWORD + "\"}";
    }

    private static JsonPath adminSession() {
        return given().contentType(ContentType.JSON)
            .body(credentials())
            .when().post("/auth/login")
            .then().statusCode(200).extract().jsonPath();
    }

    private static void expectTokenInvalid(ValidatableResponse response) {
        response.statusCode(401)
            .contentType("application/problem+json")
            .body("code", equalTo("AUTH-008"));
    }

    private String adminPasswordHash() {
        return QuarkusTransaction.requiringNew().call(() ->
            userRepository.findByUsername(TestAuth.ADMIN_USERNAME).orElseThrow().passwordHash);
    }

    private void restoreAdminPasswordHash(String hash) {
        QuarkusTransaction.requiringNew().run(() ->
            userRepository.findByUsername(TestAuth.ADMIN_USERNAME).orElseThrow().passwordHash = hash);
    }
}
