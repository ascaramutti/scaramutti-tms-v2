package com.scaramutti.tms.shared;

import com.scaramutti.tms.support.RoutePolicyTrickyUrls;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * La guarda de sesion del CODIGO, medida sin la politica por ruta debajo.
 *
 * <p>Existe porque las dos capas devuelven el mismo 401 y, mientras las dos esten puestas, un
 * test comun no distingue cual contesto: se puede borrar {@code @Authenticated} de un recurso y
 * la suite sigue verde. Este perfil deja la politica por ruta en {@code permit} para /api/v1/*,
 * asi que el unico 401 posible es el de la anotacion. Borrar la anotacion de cualquiera de los
 * recursos de aca hace fallar este test, que es justo lo que se quiere proteger.
 *
 * <p>La segunda capa importa porque la politica por ruta se evalua sobre la URL tal como llega, y
 * hay avisos publicados de rutas que la esquivan escribiendo el mismo camino con punto y coma o
 * con barras codificadas. La comprobacion del codigo no depende de como se escriba la ruta.
 *
 * <p>Vive en su propia clase porque necesita levantar la aplicacion con otra configuracion.
 */
@QuarkusTest
@TestProfile(CodeLayerAuthGuardTest.RoutePolicyPermitProfile.class)
class CodeLayerAuthGuardTest {

    /** Apaga la primera capa: /api/v1/* deja de exigir autenticacion en el servidor HTTP. */
    public static class RoutePolicyPermitProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("quarkus.http.auth.permission.protected-paths.policy", "permit");
        }
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

    @ParameterizedTest
    @ValueSource(strings = {
        "/currencies",
        "/payment-terms",
        "/quotation-conditions",
        "/quotation-service-types",
        "/cargo-types",
        "/clients",
        "/auth/me",
    })
    void withoutToken_andWithoutTheRoutePolicy_returns401(String path) {
        given()
        .when()
            .get(path)
        .then()
            .statusCode(401);
    }

    @Test
    void changePassword_withoutToken_andWithoutTheRoutePolicy_returns401() {
        given()
            .contentType(ContentType.JSON)
            .body("{\"currentPassword\":\"Admin1234\",\"newPassword\":\"Otra12345\"}")
        .when()
            .post("/auth/change-password")
        .then()
            .statusCode(401);
    }

    /**
     * El perfil apaga la politica por ruta, no el login: si esto fallara, los 401 de arriba
     * podrian venir de una aplicacion rota y no de la anotacion.
     */
    @Test
    void login_staysPublic_underThisProfile() {
        given()
            .contentType(ContentType.JSON)
            .body("{\"username\":\"admin\",\"password\":\"Admin1234\"}")
        .when()
            .post("/auth/login")
        .then()
            .statusCode(200)
            .body("token", notNullValue());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "/currencies",
        "/payment-terms",
        "/quotation-conditions",
        "/quotation-service-types",
        "/cargo-types",
        "/clients",
        "/auth/me",
    })
    void withAValidToken_theGuardLetsThrough(String path) {
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get(path)
        .then()
            .statusCode(200);
    }

    /** Un token con firma rota tampoco pasa la segunda capa. */
    @Test
    void withAMalformedToken_returns401() {
        given()
            .header("Authorization", "Bearer eyJ.malformed.token")
        .when()
            .get("/currencies")
        .then()
            .statusCode(401)
            .body("code", equalTo("AUTH-008"));
    }

    /**
     * Los dos endpoints de {@code /auth} tienen una tercera capa: {@code CurrentUser.requireId()}
     * también contesta 401 sin token, pero ya DENTRO del metodo. Las dos respuestas son 401, asi
     * que mirar solo el codigo de estado no distingue cual contesto y borrar la anotacion no
     * rompia nada. Lo que las separa es de donde viene: la guarda rechaza antes de entrar al
     * metodo y devuelve el desafio del servidor, sin cuerpo de la aplicacion; {@code requireId()}
     * devuelve el problema AUTH-006. Por eso acá se comprueba que el 401 NO sea el de adentro.
     */
    @Test
    void me_withoutToken_isRejectedBeforeEnteringTheMethod() {
        String body = given()
        .when()
            .get("/auth/me")
        .then()
            .statusCode(401)
            .extract().body().asString();

        assertFalse(body.contains("AUTH-006"),
            "El 401 lo contestó requireId() dentro del método, no la guarda de la clase: " + body);
    }

    /** Misma comprobación que en {@code me}, sobre el endpoint que no es de lectura. */
    @Test
    void changePassword_withoutToken_isRejectedBeforeEnteringTheMethod() {
        String body = given()
            .contentType(ContentType.JSON)
            .body("{\"currentPassword\":\"Admin1234\",\"newPassword\":\"Otra12345\"}")
        .when()
            .post("/auth/change-password")
        .then()
            .statusCode(401)
            .extract().body().asString();

        assertFalse(body.contains("AUTH-006"),
            "El 401 lo contestó requireId() dentro del método, no la guarda del método: " + body);
    }

    /**
     * Las variantes torcidas que llegan al recurso, con la politica por ruta apagada: acá el 401
     * solo puede venir de la guarda del codigo. Es lo que los casos de cada recurso NO pueden
     * medir, porque alla las dos capas estan puestas y cualquiera de las dos contesta. Las que no
     * llegan al recurso quedan afuera a proposito: sin la politica dan 404, medido.
     */
    @ParameterizedTest
    @ValueSource(strings = {
        "/currencies",
        "/payment-terms",
        "/quotation-conditions",
        "/quotation-service-types",
        "/cargo-types",
        "/clients",
        "/auth/me",
    })
    void withTrickyUrls_andWithoutTheRoutePolicy_returns401(String path) {
        RoutePolicyTrickyUrls.assertReachingVariantsReturn401WithoutToken(path);
    }
}
