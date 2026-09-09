package com.scaramutti.tms.support;

import io.restassured.RestAssured;
import io.restassured.http.ContentType;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Las mismas URL escritas de forma torcida, para comprobar que un endpoint sigue exigiendo
 * sesión cuando el camino no viene en su forma canónica.
 *
 * <p>Existe porque la política por ruta de {@code application.properties} se evalúa sobre la URL
 * tal como llega: hay avisos publicados de rutas que la esquivan escribiendo el mismo camino con
 * punto y coma o con barras codificadas. Estas variantes son las plausibles para esa familia.
 *
 * <p>Ninguna de ellas esquivó la política cuando se midió: todas dieron 401 aun antes de agregar
 * la guarda del código. Lo que congelan estos casos es el resultado de las DOS capas juntas, que
 * es la configuración real: la URL torcida no entra sin token. No sirven para avisar que la
 * política por ruta se volvió esquivable, porque con la guarda del código puesta ella sola
 * contesta 401; a la guarda sola la mide {@code CodeLayerAuthGuardTest}, que apaga la política por
 * ruta y corre las variantes que llegan al recurso.
 *
 * <p>Fuera de la lista quedan las que meten la barra codificada en el PREFIJO
 * ({@code /api/v1%2Fcurrencies}): dan 404 y no 401 incluso con la política puesta. Un 404 tampoco
 * entrega nada, pero congelarlo sería atar una conducta del router que no es una propiedad de
 * seguridad. Las que llevan el carácter codificado al final ({@code %3Bx}, {@code %2F}) sí están,
 * porque en la configuración real dan 401; lo que no hacen es llegar al recurso.
 *
 * <p>{@code urlEncodingEnabled(false)} y {@code basePath("")} son lo que hace que la URL llegue
 * al servidor tal como se escribe acá: sin eso RestAssured la vuelve a codificar y le antepone el
 * root-path, y la prueba mediría otra cosa.
 */
public final class RoutePolicyTrickyUrls {

    private static final String ROOT_PATH = "/api/v1";

    private RoutePolicyTrickyUrls() {
    }

    /**
     * Las variantes torcidas que el router igual resuelve al recurso. Son las unicas que sirven
     * para medir la guarda del codigo sola: si la peticion no llega al metodo, no hay anotacion
     * que pueda contestar.
     */
    public static List<String> thatReachTheResource(String resourcePath) {
        String url = ROOT_PATH + resourcePath;
        return List.of(
            url + ";x",
            url + ";x=1",
            url + "/;x",
            ROOT_PATH + "/." + resourcePath,
            ROOT_PATH + "/" + resourcePath,
            url + "/."
        );
    }

    /**
     * Las que el router NO resuelve al recurso: medido, con la politica por ruta apagada dan 404
     * y no 401, o sea que su 401 en la configuracion real lo firma la politica y nadie mas. Se
     * prueban igual, porque un 404 tampoco entrega datos y lo que importa es que no entren.
     */
    public static List<String> thatDoNotReachTheResource(String resourcePath) {
        String url = ROOT_PATH + resourcePath;
        return List.of(url + "%3Bx", url + "%2F");
    }

    /** Las variantes torcidas de {@code resourcePath}, que empieza con barra ("/currencies"). */
    public static List<String> of(String resourcePath) {
        return Stream.concat(
            thatReachTheResource(resourcePath).stream(),
            thatDoNotReachTheResource(resourcePath).stream()
        ).toList();
    }

    /** Sin token, todas tienen que dar 401. Falla nombrando la variante que no. */
    public static void assertAllReturn401WithoutToken(String resourcePath) {
        assertReturn401WithoutToken(of(resourcePath));
    }

    /**
     * Solo las que llegan al recurso. Es lo que se puede exigir cuando la politica por ruta esta
     * apagada: las otras dan 404 ahi, que es correcto y no dice nada de la guarda del codigo.
     */
    public static void assertReachingVariantsReturn401WithoutToken(String resourcePath) {
        assertReturn401WithoutToken(thatReachTheResource(resourcePath));
    }

    /**
     * Junta TODAS las que no dan 401 antes de fallar. Cortar en la primera esconde a las que
     * vienen despues y hace concluir de menos sobre la lista entera.
     */
    private static void assertReturn401WithoutToken(List<String> urls) {
        List<String> desviadas = new ArrayList<>();
        for (String url : urls) {
            int status = RestAssured.given()
                .urlEncodingEnabled(false)
                .basePath("")
            .when()
                .get(url)
            .then()
                .extract().statusCode();

            if (status != 401) {
                desviadas.add(url + " -> " + status);
            }
        }
        if (!desviadas.isEmpty()) {
            throw new AssertionError("URL torcidas que no dieron 401: " + desviadas);
        }
    }

    /** Igual que el anterior pero con POST, para los endpoints que no son de lectura. */
    public static void assertAllReturn401WithoutTokenOnPost(String resourcePath, String jsonBody) {
        List<String> desviadas = new ArrayList<>();
        for (String url : of(resourcePath)) {
            int status = RestAssured.given()
                .urlEncodingEnabled(false)
                .basePath("")
                .contentType(ContentType.JSON)
                .body(jsonBody)
            .when()
                .post(url)
            .then()
                .extract().statusCode();

            if (status != 401) {
                desviadas.add(url + " -> " + status);
            }
        }
        if (!desviadas.isEmpty()) {
            throw new AssertionError("URL torcidas que no dieron 401 con POST: " + desviadas);
        }
    }
}
