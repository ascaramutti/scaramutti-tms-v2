package com.scaramutti.tms.support;

import io.restassured.http.ContentType;
import io.smallrye.jwt.build.Jwt;

import java.time.Instant;
import java.util.Set;

import static io.restassured.RestAssured.given;

/**
 * Helpers de autenticación compartidos por los tests de integración. Antes cada clase de
 * test redeclaraba estos cuatro métodos byte a byte; acá viven una sola vez.
 *
 * Dos formas de obtener un token:
 *  - {@link #login} / {@link #adminToken}: token REAL vía {@code POST /auth/login} (prueba
 *    el flujo de credenciales completo);
 *  - {@link #fabricateAccessToken} / {@link #fabricateTokenForUser}: token FIRMADO a mano,
 *    para ejercitar autorización (roles) sin depender de un usuario real, o anclado a un
 *    {@code userId} puntual cuando la regla del endpoint resuelve el usuario del subject.
 */
public final class TestAuth {

    public static final String ADMIN_USERNAME = "admin";
    public static final String ADMIN_PASSWORD = "Admin1234";

    private TestAuth() {
    }

    /** Login real vía {@code POST /auth/login}; devuelve el access token del cuerpo. */
    public static String login(String username, String password) {
        return given().contentType(ContentType.JSON)
            .body("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}")
        .when().post("/auth/login")
        .then().statusCode(200).extract().jsonPath().getString("token");
    }

    /** Token real de admin ({@code admin}/{@code Admin1234}). */
    public static String adminToken() {
        return login(ADMIN_USERNAME, ADMIN_PASSWORD);
    }

    /** Access token fabricado con subject fijo ({@code 999}): prueba autorización por rol. */
    public static String fabricateAccessToken(String username, String role) {
        Instant now = Instant.now();
        return Jwt.subject("999").upn(username).groups(Set.of(role)).claim("typ", "access")
            .issuedAt(now).expiresAt(now.plusSeconds(3600)).sign();
    }

    /** Access token fabricado anclado a un {@code userId} real (subject = userId). */
    public static String fabricateTokenForUser(int userId, String username, String role) {
        Instant now = Instant.now();
        return Jwt.subject(String.valueOf(userId)).upn(username).groups(Set.of(role)).claim("typ", "access")
            .issuedAt(now).expiresAt(now.plusSeconds(3600)).sign();
    }
}
