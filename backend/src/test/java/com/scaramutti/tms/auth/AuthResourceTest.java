package com.scaramutti.tms.auth;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.smallrye.jwt.build.Jwt;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
class AuthResourceTest {

    @Test
    void login_withValidCredentials_returnsToken() {
        given()
            .contentType(ContentType.JSON)
            .body("""
                {"username":"lcampos","password":"Sales1234"}
                """)
        .when()
            .post("/auth/login")
        .then()
            .statusCode(200)
            .body("token", notNullValue())
            .body("refreshToken", notNullValue())
            .body("expiresIn", equalTo(3600))
            .body("user.username", equalTo("lcampos"))
            .body("user.role", equalTo("sales"))
            .body("user.fullName", equalTo("Carolina Campos"));
    }

    @Test
    void login_withWrongPassword_returns401WithProblem() {
        given()
            .contentType(ContentType.JSON)
            .body("""
                {"username":"lcampos","password":"WRONG12345"}
                """)
        .when()
            .post("/auth/login")
        .then()
            .statusCode(401)
            .contentType("application/problem+json")
            .body("code", equalTo("AUTH-001"))
            .body("status", equalTo(401))
            .body("traceId", notNullValue());
    }

    @Test
    void login_withEmptyUsername_returns400WithFieldErrors() {
        given()
            .contentType(ContentType.JSON)
            .body("""
                {"username":"","password":"Sales1234"}
                """)
        .when()
            .post("/auth/login")
        .then()
            .statusCode(400)
            .body("code", equalTo("COM-001"))
            .body("errors.field", org.hamcrest.Matchers.hasItem("username"));
    }

    @Test
    void me_withoutToken_returns401() {
        given()
        .when()
            .get("/auth/me")
        .then()
            .statusCode(401);
    }

    @Test
    void me_withInvalidToken_returnsTokenInvalid() {
        given()
            .header("Authorization", "Bearer eyJ.malformed.token")
        .when()
            .get("/auth/me")
        .then()
            .statusCode(401)
            .contentType("application/problem+json")
            .body("code", equalTo("AUTH-008"));
    }

    @Test
    void me_withValidToken_returnsCurrentUser() {
        String token = given()
            .contentType(ContentType.JSON)
            .body("""
                {"username":"admin","password":"Admin1234"}
                """)
        .when()
            .post("/auth/login")
        .then()
            .statusCode(200)
            .extract().jsonPath().getString("token");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/auth/me")
        .then()
            .statusCode(200)
            .body("username", equalTo("admin"))
            .body("role", equalTo("admin"));
    }

    @Test
    void refresh_withValidRefreshToken_returnsNewTokens() {
        String refreshToken = given()
            .contentType(ContentType.JSON)
            .body("""
                {"username":"admin","password":"Admin1234"}
                """)
        .when()
            .post("/auth/login")
        .then()
            .extract().jsonPath().getString("refreshToken");

        given()
            .contentType(ContentType.JSON)
            .body("{\"refreshToken\":\"" + refreshToken + "\"}")
        .when()
            .post("/auth/refresh")
        .then()
            .statusCode(200)
            .body("token", notNullValue())
            .body("refreshToken", notNullValue())
            .body("user.username", equalTo("admin"));
    }

    @Test
    void refresh_withAccessTokenInsteadOfRefresh_returns401() {
        String accessToken = given()
            .contentType(ContentType.JSON)
            .body("""
                {"username":"admin","password":"Admin1234"}
                """)
        .when()
            .post("/auth/login")
        .then()
            .extract().jsonPath().getString("token");

        // Mandar el access token donde se espera refresh → debe fallar
        given()
            .contentType(ContentType.JSON)
            .body("{\"refreshToken\":\"" + accessToken + "\"}")
        .when()
            .post("/auth/refresh")
        .then()
            .statusCode(401)
            .body("code", equalTo("AUTH-003"));
    }

    @Test
    void refresh_withMalformedToken_returns401() {
        given()
            .contentType(ContentType.JSON)
            .body("{\"refreshToken\":\"" + "x".repeat(250) + "\"}")
        .when()
            .post("/auth/refresh")
        .then()
            .statusCode(401)
            .body("code", equalTo("AUTH-003"));
    }

    @Test
    void changePassword_withCorrectCurrent_returns204() {
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body("""
                {"currentPassword":"Admin1234","newPassword":"AdminCambiado99"}
                """)
        .when()
            .post("/auth/change-password")
        .then()
            .statusCode(204);

        // Volvemos a la password original para no contaminar otros tests
        String tokenAfter = login("admin", "AdminCambiado99");
        given()
            .header("Authorization", "Bearer " + tokenAfter)
            .contentType(ContentType.JSON)
            .body("""
                {"currentPassword":"AdminCambiado99","newPassword":"Admin1234"}
                """)
        .when()
            .post("/auth/change-password")
        .then()
            .statusCode(204);
    }

