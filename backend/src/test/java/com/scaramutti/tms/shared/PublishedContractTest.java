package com.scaramutti.tms.shared;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
            // El strip es el que MAS necesita esto: su ruta es un literal que tambien matchea la
            // plantilla del detalle, asi que un bloque colgado de la ruta equivocada deja las dos
            // claves presentes y la suite verde.
            .body("paths.'/services/stats'.get.operationId", equalTo("getServiceStats"))
            .body("paths.'/services/stats'.get.responses.'200'.headers.'Cache-Control'.schema.type",
                equalTo("string"))
            .body("paths.'/services/stats'.get.responses.'200'.headers.Vary.description",
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

    /**
     * Los roles, el 403 y las cabeceras de las lecturas de clientes. El cliente generado lleva el
     * 403, pero no los roles ni las cabeceras: sin este test, nada notaria que el contrato los
     * perdio.
     */
    @Test
    void publishedContract_declaresWhoReadsClientsAndThatTheReadIsNotStored() {
        JsonPath contract = publishedContract();
        for (String path : List.of("/clients", "/clients/{id}")) {
            String read = "paths.'" + path + "'.get.";
            assertEquals(List.of("admin", "sales", "general_manager", "operations_manager"),
                contract.getList(read + "'x-required-roles'"), path);
            assertEquals("#/components/responses/Forbidden",
                contract.getString(read + "responses.'403'.$ref"), path);
            assertThat(path,
                contract.getString(read + "responses.'200'.headers.'Cache-Control'.description"),
                containsString("no-store"));
            assertThat(path, contract.getString(read + "responses.'200'.headers.Vary.description"),
                containsString("Authorization"));
        }
    }

    /**
     * Las cabeceras de caché de la sesión: el ingreso y la renovación traen tokens ({@code no-store}
     * y {@code Pragma}, sin {@code Vary}); {@code /auth/me}, los datos de la cuenta ({@code no-store}
     * y {@code Vary}). El cliente generado no lleva cabeceras: sin este test, nada notaría que el
     * contrato las perdió.
     */
    @Test
    void publishedContract_declaresTheSessionResponsesNotStored() {
        JsonPath contract = publishedContract();
        for (String path : List.of("/auth/login", "/auth/refresh")) {
            String headers = "paths.'" + path + "'.post.responses.'200'.headers.";
            assertThat(path, contract.getString(headers + "'Cache-Control'.description"), containsString("no-store"));
            assertThat(path, contract.getString(headers + "Pragma.description"), containsString("no-cache"));
            assertThat(path, contract.get(headers + "Vary"), nullValue());
        }
        String me = "paths.'/auth/me'.get.responses.'200'.headers.";
        assertThat(contract.getString(me + "'Cache-Control'.description"), containsString("no-store"));
        assertThat(contract.getString(me + "Vary.description"), containsString("Authorization"));
        assertThat(contract.get(me + "Pragma"), nullValue());
    }

    /**
     * Las cabeceras de caché de trabajadores: el listado depende del rol y la ficha trae el
     * documento ({@code no-store} y {@code Vary}, sin {@code Pragma}), y las cuatro escrituras
     * devuelven esa ficha. El cliente generado no lleva cabeceras: sin este test, nada notaría que
     * el contrato las perdió.
     */
    @Test
    void publishedContract_declaresTheWorkerResponsesNotStored() {
        JsonPath contract = publishedContract();
        for (String success : List.of(
                "'/workers'.get.responses.'200'", "'/workers'.post.responses.'201'",
                "'/workers/{id}'.get.responses.'200'", "'/workers/{id}'.put.responses.'200'",
                "'/workers/{id}/deactivate'.post.responses.'200'",
                "'/workers/{id}/reactivate'.post.responses.'200'")) {
            String headers = "paths." + success + ".headers.";
            assertThat(success, contract.getString(headers + "'Cache-Control'.description"),
                containsString("no-store"));
            assertThat(success, contract.getString(headers + "Vary.description"),
                containsString("Authorization"));
            assertThat(success, contract.get(headers + "Pragma"), nullValue());
        }
    }

    /**
     * El id del trabajador va solo en el usuario de la sesión. El cliente generado sale de este
     * documento: si {@code /auth/me} o el ingreso apuntaran a UserResponse, la pantalla no tendría
     * el campo; si UserResponse lo declarara, cada autor de cotizaciones y almacén lo prometería.
     */
    @Test
    void publishedContract_putsTheWorkerIdOnlyInTheSessionUser() {
        JsonPath contract = publishedContract();
        for (String path : List.of("/auth/login", "/auth/refresh")) {
            assertEquals("#/components/schemas/LoginResponse", contract.getString(
                "paths.'" + path + "'.post.responses.'200'.content.'application/json'.schema.$ref"), path);
        }
        assertEquals("#/components/schemas/SessionUser",
            contract.getString("components.schemas.LoginResponse.properties.user.$ref"));
        assertEquals("#/components/schemas/SessionUser", contract.getString(
            "paths.'/auth/me'.get.responses.'200'.content.'application/json'.schema.$ref"));

        String sessionUser = "components.schemas.SessionUser.allOf";
        assertEquals("#/components/schemas/UserResponse", contract.getString(sessionUser + "[0].$ref"));
        assertEquals(List.of("workerId"), contract.getList(sessionUser + "[1].required"));
        assertEquals("integer", contract.getString(sessionUser + "[1].properties.workerId.type"));
        assertEquals("int32", contract.getString(sessionUser + "[1].properties.workerId.format"));
        assertEquals(2, refsTo(contract.get("$"), "#/components/schemas/SessionUser"),
            "solo el usuario del ingreso y /auth/me apuntan a SessionUser; un autor no lo trae");

        for (String schema : List.of("UserResponse", "UserRef")) {
            assertFalse(contract.getMap("components.schemas." + schema).toString().contains("workerId"),
                schema + " no puede declarar workerId: es también el autor embebido");
        }
    }

    /**
     * Las tres contraseñas declaran el límite de BCrypt, en bytes: el cliente del frontend sale de
     * este documento, y con el máximo de antes (100) quien lo siguiera mandaba claves que dan 400.
     */
    @Test
    void publishedContract_limitsEveryPasswordTo72Bytes() {
        JsonPath contract = publishedContract();
        for (String field : List.of("LoginRequest.properties.password",
                                    "ChangePasswordRequest.properties.currentPassword",
                                    "ChangePasswordRequest.properties.newPassword")) {
            String schema = "components.schemas." + field;
            assertEquals(8, contract.getInt(schema + ".minLength"), field);
            assertEquals(72, contract.getInt(schema + ".maxLength"), field);
            assertTrue(contract.getString(schema + ".description").contains("72 bytes en UTF-8"), field);
        }
    }

    /**
     * La ficha manda siempre las dos partes y el nombre, el materno nulo si no hay: el contrato los
     * exige, y así el cliente generado no los tipa opcionales ni le quita el nulo al materno.
     */
    @Test
    void publishedContract_requiresTheSurnamesAndTheFullNameOfTheWorkerDetail() {
        JsonPath contract = publishedContract();
        String schema = "components.schemas.WorkerDetailResponse.";
        List<String> required = contract.getList(schema + "required");
        for (String field : List.of("paternalLastName", "maternalLastName", "fullName")) {
            assertTrue(required.contains(field), field + " no está en required");
        }
        assertEquals("string", contract.get(schema + "properties.paternalLastName.type"));
        assertEquals(List.of("string", "null"), contract.get(schema + "properties.maternalLastName.type"));
        assertEquals("string", contract.get(schema + "properties.fullName.type"));
    }

    /**
     * La edición hereda el alta: un allOf de WorkerRequest y un objeto con solo {@code reason},
     * texto o nulo hasta 500, sin otra regla. Sin copia no hay dos definiciones que se separen, y un
     * campo nuevo del alta entra en la edición del contrato.
     */
    @Test
    void publishedContract_editsAWorkerWithTheCreationFieldsPlusTheReason() {
        JsonPath contract = publishedContract();
        String edit = "components.schemas.WorkerUpdateRequest";
        assertEquals(Set.of("description", "allOf"), contract.getMap(edit).keySet(),
            "la edición no declara propiedades ni required propios: los hereda del alta");
        assertEquals(2, contract.getList(edit + ".allOf").size());
        assertEquals(Map.of("$ref", "#/components/schemas/WorkerRequest"), contract.getMap(edit + ".allOf[0]"));
        assertEquals(Set.of("type", "properties"), contract.getMap(edit + ".allOf[1]").keySet());
        assertEquals("object", contract.getString(edit + ".allOf[1].type"));
        assertEquals(Set.of("reason"), contract.getMap(edit + ".allOf[1].properties").keySet());
        Map<String, Object> reason = contract.getMap(edit + ".allOf[1].properties.reason");
        assertEquals(Set.of("type", "maxLength", "example"), reason.keySet(),
            "reason es texto o nulo hasta 500, sin otra regla: su mínimo es condicional y lo mide el servidor");
        assertEquals(List.of("string", "null"), reason.get("type"));
        assertEquals(500, reason.get("maxLength"));
    }

    /**
     * El alta publica los dos apellidos con la regla del nombre (el materno, además, vacío), y
     * ninguno obligatorio: con el apellido de antes alcanza, y cuál tiene que venir lo mide el
     * servidor. El cliente generado sale de estas definiciones.
     */
    @Test
    void publishedContract_takesBothSurnamesInTheCreation() {
        JsonPath contract = publishedContract();
        String properties = "components.schemas.WorkerRequest.properties.";
        assertEquals(Set.of("type", "minLength", "maxLength", "pattern", "example"),
            contract.getMap(properties + "paternalLastName").keySet());
        assertEquals(Set.of("type", "maxLength", "pattern", "example"),
            contract.getMap(properties + "maternalLastName").keySet(), "el materno entra vacío: sin minLength");
        String namePattern = contract.getString(properties + "firstName.pattern");
        assertEquals(namePattern, contract.getString(properties + "paternalLastName.pattern"));
        assertEquals("^ *$|" + namePattern, contract.getString(properties + "maternalLastName.pattern"));
        assertEquals("string", contract.get(properties + "paternalLastName.type"));
        assertEquals(List.of("string", "null"), contract.get(properties + "maternalLastName.type"));
        assertEquals(1, contract.getInt(properties + "paternalLastName.minLength"));
        assertEquals(100, contract.getInt(properties + "paternalLastName.maxLength"));
        assertEquals(100, contract.getInt(properties + "maternalLastName.maxLength"));
        List<String> required = contract.getList("components.schemas.WorkerRequest.required");
        for (String field : List.of("lastName", "paternalLastName", "maternalLastName")) {
            assertFalse(required.contains(field), field + " no puede ser obligatorio");
        }
    }

    /** Cuántas veces aparece una referencia en todo el documento. */
    private static long refsTo(Object node, String ref) {
        if (node instanceof Map<?, ?> map) {
            return map.values().stream().mapToLong(value -> refsTo(value, ref)).sum();
        }
        if (node instanceof List<?> list) {
            return list.stream().mapToLong(value -> refsTo(value, ref)).sum();
        }
        return ref.equals(node) ? 1 : 0;
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
