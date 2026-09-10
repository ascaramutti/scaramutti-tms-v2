package com.scaramutti.tms.shared;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * La politica por ruta del servidor HTTP sola, sin que ninguna anotacion pueda contestar por ella.
 *
 * <p>Aislarla es el problema: todos los endpoints llevan ademas una guarda en el codigo, y las dos
 * capas devuelven el mismo 401, asi que sobre un endpoint real no se sabe cual contesto. La salida
 * es pedir un camino que NO existe bajo el root-path. Ahi no hay recurso, y por lo tanto no hay
 * {@code @Authenticated} ni {@code @RolesAllowed} que puedan intervenir: el 401 solo puede salir de
 * {@code quarkus.http.auth.permission.protected-paths}.
 *
 * <p>Para que sirve: el aviso de autorizacion por ruta de junio de 2026 describe caminos escritos
 * con punto y coma o con barras codificadas que dejan de coincidir con la politica. Si alguien
 * vuelve a una version sin ese arreglo, la peticion deja de ser denegada, llega al router y la
 * respuesta pasa de 401 a 404. Este test lo grita en vez de dejarlo pasar en silencio.
 *
 * <p>Un backslash sin codificar no se puede probar: el cliente HTTP no lo acepta en el camino y
 * revienta antes de salir ({@code URISyntaxException}). Por eso solo esta la forma codificada.
 */
@QuarkusTest
class RoutePolicyGuardTest {

    private static final String CAMINO_SIN_RECURSO = "/api/v1/no-existe-este-recurso";

    private int statusSinToken(String url) {
        return RestAssured.given()
            .urlEncodingEnabled(false)
            .basePath("")
        .when()
            .get(url)
        .then()
            .extract().statusCode();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "",
        ";x",
        "/;x",
        "%3Bx",
        "%2F",
        "%5C",
        "/.",
        "/;x/sub",
        "/%2Fsub",
        "/%5Csub",
    })
    void unknownPathUnderTheApiRoot_withoutToken_isDeniedByTheRoutePolicy(String sufijo) {
        int status = statusSinToken(CAMINO_SIN_RECURSO + sufijo);

        assertEquals(401, status,
            "La politica por ruta dejo de cubrir " + CAMINO_SIN_RECURSO + sufijo
                + " (un 404 significa que la peticion llego al router en vez de ser denegada)");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "/api/v1/./no-existe-este-recurso",
        "/api/v1//no-existe-este-recurso",
    })
    void unknownPathWithATwistedPrefix_withoutToken_isDeniedByTheRoutePolicy(String url) {
        assertEquals(401, statusSinToken(url),
            "La politica por ruta dejo de cubrir " + url);
    }

    /**
     * Control: la politica no deniega todo por igual. El login es publico y se alcanza sin token,
     * asi que responde por el metodo equivocado (405) y no por falta de credenciales. Sin esto, un
     * "deniega todo" accidental dejaria los casos de arriba en verde sin significar nada.
     */
    @Test
    void theLoginPath_staysPublic() {
        assertEquals(405, statusSinToken("/api/v1/auth/login"),
            "El login dejo de ser publico: sin token tiene que llegar al recurso y rebotar por el"
                + " metodo (405), no por falta de credenciales");
    }
}
