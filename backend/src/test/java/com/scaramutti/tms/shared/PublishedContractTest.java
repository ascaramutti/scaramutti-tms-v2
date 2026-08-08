package com.scaramutti.tms.shared;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * El contrato que la aplicación PUBLICA, leído como lo lee un cliente.
 *
 * <p>Existe por un accidente concreto: un bloque de rutas mal indentado dejó a un endpoint colgado
 * de la ruta equivocada, con lo cual el listado y el alta pasaron a publicarse bajo
 * {@code /services/{id}} y el detalle desapareció del documento. El YAML seguía siendo válido y
 * seguía teniendo las dos claves, así que verificar "las rutas están" no alcanzaba: hay que mirar
 * QUÉ OPERACIÓN cuelga de CADA ruta.
 *
 * <p>Importa más de lo que parece porque el escaneo de anotaciones está apagado
 * ({@code mp.openapi.scan.disable=true}): este archivo no se genera desde el código, se sirve tal
 * cual, y de él sale el cliente TypeScript del frontend. Nada más lo compara contra la realidad.
 */
@QuarkusTest
class PublishedContractTest {

    private static JsonPath publishedContract() {
        return given()
            // el documento se sirve en la RAIZ, fuera del prefijo de la API y de su filtro de auth
            .basePath("/")
        .when()
            .get("/openapi?format=json")
        .then()
            .statusCode(200)
            .extract().jsonPath();
    }

    /**
     * Toda ruta declarada en el archivo tiene que aparecer en el documento SERVIDO.
     *
     * <p>Este es el invariante que caza el accidente real: cuando un bloque queda mal ubicado, la
     * ruta afectada se queda sin operaciones y el generador directamente la DESCARTA, así que no
     * aparece vacía en el documento, desaparece. Comparar el archivo contra lo servido lo detecta
     * en cualquiera de las rutas publicadas, no solo en las que a alguien se le ocurrió listar.
     */
    @Test
    void publishedContract_servesEveryPathDeclaredInTheFile() throws Exception {
        Set<String> declared = declaredPaths();
        Set<String> served = publishedContract().<String, Object>getMap("paths").keySet();

        assertFalse(declared.isEmpty(), "no se pudo leer ninguna ruta del archivo del contrato");
        Set<String> missing = new TreeSet<>(declared);
        missing.removeAll(served);

        assertTrue(missing.isEmpty(),
            "el archivo declara estas rutas y el documento servido no las publica "
                + "(bloque mal ubicado): " + missing);
    }