    @Test
    void changePassword_withWrongCurrent_returns400() {
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body("""
                {"currentPassword":"WRONG12345","newPassword":"NuevaPass123"}
                """)
        .when()
            .post("/auth/change-password")
        .then()
            .statusCode(400)
            .body("code", equalTo("AUTH-004"));
    }

    @Test
    void changePassword_withWeakNewPassword_returns400WithValidation() {
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body("""
                {"currentPassword":"Admin1234","newPassword":"123"}
                """)
        .when()
            .post("/auth/change-password")
        .then()
            .statusCode(400)
            .body("code", equalTo("COM-001"))
            .body("errors.field", org.hamcrest.Matchers.hasItem("newPassword"));
    }

    @Test
    void changePassword_withoutToken_returns401() {
        given()
            .contentType(ContentType.JSON)
            .body("""
                {"currentPassword":"Admin1234","newPassword":"NuevaPass123"}
                """)
        .when()
            .post("/auth/change-password")
        .then()
            .statusCode(401);
    }

    @Test
    void login_withInactiveUser_returns401_userInactive() {
        given()
            .contentType(ContentType.JSON)
            .body("""
                {"username":"inactivo","password":"Inactivo1234"}
                """)
        .when()
            .post("/auth/login")
        .then()
            .statusCode(401)
            .body("code", equalTo("AUTH-002"));
    }

    @Test
    void login_withInvalidUsernamePattern_returns400_validation() {
        given()
            .contentType(ContentType.JSON)
            .body("""
                {"username":"user@admin","password":"Admin1234"}
                """)
        .when()
            .post("/auth/login")
        .then()
            .statusCode(400)
            .body("code", equalTo("COM-001"))
            .body("errors.field", org.hamcrest.Matchers.hasItem("username"));
    }

    @Test
    void refresh_withEmptyToken_returns400_validation() {
        given()
            .contentType(ContentType.JSON)
            .body("""
                {"refreshToken":""}
                """)
        .when()
            .post("/auth/refresh")
        .then()
            .statusCode(400)
            .body("code", equalTo("COM-001"))
            .body("errors.field", org.hamcrest.Matchers.hasItem("refreshToken"));
    }

    @Test
    void changePassword_withMissingFields_returns400_validation() {
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body("""
                {"currentPassword":""}
                """)
        .when()
            .post("/auth/change-password")
        .then()
            .statusCode(400)
            .body("code", equalTo("COM-001"))
            .body("errors.field", org.hamcrest.Matchers.hasItems("currentPassword", "newPassword"));
    }

    @Test
    void refresh_withExpiredToken_returns401_refreshTokenExpired() {
        // Fabricamos un JWT con exp en el pasado, firmado con la clave del backend.
        Instant past = Instant.now().minusSeconds(60);
        String expiredToken = Jwt.subject("1")
            .upn("admin")
            .claim("typ", "refresh")
            .issuedAt(past.minusSeconds(60))
            .expiresAt(past)
            .sign();

        given()
            .contentType(ContentType.JSON)
            .body("{\"refreshToken\":\"" + expiredToken + "\"}")
        .when()
            .post("/auth/refresh")
        .then()
            .statusCode(401)
            .body("code", equalTo("AUTH-009"));
    }

    @Test
    void me_withExpiredAccessToken_returns401_tokenExpired() {
        // Access token expirado: el filtro de seguridad de Quarkus lo rechaza
        // y nuestro JwtAuthExceptionMapper lo clasifica como AUTH-007.
        Instant past = Instant.now().minusSeconds(60);
        String expiredAccessToken = Jwt.subject("1")
            .upn("admin")
            .groups(java.util.Set.of("admin"))
            .claim("typ", "access")
            .issuedAt(past.minusSeconds(3600))
            .expiresAt(past)
            .sign();

        given()
            .header("Authorization", "Bearer " + expiredAccessToken)
        .when()
            .get("/auth/me")
        .then()
            .statusCode(401)
            .body("code", equalTo("AUTH-007"));
    }

    @Test
    void me_withTokenOfNonExistentUser_returns404_userNotFound() {
        // JWT firmado correctamente pero con un userId que no existe en BD.
        Instant now = Instant.now();
        String tokenForGhostUser = Jwt.subject("999999")
            .upn("ghost")
            .groups(java.util.Set.of("sales"))
            .claim("typ", "access")
            .issuedAt(now)
            .expiresAt(now.plusSeconds(3600))
            .sign();

        given()
            .header("Authorization", "Bearer " + tokenForGhostUser)
        .when()
            .get("/auth/me")
        .then()
            .statusCode(404)
            .body("code", equalTo("AUTH-005"));
    }

    // ----- helpers ----------------------------------------------------------

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
}