    /**
     * Las rutas tal como están escritas en el archivo, sin parsear YAML.
     *
     * <p>Se aceptan con CUALQUIER sangría, no solo la correcta: un bloque indentado de más
     * desaparece del documento servido, y si acá se lo descartara por mal sangrado se caería
     * también del conjunto declarado, con lo cual la comparación no vería nada. Justamente el
     * modo de falla que este test existe para cazar.
     */
    private static Set<String> declaredPaths() throws Exception {
        Pattern pathKey = Pattern.compile("^\\s+(/\\S*):\\s*$");
        Set<String> paths = new TreeSet<>();
        try (var reader = new BufferedReader(new InputStreamReader(
                Objects.requireNonNull(
                    PublishedContractTest.class.getResourceAsStream("/META-INF/openapi.yaml")),
                StandardCharsets.UTF_8))) {
            String line;
            boolean insidePaths = false;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("paths:")) {
                    insidePaths = true;
                    continue;
                }
                // otra CLAVE de primer nivel cierra la seccion; los comentarios de banner viven
                // en la columna cero y no cierran nada
                if (insidePaths && !line.isBlank() && !line.startsWith("#")
                        && !Character.isWhitespace(line.charAt(0))) {
                    break;
                }
                Matcher matcher = pathKey.matcher(line);
                if (insidePaths && matcher.matches()) {
                    paths.add(matcher.group(1));
                }
            }
        }
        return paths;
    }

    /**
     * Los dos campos que el servidor OMITE según el rol no pueden declararse obligatorios: el
     * cliente TypeScript los tiparía como siempre presentes y explotaría en runtime justo para
     * el rol al que se le omiten.
     */
    @Test
    void publishedContract_doesNotRequireTheFieldsItOmitsByRole() {
        JsonPath contract = publishedContract();

        for (String schema : List.of("ServiceDetailResponse", "ServiceSummaryResponse")) {
            List<String> required = contract.getList("components.schemas." + schema + ".required");
            assertFalse(required.contains("price"),
                schema + " declara price como obligatorio y al despacho se le omite");
            assertFalse(required.contains("currencyCode"),
                schema + " declara currencyCode como obligatorio y al despacho se le omite");
        }
    }

    @Test
    void publishedContract_keepsEachOperationUnderItsOwnPath() {
        given()
            // el documento se sirve en la RAIZ, fuera del prefijo de la API y de su filtro de auth
            .basePath("/")
        .when()
            .get("/openapi?format=json")
        .then()
            .statusCode(200)
            .body("paths.'/services'.get.operationId", equalTo("listServices"))
            .body("paths.'/services'.post.operationId", equalTo("createService"))
            .body("paths.'/services/{id}'.get.operationId", equalTo("getService"))
            .body("paths.'/services/{id}'.put.operationId", equalTo("updateService"))
            // A QUE respuesta apunta cada status, no solo que exista: apuntar el 409 al cuerpo
            // del 404, o el 412 al de otro recurso, deja al cliente TypeScript tipando mal justo
            // los casos que este endpoint estrena
            .body("paths.'/services/{id}'.put.responses.'409'.$ref",
                equalTo("#/components/responses/Conflict"))
            .body("paths.'/services/{id}'.put.responses.'412'.$ref",
                equalTo("#/components/responses/PreconditionFailed"))
            .body("paths.'/services/{id}'.put.responses.'404'.$ref",
                equalTo("#/components/responses/NotFound"))
            .body("paths.'/services/{id}'.put.responses.'403'", notNullValue())
            .body("paths.'/services/{id}'.put.responses.'200'.headers.ETag", notNullValue())
            // los tres headers que la respuesta manda de verdad
            .body("paths.'/services/{id}'.put.responses.'200'.headers.'Cache-Control'.schema.type",
                equalTo("string"))
            .body("paths.'/services/{id}'.put.responses.'200'.headers.Vary.description",
                containsString("Authorization"))
            .body("paths.'/services/{id}'.get.responses.'200'.headers.Vary.description",
                containsString("Authorization"))
            .body("paths.'/services'.get.responses.'200'.headers.Vary.description",
                containsString("Authorization"))
            .body("paths.'/services'.post.responses.'201'.headers.Vary.description",
                containsString("Authorization"))
            // el patrón de la justificación: es lo que traduce al cliente el mínimo REAL (que se
            // mide sobre el texto recortado), y el minLength solo no lo expresa
            .body("components.schemas.ServiceUpdateRequest.properties.justification.pattern",
                equalTo("^\\s*\\S[\\s\\S]{8,}\\S\\s*$"))
            .body("components.schemas.ServiceUpdateRequest.properties.justification.minLength", equalTo(10))
            // una ruta sin operaciones es el sintoma exacto del bloque mal ubicado
            .body("paths.'/services'.put", nullValue())
            .body("paths.'/services/{id}'.post", nullValue());
    }

    /** El detalle publica el ETag: es lo que la edición y las transiciones van a exigir. */
    @Test
    void publishedContract_declaresTheEtagOfTheDetail() {
        given()
            // el documento se sirve en la RAIZ, fuera del prefijo de la API y de su filtro de auth
            .basePath("/")
        .when()
            .get("/openapi?format=json")
        .then()
            .statusCode(200)
            .body("paths.'/services/{id}'.get.responses.'200'.headers.ETag", notNullValue());
    }
}
