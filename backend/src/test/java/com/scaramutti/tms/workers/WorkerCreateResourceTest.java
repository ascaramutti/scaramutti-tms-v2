package com.scaramutti.tms.workers;

import com.scaramutti.tms.support.WarehouseTestData;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.LocalDate;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.scaramutti.tms.support.LockWaiters.UNTIL_IT_ARRIVES_MILLIS;
import static com.scaramutti.tms.support.LockWaiters.awaitWaiters;
import static com.scaramutti.tms.support.LockWaiters.backendPid;
import static com.scaramutti.tms.support.TestAuth.adminToken;
import static com.scaramutti.tms.support.TestAuth.fabricateAccessToken;
import static com.scaramutti.tms.support.TestAuth.fabricateTokenForUser;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Integration tests de POST /workers.
 *
 * <p>Limpia ANTES y DESPUES de cada caso: el alta deja filas de verdad, y una corrida cortada
 * a la mitad deja un documento que rompe la siguiente por unicidad culpando al fixture.
 *
 * <p>Los casos de organigrama entran con un ACTOR SEMBRADO (trabajador + usuario con su rol) y
 * un token anclado a ese usuario, no con el sujeto fijo del token fabricado: la regla de rango
 * resuelve el usuario del sujeto y lee su nivel de la base, asi que un sujeto que no existe
 * mediria otra cosa. Los casos que solo miden la lista de roles del recurso si usan el token
 * fabricado, porque ahi el usuario nunca se resuelve.
 */
@QuarkusTest
class WorkerCreateResourceTest {

    @Inject WarehouseTestData fixtures;
    @Inject DataSource dataSource;

    private Integer documentTypeId;

    @BeforeEach
    @AfterEach
    void cleanupFixtures() {
        fixtures.deleteTestWorkerDependents();
        QuarkusTransaction.requiringNew().run(() -> fixtures.deleteTestWorkers());
        fixtures.deleteTestRoles();
        fixtures.deleteTestDocumentTypes();
        documentTypeId = null;
    }

    /**
     * Tipo de documento de prueba, ancho y sin formato, para los casos que no miden el tipo.
     *
     * <p>El DNI real tiene largo maximo 8 y ningun documento de prueba entra ahi, pero sobre
     * todo: colgar la mayoria de los casos de una fila del padron los ata a una regla que puede
     * cambiar sin avisar, y entonces treinta casos se caen juntos por un motivo que no es el
     * suyo. El tipo real tiene su propio caso, que es donde esa regla SI se mide.
     */
    private int documentTypeId() {
        if (documentTypeId == null) {
            documentTypeId = fixtures.seedDocumentType("ZTDOC00", "ZTEST generico", 20, null, true);
        }
        return documentTypeId;
    }

    /**
     * El cuerpo minimo valido. Se arma en codigo y no en un archivo porque cada caso cambia UN
     * campo, y lo que se lee al fallar es justamente cual.
     */
    private String body(String documentNumber, String role) {
        return String.format("""
            {"firstName":"Juan","lastName":"Pérez Huamán","documentTypeId":%d,
             "documentNumber":"%s","role":"%s","hireDate":"2024-03-01"}""",
            documentTypeId(), documentNumber, role);
    }

    private String bodyWithDriver(String documentNumber, String role, String driverJson) {
        return String.format("""
            {"firstName":"Juan","lastName":"Pérez Huamán","documentTypeId":%d,
             "documentNumber":"%s","role":"%s","hireDate":"2024-03-01","driver":%s}""",
            documentTypeId(), documentNumber, role, driverJson);
    }

    private io.restassured.specification.RequestSpecification post(String token) {
        return given().header("Authorization", "Bearer " + token).contentType("application/json");
    }

    // ---------- camino feliz -----------------------------------------------------

    @Test
    void create_withTheFullBody_returns201WithTheFifteenKeys() {
        post(adminToken()).body("""
            {"firstName":"Juan","lastName":"Pérez Huamán","documentTypeId":%d,
             "documentNumber":"ZTESTC001","phone":"987654321","role":"operator",
             "hireDate":"2024-03-01"}""".formatted(documentTypeId()))
        .when().post("/workers")
        .then().statusCode(201)
            .contentType("application/json")
            // El conjunto ENTERO de claves: un campo de mas, que es como se filtra un dato sin
            // querer, tambien hace fallar el caso.
            .body("keySet()", containsInAnyOrder(
                "id", "firstName", "lastName", "documentType", "documentNumber", "phone",
                "role", "hireDate", "isActive", "createdAt", "createdBy", "updatedAt",
                "updatedBy", "driver", "hasUser"))
            .body("id", notNullValue())
            .body("firstName", equalTo("Juan"))
            .body("lastName", equalTo("Pérez Huamán"))
            .body("documentNumber", equalTo("ZTESTC001"))
            .body("phone", equalTo("987654321"))
            .body("documentType.code", equalTo("ZTDOC00"))
            .body("role.name", equalTo("operator"))
            .body("role.level", equalTo(1))
            .body("role.driverProfile", equalTo("NONE"))
            .body("hireDate", equalTo("2024-03-01"))
            .body("isActive", equalTo(true))
            .body("hasUser", equalTo(false))
            .body("driver", nullValue());
    }

    /**
     * El unico caso que usa el tipo REAL del padron, con un numero que cumple su largo maximo.
     * Es el que mide que las reglas del catalogo de produccion dejan pasar un alta legitima:
     * el resto de los casos usa un tipo de prueba para no depender de ellas.
     */
    @Test
    void create_withTheRealDniType_returns201() {
        post(adminToken()).body("""
            {"firstName":"Juan","lastName":"Pérez","documentTypeId":%d,"documentNumber":"ZTEST001",
             "role":"operator","hireDate":"2024-03-01"}"""
            .formatted(fixtures.dniDocumentTypeId()))
        .when().post("/workers")
        .then().statusCode(201)
            .body("documentType.code", equalTo("DNI"))
            .body("documentNumber", equalTo("ZTEST001"));
    }

    @Test
    void create_withOnlyTheRequiredFields_returns201WithoutPhone() {
        post(adminToken()).body(body("ZTESTC002", "operator"))
        .when().post("/workers")
        .then().statusCode(201).body("phone", nullValue());
    }

    /**
     * Gemelo del anterior a proposito: el formulario manda la clave en nulo en vez de omitirla,
     * y con un solo caso tratar "ausente" y "nulo" distinto pasaria sin que nada falle.
     */
    @Test
    void create_withPhoneExplicitlyNull_returns201() {
        post(adminToken()).body("""
            {"firstName":"Juan","lastName":"Pérez","documentTypeId":%d,"documentNumber":"ZTESTC003",
             "phone":null,"role":"operator","hireDate":"2024-03-01"}"""
            .formatted(documentTypeId()))
        .when().post("/workers")
        .then().statusCode(201).body("phone", nullValue());
    }

    /** El backend es permisivo a proposito: la fecha futura la rechaza solo el cliente. */
    @Test
    void create_withAFutureHireDate_returns201() {
        String future = LocalDate.now().plusYears(1).toString();
        post(adminToken()).body("""
            {"firstName":"Juan","lastName":"Pérez","documentTypeId":%d,"documentNumber":"ZTESTC004",
             "role":"operator","hireDate":"%s"}"""
            .formatted(documentTypeId(), future))
        .when().post("/workers")
        .then().statusCode(201).body("hireDate", equalTo(future));
    }

    /**
     * El recorte toca los bordes y NO la caja, y se mide en los dos sentidos (una minuscula que
     * sigue minuscula y una mayuscula que sigue mayuscula): reusar un helper que sube a
     * mayusculas, como el de clientes, muere aca.
     */
    @Test
    void create_trimsTheBordersWithoutChangingCase() {
        post(adminToken()).body("""
            {"firstName":"  juan Carlos  ","lastName":" PÉREZ huamán ","documentTypeId":%d,
             "documentNumber":" ZTESTC010 ","role":"driver","hireDate":"2024-03-01",
             "driver":{"licenseNumber":" ztestl010 ","licenseCategory":"  a-IIIc  "}}"""
            .formatted(documentTypeId()))
        .when().post("/workers")
        .then().statusCode(201)
            .body("firstName", equalTo("juan Carlos"))
            .body("lastName", equalTo("PÉREZ huamán"))
            .body("documentNumber", equalTo("ZTESTC010"))
            .body("driver.licenseNumber", equalTo("ztestl010"))
            .body("driver.licenseCategory", equalTo("a-IIIc"));
    }

    /** El 201 no se armo en memoria: la transaccion cerro y el detalle lee lo mismo. */
    @Test
    void create_theCreatedWorker_isReadableByTheDetailEndpoint() {
        var created = post(adminToken()).body(body("ZTESTC005", "operator"))
            .when().post("/workers").then().statusCode(201)
            .extract().body().jsonPath().getMap("$");

        var read = given().header("Authorization", "Bearer " + adminToken())
            .when().get("/workers/" + created.get("id"))
            .then().statusCode(200).extract().body().jsonPath().getMap("$");

        // El cuerpo ENTERO y no dos campos: el servicio promete que el 201 sale releido de la
        // fila, asi que cualquier diferencia entre las dos respuestas es esa promesa rota.
        assertEquals(created, read, "el 201 y el detalle tienen que ser el MISMO cuerpo");
    }

    // ---------- la ficha, segun la modalidad del cargo ----------------------------

    /**
     * LA MATRIZ COMPLETA de modalidad de ficha por cargo, las diez celdas.
     *
     * <p>Es la guarda de completitud: cada modalidad se mide con ficha y sin ficha, y cada
     * resultado se afirma contra la FILA de {@code public.drivers} y no solo contra la
     * respuesta. Las dos direcciones juntas son lo que distingue "mira la modalidad" de
     * "siempre exige ficha" y de "siempre la rechaza": una sola comparacion invertida mata
     * exactamente una celda y el nombre del caso dice cual.
     *
     * <p>Los cargos no son intercambiables dentro de su modalidad y por eso estan los cinco:
     * conductor y escolta comparten modalidad pero son filas distintas del catalogo, y de los
     * que no llevan ficha se miden uno que NO inicia sesion (operador) y uno que SI
     * (coordinador), porque llevar ficha y poder entrar al sistema son columnas distintas y
     * nada impide que alguien las confunda.
     *
     * <p>Las celdas que rechazan afirman ademas que no quedo NADA: un error que igual grabo la
     * fila se ve solo contando.
     */
    @ParameterizedTest(name = "{0} ({1}) {2} ficha -> {3}")
    @CsvSource({
        // cargo,      modalidad, manda ficha, status, codigo,  espera fila, documento,   licencia
        "driver,      REQUIRED, con,  201, ,        si, ZTESTM01, ZTESTLM01",
        "driver,      REQUIRED, sin,  400, WRK-008, no, ZTESTM02, ",
        "escort,      REQUIRED, con,  201, ,        si, ZTESTM03, ZTESTLM03",
        "escort,      REQUIRED, sin,  400, WRK-008, no, ZTESTM04, ",
        "assistant,   OPTIONAL, con,  201, ,        si, ZTESTM05, ZTESTLM05",
        "assistant,   OPTIONAL, sin,  201, ,        no, ZTESTM06, ",
        "operator,    NONE,     con,  400, WRK-008, no, ZTESTM07, ZTESTLM07",
        "operator,    NONE,     sin,  201, ,        no, ZTESTM08, ",
        "dispatcher,  NONE,     con,  400, WRK-008, no, ZTESTM09, ZTESTLM09",
        "dispatcher,  NONE,     sin,  201, ,        no, ZTESTM10, ",
    })
    void create_coversTheWholeDriverProfileMatrix(String role, String mode, String sendsProfile,
            int expectedStatus, String expectedCode, String expectsRow,
            String documentNumber, String licenseNumber) {
        String requestBody = "con".equals(sendsProfile)
            ? bodyWithDriver(documentNumber, role, "{\"licenseNumber\":\"" + licenseNumber + "\"}")
            : body(documentNumber, role);

        var response = post(adminToken()).body(requestBody)
            .when().post("/workers").then().statusCode(expectedStatus);

        if (expectedCode != null) {
            response.body("code", equalTo(expectedCode));
            assertEquals(0, fixtures.countWorkersByDocumentNumber(documentNumber),
                "un cuerpo rechazado no puede dejar el trabajador");
            return;
        }

        int id = response.extract().path("id");
        if ("si".equals(expectsRow)) {
            var driverRow = fixtures.driverRowOf(id);
            assertNotNull(driverRow, "el cargo " + role + " (" + mode + ") tiene que dejar su ficha");
            assertEquals(licenseNumber, driverRow.licenseNumber());
        } else {
            assertNull(fixtures.driverRowOf(id),
                "el cargo " + role + " (" + mode + ") sin ficha no puede dejar una fila");
        }
    }

    @Test
    void create_roleRequired_withProfile_createsTheDriverRow() {
        int id = post(adminToken())
            .body(bodyWithDriver("ZTESTC020", "driver", "{\"licenseNumber\":\"ZTESTL020\"}"))
            .when().post("/workers").then().statusCode(201)
            .body("driver.id", notNullValue())
            .body("driver.licenseNumber", equalTo("ZTESTL020"))
            .body("driver.licenseCategory", nullValue())
            .body("driver.status", equalTo("AVAILABLE"))
            .body("driver.isActive", equalTo(true))
            .extract().path("id");

        // La columna es obligatoria y hasta este PR nada la llenaba.
        var driverRow = fixtures.driverRowOf(id);
        assertNotNull(driverRow.createdAt(), "drivers.created_at no puede quedar nulo");
    }




    @Test
    void create_roleOptional_withProfile_returns201WithTheRow() {
        int id = post(adminToken()).body(bodyWithDriver("ZTESTC024", "assistant",
            "{\"licenseNumber\":\"ZTESTL024\",\"licenseCategory\":\"A-IIb\"}"))
        .when().post("/workers")
        .then().statusCode(201)
            .body("driver.licenseNumber", equalTo("ZTESTL024"))
            .body("driver.licenseCategory", equalTo("A-IIb"))
            .extract().path("id");

        // Por SIMETRIA con su gemelo sin ficha, que si necesita mirar la fila para afirmar que
        // no existe. Aca no agrega cobertura: el 201 se relee de la base, asi que sin fila la
        // asercion de la licencia de arriba ya estaria en rojo. Se deja para que el par se lea
        // junto y nadie borre la del gemelo creyendo que sobra.
        assertNotNull(fixtures.driverRowOf(id));
    }

    /** Sin este par, devolver siempre disponible pasa. */
    @Test
    void create_driverStatusExplicit_isHonored() {
        post(adminToken()).body(bodyWithDriver("ZTESTC025", "driver",
            "{\"licenseNumber\":\"ZTESTL025\",\"status\":\"MAINTENANCE\"}"))
        .when().post("/workers")
        .then().statusCode(201).body("driver.status", equalTo("MAINTENANCE"));
    }

    /**
     * El estado se resuelve por NOMBRE sin distinguir caja y se queda con el menor id.
     *
     * <p>OJO con lo que este caso puede y no puede medir, porque es lo contrario de lo que
     * parece. En la base de desarrollo la fila en mayuscula es la de MENOR id, asi que una
     * busqueda por nombre exacto devuelve la misma fila que la que no distingue caja y este
     * caso sigue en verde. Donde esa busqueda falla es en produccion, que solo tiene las
     * minusculas: ahi no encontraria nada y el alta de cualquier ficha se caeria. Lo que este
     * caso fija es que el id elegido sea el menor de los que coinciden sin distinguir caja; que
     * el metodo resuelva en los dos ambientes lo miden los casos del repositorio, con filas
     * sembradas, y que el servicio siga llamandolo lo mide su caso unitario.
     */
    @Test
    void create_theProfileStatus_isResolvedIgnoringCaseAndTakesTheLowestId() {
        int id = post(adminToken())
            .body(bodyWithDriver("ZTESTC026", "driver", "{\"licenseNumber\":\"ZTESTL026\"}"))
            .when().post("/workers").then().statusCode(201).extract().path("id");

        assertEquals(fixtures.lowestResourceStatusIdIgnoringCase("available"),
            fixtures.driverRowOf(id).statusId());
    }

    /**
     * La ficha en conflicto se siembra INACTIVA a proposito: el indice unico no mira el estado,
     * asi que un chequeo previo que filtrara por activo dejaria pasar el alta y recien chocaria
     * contra la base.
     *
     * <p>Este caso NO mide la traduccion de la violacion: el chequeo previo corta antes de
     * grabar nada. Tampoco distingue si el chequeo previo mira las fichas apagadas, porque si
     * las filtrara saldria el mismo conflicto por el indice: eso se mide llamando al metodo,
     * en los casos de los chequeos previos. Lo que este caso fija es la respuesta completa que
     * ve quien manda una licencia ya tomada: el codigo y que no quede nada.
     */
    @Test
    void create_withATakenLicense_returns409_WRK007_andLeavesNoWorker() {
        int other = fixtures.seedWorker("ZTESTC030", "Ana", "Silva", "driver", true);
        fixtures.seedDriverProfileFor(other, "ZTESTL030", null, WarehouseTestData.STATUS_AVAILABLE, false);

        post(adminToken())
            .body(bodyWithDriver("ZTESTC031", "driver", "{\"licenseNumber\":\"ZTESTL030\"}"))
        .when().post("/workers")
        .then().statusCode(409).body("code", equalTo("WRK-007"));

        assertEquals(0, fixtures.countWorkersByDocumentNumber("ZTESTC031"),
            "si la ficha falla, el trabajador no puede quedar");
    }

    // ---------- validacion del cuerpo (COM-001) -----------------------------------

    /**
     * Los DOS, no uno: con {@code @Valid} solo, el cuerpo nulo pasa y revienta mas adentro con
     * un 500. Son dos vias distintas al mismo {@code @NotNull}.
     */
    @Test
    void create_withEmptyBody_returns400_COM001() {
        post(adminToken()).body("")
        .when().post("/workers")
        .then().statusCode(400)
            .contentType("application/problem+json")
            .body("code", equalTo("COM-001"));
    }

    @Test
    void create_withExplicitNullBody_returns400_COM001() {
        post(adminToken()).body("null")
        .when().post("/workers")
        .then().statusCode(400).body("code", equalTo("COM-001"));
    }

    /** Un solo caso fija los seis obligatorios: sacarle la anotacion a cualquiera muere aca. */
    @Test
    void create_withAnEmptyJsonObject_reportsTheSixRequiredFields() {
        post(adminToken()).body("{}")
        .when().post("/workers")
        .then().statusCode(400)
            .body("code", equalTo("COM-001"))
            .body("errors.field", containsInAnyOrder(
                "firstName", "lastName", "documentTypeId", "documentNumber", "role", "hireDate"));
    }

    @ParameterizedTest(name = "{0} invalido -> 400 COM-001")
    @CsvSource({
        "firstName,      '\"firstName\":\"   \"'",
        "lastName,       '\"lastName\":\"   \"'",
        "documentNumber, '\"documentNumber\":\"   \"'",
        "role,           '\"role\":\"   \"'",
    })
    void create_withABlankRequiredText_returns400_COM001(String field, String override) {
        post(adminToken()).body(bodyOverriding(override, "ZTESTC040"))
        .when().post("/workers")
        .then().statusCode(400)
            .body("code", equalTo("COM-001"))
            .body("errors.field", hasItem(field));

        assertEquals(0, fixtures.countWorkersByDocumentNumber("ZTESTC040"));
    }

    @ParameterizedTest(name = "{0} demasiado largo -> 400 COM-001")
    @CsvSource({"firstName, 101", "lastName, 101", "documentNumber, 21", "role, 51"})
    void create_withATextOverItsMaximum_returns400_COM001(String field, int length) {
        String tooLong = "A".repeat(length);
        post(adminToken()).body(bodyOverriding("\"" + field + "\":\"" + tooLong + "\"", "ZTESTC041"))
        .when().post("/workers")
        .then().statusCode(400)
            .body("code", equalTo("COM-001"))
            .body("errors.field", hasItem(field));
    }

    /**
     * La cadena vacia tiene que ser 400 y no "se guarda vacio": el formato de nueve digitos no
     * aplica al nulo pero si a la cadena vacia, y ese es el borde entre "el formulario manda
     * nulo cuando esta vacio" y "manda comilla-comilla".
     */
    @ParameterizedTest(name = "phone {0} -> 400 COM-001")
    @ValueSource(strings = {"12345", "1234567890", "abcdefghi", ""})
    void create_withAMalformedPhone_returns400_COM001(String phone) {
        post(adminToken()).body(bodyOverriding("\"phone\":\"" + phone + "\"", "ZTESTC042"))
        .when().post("/workers")
        .then().statusCode(400)
            .body("code", equalTo("COM-001"))
            .body("errors.field", hasItem("phone"));
    }

    /**
     * La licencia en blanco sale como COM-001 con la RUTA ANIDADA del campo, no como el error
     * de correspondencia: ese codigo es SOLO sobre la presencia del objeto frente al cargo. Es
     * la contradiccion que el diseno resolvio, y esta asercion es lo unico que la sostiene.
     */
    @Test
    void create_withABlankLicenseNumber_returns400_COM001_namingTheNestedField() {
        post(adminToken()).body(bodyWithDriver("ZTESTC043", "driver", "{\"licenseNumber\":\"   \"}"))
        .when().post("/workers")
        .then().statusCode(400)
            .body("code", equalTo("COM-001"))
            .body("errors.field", hasItem("driver.licenseNumber"));
    }

    @Test
    void create_withALicenseNumberOverItsMaximum_returns400_COM001() {
        post(adminToken()).body(bodyWithDriver("ZTESTC046", "driver",
            "{\"licenseNumber\":\"" + "A".repeat(21) + "\"}"))
        .when().post("/workers")
        .then().statusCode(400)
            .body("code", equalTo("COM-001"))
            .body("errors.field", hasItem("driver.licenseNumber"));
    }

    @Test
    void create_withALicenseCategoryOverItsMaximum_returns400_COM001() {
        post(adminToken()).body(bodyWithDriver("ZTESTC044", "driver",
            "{\"licenseNumber\":\"ZTESTL044\",\"licenseCategory\":\"" + "A".repeat(21) + "\"}"))
        .when().post("/workers")
        .then().statusCode(400).body("code", equalTo("COM-001"));
    }

    /**
     * Lo que no se puede leer sale como 400 SIN cuerpo Problem: falla el deserializador antes
     * de la validacion. Esta escrito en el contrato y se fija aca para que nadie lo "arregle".
     */
    @ParameterizedTest(name = "cuerpo ilegible: {0}")
    @CsvSource({
        "'\"hireDate\":\"2026-13-45\"'",
        "'\"documentTypeId\":\"abc\"'",
        "'\"driver\":{\"licenseNumber\":\"ZTESTL045\",\"status\":\"DISPONIBLE\"}'",
    })
    void create_withAnUnreadableBody_returns400WithoutProblem(String override) {
        post(adminToken()).body(bodyOverriding(override, "ZTESTC045"))
        .when().post("/workers")
        .then().statusCode(400)
            // Sin codigo: es lo UNICO que distingue este 400 del de validacion, y es lo que el
            // contrato promete. Sin esta linea, agregar un traductor de la excepcion del lector
            // de JSON dejaria el caso en verde y el contrato a la deriva.
            .body("code", nullValue());

        assertEquals(0, fixtures.countWorkersByDocumentNumber("ZTESTC045"));
    }

    /** El cuerpo valido con UN campo reemplazado; el resto queda igual. */
    private String bodyOverriding(String override, String documentNumber) {
        return String.format("""
            {"firstName":"Juan","lastName":"Pérez","documentTypeId":%d,"documentNumber":"%s",
             "role":"operator","hireDate":"2024-03-01",%s}""",
            documentTypeId(), documentNumber, override);
    }

    // ---------- validaciones de negocio -------------------------------------------

    @Test
    void create_withANonexistentDocumentType_returns400_WRK003() {
        post(adminToken()).body("""
            {"firstName":"Juan","lastName":"Pérez","documentTypeId":999999,
             "documentNumber":"ZTESTC050","role":"operator","hireDate":"2024-03-01"}""")
        .when().post("/workers")
        .then().statusCode(400).body("code", equalTo("WRK-003"));

        assertEquals(0, fixtures.countWorkersByDocumentNumber("ZTESTC050"));
    }

    /** Este es el que mata un chequeo que solo pregunta si el tipo existe. */
    @Test
    void create_withAnInactiveDocumentType_returns400_WRK003() {
        int retired = fixtures.seedDocumentType("ZTDOC80", "ZTEST retirado", 20, null, false);

        post(adminToken()).body("""
            {"firstName":"Juan","lastName":"Pérez","documentTypeId":%d,
             "documentNumber":"ZTESTC051","role":"operator","hireDate":"2024-03-01"}"""
            .formatted(retired))
        .when().post("/workers")
        .then().statusCode(400).body("code", equalTo("WRK-003"));
    }

    /** El par fija el limite por los dos lados: comparar con menor estricto mata el segundo. */
    @Test
    void create_withANumberLongerThanTheTypeMaxLength_returns400_WRK004() {
        int shortType = fixtures.seedDocumentType("ZTDOC81", "ZTEST corto", 9, null, true);

        post(adminToken()).body("""
            {"firstName":"Juan","lastName":"Pérez","documentTypeId":%d,
             "documentNumber":"ZTESTC0001","role":"operator","hireDate":"2024-03-01"}"""
            .formatted(shortType))
        .when().post("/workers")
        .then().statusCode(400).body("code", equalTo("WRK-004"));
    }

    @Test
    void create_withANumberOfExactlyTheMaxLength_returns201() {
        int shortType = fixtures.seedDocumentType("ZTDOC82", "ZTEST justo", 9, null, true);

        post(adminToken()).body("""
            {"firstName":"Juan","lastName":"Pérez","documentTypeId":%d,
             "documentNumber":"ZTESTC052","role":"operator","hireDate":"2024-03-01"}"""
            .formatted(shortType))
        .when().post("/workers")
        .then().statusCode(201);
    }

    @Test
    void create_withANumberThatDoesNotMatchThePattern_returns400_WRK004() {
        int patterned = fixtures.seedDocumentType("ZTDOC83", "ZTEST patron", 9, "^ZTEST[A-Z0-9]{4}$", true);

        post(adminToken()).body("""
            {"firstName":"Juan","lastName":"Pérez","documentTypeId":%d,
             "documentNumber":"ZTEST-053","role":"operator","hireDate":"2024-03-01"}"""
            .formatted(patterned))
        .when().post("/workers")
        .then().statusCode(400).body("code", equalTo("WRK-004"));
    }

    @Test
    void create_withANumberThatMatchesThePattern_returns201() {
        int patterned = fixtures.seedDocumentType("ZTDOC84", "ZTEST patron ok", 9, "^ZTEST[A-Z0-9]{4}$", true);

        post(adminToken()).body("""
            {"firstName":"Juan","lastName":"Pérez","documentTypeId":%d,
             "documentNumber":"ZTESTC054","role":"operator","hireDate":"2024-03-01"}"""
            .formatted(patterned))
        .when().post("/workers")
        .then().statusCode(201);
    }

    /** Un patron nulo mide SOLO el largo; sin este caso se leeria como "no coincide con nada". */
    @Test
    void create_withATypeWithoutPattern_onlyChecksTheLength() {
        int noPattern = fixtures.seedDocumentType("ZTDOC85", "ZTEST sin patron", 20, null, true);

        post(adminToken()).body("""
            {"firstName":"Juan","lastName":"Pérez","documentTypeId":%d,
             "documentNumber":"ZTESTC055","role":"operator","hireDate":"2024-03-01"}"""
            .formatted(noPattern))
        .when().post("/workers")
        .then().statusCode(201);
    }

    /**
     * El formato tiene que cubrir el numero ENTERO y no un pedazo. Un patron SIN anclas es lo
     * unico que distingue una coincidencia completa de una parcial, y el tipo de documento que
     * hoy tiene el padron ni siquiera define formato (medido), asi que sin este caso sembrado
     * la diferencia no se veria nunca.
     */
    @Test
    void create_thePatternMustMatchTheWholeNumber_notAFragment() {
        int unanchored = fixtures.seedDocumentType("ZTDOC86", "ZTEST sin anclas", 20, "ZTEST\\d{3}", true);

        post(adminToken()).body("""
            {"firstName":"Juan","lastName":"Pérez","documentTypeId":%d,
             "documentNumber":"ZTEST123XX","role":"operator","hireDate":"2024-03-01"}"""
            .formatted(unanchored))
        .when().post("/workers")
        .then().statusCode(400).body("code", equalTo("WRK-004"));
    }

    @Test
    void create_withANonexistentRole_returns400_WRK005() {
        post(adminToken()).body(body("ZTESTC056", "no_existe"))
        .when().post("/workers")
        .then().statusCode(400).body("code", equalTo("WRK-005"));
    }

    /**
     * Un cargo se retira poniendolo inactivo: desde ahi no se asigna mas, aunque quien lo tenga
     * lo conserve. Una busqueda por nombre sin filtro de vigencia muere aca.
     */
    @Test
    void create_withAnInactiveRole_returns400_WRK005() {
        fixtures.seedRole("ztestrole80", "ZTEST cargo retirado", 1, false, "NONE", false);

        post(adminToken()).body(body("ZTESTC057", "ztestrole80"))
        .when().post("/workers")
        .then().statusCode(400).body("code", equalTo("WRK-005"));
    }

    @Test
    void create_withTheDocumentOfAnotherWorker_returns409_WRK002() {
        fixtures.seedWorker("ZTESTC060", "Ana", "Silva", "operator", true);

        post(adminToken()).body(body("ZTESTC060", "operator"))
        .when().post("/workers")
        .then().statusCode(409).body("code", equalTo("WRK-002"));

        assertEquals(1, fixtures.countWorkersByDocumentNumber("ZTESTC060"));
    }

    /**
     * La unicidad es del NUMERO solo, no del par tipo-numero: es lo que impone la restriccion
     * de la base, y un chequeo por el par no lo ve.
     */
    @Test
    void create_withTheSameNumberAndADifferentDocumentType_returns409_WRK002() {
        fixtures.seedWorker("ZTESTC061", "Ana", "Silva", "operator", true);
        int otherType = fixtures.seedDocumentType("ZTDOC87", "ZTEST otro tipo", 20, null, true);

        post(adminToken()).body("""
            {"firstName":"Juan","lastName":"Pérez","documentTypeId":%d,
             "documentNumber":"ZTESTC061","role":"operator","hireDate":"2024-03-01"}"""
            .formatted(otherType))
        .when().post("/workers")
        .then().statusCode(409).body("code", equalTo("WRK-002"));
    }

    /** El conflicto no nombra al otro trabajador ni su id. */
    @Test
    void create_theConflictBody_doesNotNameTheOtherWorker() {
        fixtures.seedWorker("ZTESTC062", "Ana", "Silva", "operator", true);

        post(adminToken()).body(body("ZTESTC062", "operator"))
        .when().post("/workers")
        .then().statusCode(409)
            .body("detail", equalTo("Ya existe un trabajador con ese numero de documento"))
            .body("detail", not(org.hamcrest.Matchers.containsString("ZTESTC062")))
            .body("keySet()", containsInAnyOrder(
                "type", "title", "status", "detail", "instance", "code", "traceId"));
    }


    // ---------- permisos y organigrama --------------------------------------------

    @Test
    void create_withoutToken_returns401() {
        given().contentType("application/json").body(body("ZTESTC070", "operator"))
        .when().post("/workers").then().statusCode(401);
    }

    @Test
    void create_withAMalformedToken_returns401() {
        post("no-es-un-token").body(body("ZTESTC071", "operator"))
        .when().post("/workers").then().statusCode(401);
    }

    /**
     * El encargado de almacen va PRIMERO en la lista: es el unico de los tres que si entra al
     * listado, asi que una lista de roles copiada del listado muere exactamente ahi y en ningun
     * otro lado.
     *
     * <p>El actor se SIEMBRA y el token se ancla a su usuario, y eso no es adorno. Con un token
     * fabricado sobre un sujeto que no existe, el caso pasaba por el motivo equivocado: si la
     * lista de roles dejara entrar al encargado de almacen, la regla del organigrama no
     * encontraria a su actor y devolveria el MISMO 403 con el MISMO codigo, asi que el caso
     * seguia en verde con la guarda rota. Lo descubrio una mutacion que sobrevivio.
     */
    @ParameterizedTest(name = "rol {0} no puede dar de alta")
    @CsvSource({
        "warehouse_keeper, 41",
        "sales,            42",
        "dispatcher,       43",
    })
    void create_withARoleOutsideTheFour_returns403_COM003(String role, String suffix) {
        int actorUserId = fixtures.seedActorUser(role, suffix);

        post(fabricateTokenForUser(actorUserId, "ztestuser" + suffix, role))
            .body(body("ZTESTC0" + suffix, "operator"))
        .when().post("/workers")
        .then().statusCode(403).body("code", equalTo("COM-003"));

        assertEquals(0, fixtures.countWorkersByDocumentNumber("ZTESTC0" + suffix));
    }

    @ParameterizedTest(name = "{0} da de alta un operador")
    @CsvSource({
        "general_manager,    91",
        "operations_manager, 92",
        "finance_manager,    93",
    })
    void create_withEachMaintenanceRole_returns201(String role, String suffix) {
        int actorUserId = fixtures.seedActorUser(role, suffix);

        post(fabricateTokenForUser(actorUserId, "ztestuser" + suffix, role))
            .body(body("ZTESTC07" + suffix.charAt(1), "operator"))
        .when().post("/workers").then().statusCode(201);
    }

    /**
     * Los cuatro casos fijos del organigrama, con el nivel del objetivo leido de la base. Los
     * dos sentidos y el borde de igualdad quedan tomados: ninguna constante los satisface a
     * todos.
     */
    @ParameterizedTest(name = "{0} sobre {1} -> {2} {3}")
    @CsvSource({
        "finance_manager, driver,             201, ,        81, ZTESTC181",
        "finance_manager, sales,              403, WRK-006, 82, ZTESTC182",
        "general_manager, finance_manager,    201, ,        83, ZTESTC183",
        "general_manager, operations_manager, 403, WRK-006, 84, ZTESTC184",
    })
    void create_appliesTheRankRuleOfTheOrgChart(String actorRole, String targetRole,
            int expectedStatus, String expectedCode, String suffix, String documentNumber) {
        int actorUserId = fixtures.seedActorUser(actorRole, suffix);
        String requestBody = "driver".equals(targetRole)
            ? bodyWithDriver(documentNumber, targetRole, "{\"licenseNumber\":\"ZTESTL1" + suffix + "\"}")
            : body(documentNumber, targetRole);

        var response = post(fabricateTokenForUser(actorUserId, "ztestuser" + suffix, actorRole))
            .body(requestBody).when().post("/workers").then().statusCode(expectedStatus);
        // El CODIGO y no solo el numero: si la regla de rango ni llegara a ejecutarse porque el
        // actor no resuelve, saldria el mismo 403 con otro codigo y estas filas seguirian en
        // verde con la regla nunca corrida. Es el defecto que la ronda anterior encontro en el
        // caso de los roles, cincuenta lineas mas arriba, y que arregle ahi y no aca.
        if (expectedCode != null) {
            response.body("code", equalTo(expectedCode));
        }
    }

    /** El par fija que la exencion es del administrador y de nadie mas. */
    @Test
    void create_admin_createsAnotherAdmin_returns201() {
        post(adminToken()).body(body("ZTESTC085", "admin"))
        .when().post("/workers").then().statusCode(201);
    }

    @Test
    void create_aManager_createsAnAdmin_returns403_WRK006() {
        int actorUserId = fixtures.seedActorUser("general_manager", "86");

        post(fabricateTokenForUser(actorUserId, "ztestuser86", "general_manager"))
            .body(body("ZTESTC086", "admin"))
        .when().post("/workers")
        .then().statusCode(403).body("code", equalTo("WRK-006"));
    }

    /**
     * El caso que el dueno habilito al bajar al encargado de almacen a nivel 1. Es ademas el
     * unico que se rompe si alguien "corrige" la tabla de niveles con el texto que estuvo
     * publicado en el contrato.
     */
    @Test
    void create_financeManager_createsAWarehouseKeeper_returns201() {
        int actorUserId = fixtures.seedActorUser("finance_manager", "87");

        post(fabricateTokenForUser(actorUserId, "ztestuser87", "finance_manager"))
            .body(body("ZTESTC087", "warehouse_keeper"))
        .when().post("/workers").then().statusCode(201);
    }

    /**
     * La escalada mas barata que existe contra este endpoint: cambiarle el cargo a alguien deja
     * su token viejo con el grupo anterior hasta que vence. Si la regla leyera el grupo, ese
     * token seguiria valiendo el nivel de ayer.
     */
    @Test
    void create_theRankLevelComesFromTheDatabase_notFromTheToken() {
        int actorUserId = fixtures.seedActorUser("finance_manager", "88");

        post(fabricateTokenForUser(actorUserId, "ztestuser88", "general_manager"))
            .body(body("ZTESTC088", "sales"))
        .when().post("/workers")
        .then().statusCode(403).body("code", equalTo("WRK-006"));
    }

    /** Gemelo del anterior por el otro lado: no hay nivel memorizado entre pedidos. */
    @Test
    void create_afterTheActorRoleChangesInTheDatabase_theNextWriteObeysTheNewOne() {
        int actorUserId = fixtures.seedActorUser("general_manager", "89");
        String token = fabricateTokenForUser(actorUserId, "ztestuser89", "general_manager");

        post(token).body(body("ZTESTC089", "sales")).when().post("/workers").then().statusCode(201);

        fixtures.setUserRole(actorUserId, "finance_manager");

        post(token).body(body("ZTESTC090", "sales"))
        .when().post("/workers")
        .then().statusCode(403).body("code", equalTo("WRK-006"));
    }

    /** El 403 de rango no lleva cargo, ni nivel, ni id: los dos disparadores dan lo mismo. */
    @Test
    void create_theRankForbiddenBody_carriesNoRoleNorLevel() {
        int actorUserId = fixtures.seedActorUser("finance_manager", "80");
        String token = fabricateTokenForUser(actorUserId, "ztestuser80", "finance_manager");

        // Se afirma el CODIGO en las dos: dos rechazos por "no hay actor" tambien serian
        // identicos entre si, y el caso pasaria sin que el rango se haya evaluado nunca.
        String sameLevel = post(token).body(body("ZTESTC091", "sales"))
            .when().post("/workers").then().statusCode(403)
            .body("code", equalTo("WRK-006")).extract().asString();
        String higherLevel = post(token).body(body("ZTESTC092", "general_manager"))
            .when().post("/workers").then().statusCode(403)
            .body("code", equalTo("WRK-006")).extract().asString();

        // El identificador de traza cambia por pedido y no es parte del motivo: se borra de los
        // dos antes de comparar. Todo lo demas tiene que ser identico, cargo incluido.
        assertEquals(withoutTraceId(sameLevel), withoutTraceId(higherLevel),
            "los dos motivos de rango tienen que dar el MISMO cuerpo");
    }

    // ---------- orden de evaluacion ------------------------------------------------

    /**
     * Cada caso arma un cuerpo que dispara DOS errores a la vez y fija cual gana. Es la unica
     * forma de proteger una cadena de comprobaciones: sin ellos, mover un bloque tres lineas
     * arriba no rompe nada.
     */
    @Test
    void order_forbiddenRoleBeatsAnInvalidBody() {
        post(fabricateAccessToken("ztest-sales", "sales")).body("{}")
        .when().post("/workers")
        .then().statusCode(403).body("code", equalTo("COM-003"));
    }

    @Test
    void order_invalidFieldBeatsANonexistentRole() {
        post(adminToken()).body("""
            {"lastName":"Pérez","documentTypeId":%d,"documentNumber":"ZTESTC100",
             "role":"no_existe","hireDate":"2024-03-01"}"""
            .formatted(documentTypeId()))
        .when().post("/workers")
        .then().statusCode(400).body("code", equalTo("COM-001"));
    }

    @Test
    void order_nonexistentRoleBeatsAnInvalidDocumentType() {
        post(adminToken()).body("""
            {"firstName":"Juan","lastName":"Pérez","documentTypeId":999999,
             "documentNumber":"ZTESTC101","role":"no_existe","hireDate":"2024-03-01"}""")
        .when().post("/workers")
        .then().statusCode(400).body("code", equalTo("WRK-005"));
    }

    @Test
    void order_outOfRankBeatsAnInvalidDocumentType() {
        int actorUserId = fixtures.seedActorUser("finance_manager", "70");

        post(fabricateTokenForUser(actorUserId, "ztestuser70", "finance_manager")).body("""
            {"firstName":"Juan","lastName":"Pérez","documentTypeId":999999,
             "documentNumber":"ZTESTC102","role":"sales","hireDate":"2024-03-01"}""")
        .when().post("/workers")
        .then().statusCode(403).body("code", equalTo("WRK-006"));
    }

    @Test
    void order_outOfRankBeatsADuplicateDocument() {
        int actorUserId = fixtures.seedActorUser("finance_manager", "71");
        fixtures.seedWorker("ZTESTC103", "Ana", "Silva", "operator", true);

        post(fabricateTokenForUser(actorUserId, "ztestuser71", "finance_manager"))
            .body(body("ZTESTC103", "sales"))
        .when().post("/workers")
        .then().statusCode(403).body("code", equalTo("WRK-006"));
    }

    @Test
    void order_profileMismatchBeatsADuplicateDocument() {
        fixtures.seedWorker("ZTESTC104", "Ana", "Silva", "operator", true);

        post(adminToken())
            .body(bodyWithDriver("ZTESTC104", "operator", "{\"licenseNumber\":\"ZTESTL104\"}"))
        .when().post("/workers")
        .then().statusCode(400).body("code", equalTo("WRK-008"));
    }

    @Test
    void order_duplicateDocumentBeatsADuplicateLicense() {
        int other = fixtures.seedWorker("ZTESTC105", "Ana", "Silva", "driver", true);
        fixtures.seedDriverProfileFor(other, "ZTESTL105", null, WarehouseTestData.STATUS_AVAILABLE, true);

        post(adminToken())
            .body(bodyWithDriver("ZTESTC105", "driver", "{\"licenseNumber\":\"ZTESTL105\"}"))
        .when().post("/workers")
        .then().statusCode(409).body("code", equalTo("WRK-002"));
    }

    // ---------- auditoria -----------------------------------------------------------

    @Test
    void create_leavesExactlyOneAuditRowOfTypeCreated() {
        int actorUserId = fixtures.seedActorUser("general_manager", "60");

        int id = post(fabricateTokenForUser(actorUserId, "ztestuser60", "general_manager"))
            .body(body("ZTESTC110", "operator"))
            .when().post("/workers").then().statusCode(201).extract().path("id");

        var rows = fixtures.workerAuditRows(id);
        assertEquals(1, rows.size(), "el alta deja UNA fila, no una por campo");
        var row = rows.get(0);
        assertEquals("CREATED", row.changeType());
        assertEquals(actorUserId, row.changedBy());
        assertNotNull(row.loggedAt());
        assertNull(row.fieldName());
        assertNull(row.fieldLabel());
        assertNull(row.oldValue());
        assertNull(row.newValue());
        assertNull(row.reason());
    }

    /** Con una sola alta, fijar el autor al administrador o a una constante pasaria. */
    @Test
    void create_theAuditAuthorIsTheSessionUser_notTheAdmin() {
        int firstActor = fixtures.seedActorUser("general_manager", "61");
        int secondActor = fixtures.seedActorUser("finance_manager", "62");

        int firstId = post(fabricateTokenForUser(firstActor, "ztestuser61", "general_manager"))
            .body(body("ZTESTC111", "operator"))
            .when().post("/workers").then().statusCode(201).extract().path("id");
        int secondId = post(fabricateTokenForUser(secondActor, "ztestuser62", "finance_manager"))
            .body(body("ZTESTC112", "operator"))
            .when().post("/workers").then().statusCode(201).extract().path("id");

        assertEquals(firstActor, fixtures.workerAuditRows(firstId).get(0).changedBy());
        assertEquals(secondActor, fixtures.workerAuditRows(secondId).get(0).changedBy());
    }

    @Test
    void create_withADriverProfile_stillLeavesExactlyOneAuditRow() {
        int id = post(adminToken())
            .body(bodyWithDriver("ZTESTC113", "driver", "{\"licenseNumber\":\"ZTESTL113\"}"))
            .when().post("/workers").then().statusCode(201).extract().path("id");

        assertEquals(1, fixtures.workerAuditRows(id).size());
    }

    @Test
    void create_thatIsRejected_leavesNoAuditRow() {
        int actorUserId = fixtures.seedActorUser("general_manager", "63");
        // Un alta que SI deja rastro primero: sin esto el conteo previo es cero por
        // construccion y la comparacion final no distingue "no escribio" de "recien nacio".
        post(fabricateTokenForUser(actorUserId, "ztestuser63", "general_manager"))
            .body(body("ZTESTC113B", "operator"))
        .when().post("/workers").then().statusCode(201);

        fixtures.seedWorker("ZTESTC114", "Ana", "Silva", "operator", true);
        int before = fixtures.countWorkerAuditRowsByAuthor(actorUserId);
        assertEquals(1, before, "el actor ya tiene que tener una fila para que el delta mida algo");

        post(fabricateTokenForUser(actorUserId, "ztestuser63", "general_manager"))
            .body(body("ZTESTC114", "operator"))
        .when().post("/workers").then().statusCode(409);

        assertEquals(before, fixtures.countWorkerAuditRowsByAuthor(actorUserId));
    }

    /**
     * La ultima asercion documenta que el cargo dejo de tener dos origenes: el dia que alguien
     * vuelva a escribir la columna muerta, esto lo dice.
     */
    @Test
    void create_theWorkerRow_carriesTheAuthorAndLeavesTheDeadColumnAlone() {
        int actorUserId = fixtures.seedActorUser("general_manager", "64");

        int id = post(fabricateTokenForUser(actorUserId, "ztestuser64", "general_manager"))
            .body(body("ZTESTC115", "operator"))
            .when().post("/workers").then().statusCode(201).extract().path("id");

        var row = fixtures.workerRowOf(id);
        assertEquals(fixtures.roleIdOf("operator"), row.roleId(), "el cargo pedido, en la fila");
        assertEquals(LocalDate.of(2024, 3, 1), row.hireDate());
        assertEquals(actorUserId, row.createdBy());
        assertEquals(actorUserId, row.updatedBy());
        assertNull(row.position(), "la columna de cargo vieja no se escribe nunca mas");
    }

    // ---------- lo que pone el servidor y el cuerpo no mueve -------------------------

    @Test
    void create_withServerOwnedFieldsInTheBody_ignoresThem() {
        int actorUserId = fixtures.seedActorUser("general_manager", "65");

        post(fabricateTokenForUser(actorUserId, "ztestuser65", "general_manager")).body("""
            {"id":999999,"firstName":"Juan","lastName":"Pérez","documentTypeId":%d,
             "documentNumber":"ZTESTC120","role":"operator","hireDate":"2024-03-01",
             "isActive":false,"hasUser":true,"createdAt":"2000-01-01T00:00:00Z",
             "updatedAt":"2000-01-01T00:00:00Z","createdBy":{"id":1},"updatedBy":{"id":1}}"""
            .formatted(documentTypeId()))
        .when().post("/workers")
        .then().statusCode(201)
            .body("id", not(equalTo(999999)))
            .body("isActive", equalTo(true))
            .body("hasUser", equalTo(false))
            .body("createdBy.id", equalTo(actorUserId))
            .body("updatedBy.id", equalTo(actorUserId))
            .body("createdAt", not(org.hamcrest.Matchers.startsWith("2000")))
            .body("updatedAt", not(org.hamcrest.Matchers.startsWith("2000")));
    }

    @Test
    void create_withDriverOwnedFieldsInTheBody_ignoresThem() {
        post(adminToken()).body(bodyWithDriver("ZTESTC121", "driver",
            "{\"id\":999,\"licenseNumber\":\"ZTESTL121\",\"isActive\":false}"))
        .when().post("/workers")
        .then().statusCode(201)
            .body("driver.id", not(equalTo(999)))
            .body("driver.isActive", equalTo(true));
    }

    /**
     * Iguales, no solo "las dos presentes": con cada una afirmada por separado, intercambiarlas
     * o dejar una nula pasaria.
     */
    @Test
    void create_createdAtAndUpdatedAt_areTheSameInstantOnAnAlta() {
        var response = post(adminToken()).body(body("ZTESTC122", "operator"))
            .when().post("/workers").then().statusCode(201).extract();

        String createdAt = response.path("createdAt");
        String updatedAt = response.path("updatedAt");
        assertEquals(createdAt, updatedAt);
    }

    @Test
    void create_createdBy_carriesTheFieldsOfAUserReference() {
        int actorUserId = fixtures.seedActorUser("general_manager", "66");

        post(fabricateTokenForUser(actorUserId, "ztestuser66", "general_manager"))
            .body(body("ZTESTC123", "operator"))
        .when().post("/workers")
        .then().statusCode(201)
            .body("createdBy.id", equalTo(actorUserId))
            .body("createdBy.username", equalTo("ztestuser66"))
            .body("createdBy.fullName", notNullValue())
            .body("createdBy.position", equalTo("Gerente General"));
    }


    private String withoutTraceId(String problemBody) {
        return problemBody.replaceAll("\"traceId\":\"[^\"]*\"", "\"traceId\":\"X\"");
    }


    // ---------- la transaccion unica ------------------------------------------------

    /**
     * ESTE es el caso que prueba que el trabajador y su ficha son UNA transaccion.
     *
     * <p>Una licencia ya tomada por una fila CONFIRMADA no sirve para probarlo: ahi el chequeo
     * previo rechaza antes de grabar nada, y un trabajador huerfano no podria existir aunque
     * las dos escrituras estuvieran separadas. Hace falta que el chequeo previo PASE y el
     * choque ocurra recien contra el indice, con el trabajador ya insertado.
     *
     * <p>Se consigue sin azar: una conexion aparte inserta la ficha y NO confirma. El alta no
     * ve esa fila, porque solo ve lo confirmado, asi que pasa el chequeo, inserta el trabajador
     * y queda esperando el indice. Al confirmar la otra conexion, el indice rechaza la ficha.
     * Si el alta y la ficha vivieran en transacciones distintas, el trabajador sobreviviria, y
     * el conteo en cero es lo que lo denuncia.
     *
     * <p>La otra conexion confirma recien al VER al alta esperando el indice, y antes del tope de
     * espera: asi el choque sale por el indice y no por el chequeo previo ni por el tope.
     */
    @Test
    void create_whenTheLicenseIsTakenByAnUncommittedTransaction_returns409_andLeavesNoWorker()
            throws Exception {
        int otherWorker = fixtures.seedWorker("ZTESTA90", "Ana", "Silva", "driver", true);
        int availableStatusId = fixtures.lowestResourceStatusIdIgnoringCase("available");
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try (Connection uncommitted = dataSource.getConnection()) {
            uncommitted.setAutoCommit(false);
            try (PreparedStatement insert = uncommitted.prepareStatement(
                "INSERT INTO public.drivers (worker_id, license_number, status_id, is_active, created_at) "
                    + "VALUES (?, ?, ?, true, now())")) {
                insert.setInt(1, otherWorker);
                insert.setString(2, "ZTESTL900");
                insert.setInt(3, availableStatusId);
                insert.executeUpdate();
            }

            Future<Respuesta> statusCode = executor.submit(() -> {
                var extracted = post(adminToken())
                    .body(bodyWithDriver("ZTEST900", "driver", "{\"licenseNumber\":\"ZTESTL900\"}"))
                    .when().post("/workers").then().extract();
                return new Respuesta(extracted.statusCode(), extracted.path("code"));
            });

            try (Connection observer = dataSource.getConnection()) {
                assertTrue(awaitWaiters(observer, backendPid(uncommitted), 1, statusCode, UNTIL_IT_ARRIVES_MILLIS),
                    "precondicion: el alta espera el indice de la licencia");
            }
            uncommitted.commit();

            var response = statusCode.get(20, TimeUnit.SECONDS);
            assertEquals(409, response.statusCode(),
                "el choque contra el indice tiene que salir como conflicto, nunca como 500");
            assertEquals("WRK-007", response.code(), "el de la licencia, no el transitorio del tope");
        } finally {
            shutdown(executor);
        }

        assertEquals(0, fixtures.countWorkersByDocumentNumber("ZTEST900"),
            "si la ficha falla, la transaccion tiene que deshacer tambien el trabajador");
        // UNA: la que confirmo la otra conexion. Si el alta hubiera dejado la suya, serian dos,
        // y eso solo puede pasar si el indice unico no la freno.
        assertEquals(1, fixtures.countDriversByLicense("ZTESTL900"),
            "la unica ficha con esa licencia tiene que ser la de la otra conexion");
    }


    /**
     * El telefono, de punta a punta: del cuerpo a la FILA.
     *
     * <p>Hasta que este caso existio, ningun caso mandaba un telefono valido: solo se afirmaba
     * que llega nulo cuando no viene, y que un formato malo da 400. Borrar la linea que lo
     * asigna en el servicio dejaba la suite entera en verde, con el endpoint aceptando un campo
     * y tirandolo en silencio. Por eso mira tambien la fila y no solo la respuesta.
     */
    @Test
    void create_withAPhone_storesItInTheRow() {
        int id = post(adminToken()).body("""
            {"firstName":"Juan","lastName":"Pérez","documentTypeId":%d,"documentNumber":"ZTESTC007",
             "phone":"912345678","role":"operator","hireDate":"2024-03-01"}"""
            .formatted(documentTypeId()))
            .when().post("/workers").then().statusCode(201)
            .body("phone", equalTo("912345678")).extract().path("id");

        given().header("Authorization", "Bearer " + adminToken())
        .when().get("/workers/" + id)
        .then().statusCode(200).body("phone", equalTo("912345678"));
    }

    /**
     * Los dos escalones del orden publicado que no ancla ningun otro caso: el tipo de documento
     * y el formato del numero se resuelven ANTES que la correspondencia de la ficha con el
     * cargo. Sin estos dos, subir esa comprobacion tres lineas compila y no rompe nada.
     */
    @Test
    void order_invalidDocumentTypeBeatsAProfileMismatch() {
        post(adminToken()).body("""
            {"firstName":"Juan","lastName":"Pérez","documentTypeId":999999,
             "documentNumber":"ZTESTC106","role":"operator","hireDate":"2024-03-01",
             "driver":{"licenseNumber":"ZTESTL106"}}""")
        .when().post("/workers")
        .then().statusCode(400).body("code", equalTo("WRK-003"));
    }

    @Test
    void order_invalidDocumentNumberBeatsAProfileMismatch() {
        int patterned = fixtures.seedDocumentType("ZTDOC88", "ZTEST patron", 9, "^ZTEST[A-Z0-9]{4}$", true);

        post(adminToken()).body("""
            {"firstName":"Juan","lastName":"Pérez","documentTypeId":%d,
             "documentNumber":"ZTEST-107","role":"operator","hireDate":"2024-03-01",
             "driver":{"licenseNumber":"ZTESTL107"}}""".formatted(patterned))
        .when().post("/workers")
        .then().statusCode(400).body("code", equalTo("WRK-004"));
    }

    /**
     * Quien tiene la sesion pero su usuario ya no esta VIGENTE no escribe.
     *
     * <p>Dar de baja a un usuario no revoca el token que ya tenia: el inicio de sesion y la
     * renovacion lo frenan, pero la ventana del token vigente no. Sin la guarda, en esa ventana
     * puede dar de alta personas y encima firmarlas con su id.
     */
    @Test
    void create_whenTheActorUserIsNoLongerActive_returns403() {
        int actorUserId = fixtures.seedActorUser("general_manager", "50");
        String token = fabricateTokenForUser(actorUserId, "ztestuser50", "general_manager");

        post(token).body(body("ZTESTC050", "operator"))
        .when().post("/workers").then().statusCode(201);

        fixtures.deactivateUser(actorUserId);

        post(token).body(body("ZTESTC051", "operator"))
        .when().post("/workers")
        .then().statusCode(403).body("code", equalTo("COM-003"));

        assertEquals(0, fixtures.countWorkersByDocumentNumber("ZTESTC051"));
    }

    /**
     * Los tres valores del dominio de disponibilidad resuelven contra el catalogo REAL de ESTE
     * ambiente.
     *
     * <p>Los casos del repositorio miden el metodo con filas sembradas, a proposito, asi que
     * ninguno mira el catalogo de verdad. Si a un ambiente le faltara una de las tres filas, un
     * cuerpo perfectamente valido devolveria un error interno que el contrato no declara. Esto
     * convierte eso en un fallo de construccion.
     */
    @Test
    void theThreeFleetStatuses_resolveAgainstTheRealCatalog() {
        for (var status : com.scaramutti.tms.sharedcatalogs.model.FleetResourceStatus.values()) {
            assertNotNull(fixtures.resourceStatusRowIdIgnoringCase(status.name()),
                "el catalogo de este ambiente no tiene fila para " + status.name());
        }
    }


    /**
     * El recorte ocurre DESPUES de la validacion del borde: un texto de largo maximo con
     * espacios alrededor se rechaza aunque recortado entre.
     *
     * <p>El javadoc del mapper afirma este comportamiento y hasta ahora no lo medía nadie, ni
     * acá ni en el módulo del que dice copiarlo. Es justo el borde que alguien podría "mejorar"
     * recortando antes de validar, y entonces cien caracteres con espacios entrarían.
     */
    @Test
    void create_withAMaximumLengthTextSurroundedBySpaces_returns400_COM001() {
        post(adminToken()).body(bodyOverriding(
            "\"firstName\":\" " + "A".repeat(100) + " \"", "ZTESTC047"))
        .when().post("/workers")
        .then().statusCode(400)
            .body("code", equalTo("COM-001"))
            .body("errors.field", hasItem("firstName"));

        assertEquals(0, fixtures.countWorkersByDocumentNumber("ZTESTC047"));
    }

    /** La categoria en blanco llega nula, igual que el resto de los textos recortados. */
    @Test
    void create_withABlankLicenseCategory_storesItAsNull() {
        int id = post(adminToken()).body(bodyWithDriver("ZTESTC048", "driver",
            "{\"licenseNumber\":\"ZTESTL048\",\"licenseCategory\":\"   \"}"))
            .when().post("/workers").then().statusCode(201)
            .body("driver.licenseCategory", nullValue()).extract().path("id");

        assertNull(fixtures.driverRowOf(id).category());
    }

    /**
     * La exencion del administrador sale de la FILA, no del grupo del token.
     *
     * <p>Es la escalada barata de un ex administrador: le cambian el cargo, su token sigue
     * diciendo admin hasta que vence. El nivel ya estaba atado a la fila por dos casos; la
     * exencion no lo estaba por ninguno.
     */
    @Test
    void create_theAdminExemption_comesFromTheDatabase_notFromTheToken() {
        int actorUserId = fixtures.seedActorUser("general_manager", "94");

        post(fabricateTokenForUser(actorUserId, "ztestuser94", "admin"))
            .body(body("ZTESTC094", "admin"))
        .when().post("/workers")
        .then().statusCode(403).body("code", equalTo("WRK-006"));

        assertEquals(0, fixtures.countWorkersByDocumentNumber("ZTESTC094"));
    }

    /**
     * El gemelo del chequeo previo del documento con el trabajador INACTIVO.
     *
     * <p>Que el chequeo previo mire a los inactivos NO lo distingue este caso: si los filtrara
     * saldria el mismo conflicto por el indice. Eso se mide llamando al metodo. Lo que este
     * caso fija es que un documento de alguien dado de baja sigue ocupado de cara al cliente.
     */
    @Test
    void create_withTheDocumentOfAnInactiveWorker_returns409_WRK002() {
        fixtures.seedWorker("ZTESTC064", "Ana", "Silva", "operator", false);

        post(adminToken()).body(body("ZTESTC064", "operator"))
        .when().post("/workers")
        .then().statusCode(409).body("code", equalTo("WRK-002"));

        assertEquals(1, fixtures.countWorkersByDocumentNumber("ZTESTC064"),
            "el inactivo sigue siendo el unico con ese documento");
    }

    /**
     * La carrera del DOCUMENTO, con el mismo molde que la de la licencia.
     *
     * <p>El contrato promete el conflicto para las DOS unicidades y sólo una estaba medida de
     * punta a punta. Sin esto, que el nombre de esa restricción dejara de reconocerse sería un
     * error interno en la primera alta concurrente y ningún caso lo vería.
     *
     * <p>Igual que su gemelo, la otra conexion confirma recien al ver al alta esperando el
     * indice, y antes del tope: la traduccion que este caso protege se ejecuta siempre.
     */
    @Test
    void create_whenTheDocumentIsTakenByAnUncommittedTransaction_returns409_WRK002()
            throws Exception {
        int roleId = fixtures.roleIdOf("operator");
        int documentTypeId = documentTypeId();
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try (Connection uncommitted = dataSource.getConnection()) {
            uncommitted.setAutoCommit(false);
            try (PreparedStatement insert = uncommitted.prepareStatement(
                "INSERT INTO public.workers (first_name, last_name, document_type_id, "
                    + "document_number, role_id, hire_date, is_active, created_at, updated_at) "
                    + "VALUES ('Zz', 'Carrera', ?, 'ZTEST901', ?, DATE '2024-03-01', true, now(), now())")) {
                insert.setInt(1, documentTypeId);
                insert.setInt(2, roleId);
                insert.executeUpdate();
            }

            Future<Respuesta> statusCode = executor.submit(() -> {
                var extracted = post(adminToken()).body(body("ZTEST901", "operator"))
                    .when().post("/workers").then().extract();
                return new Respuesta(extracted.statusCode(), extracted.path("code"));
            });

            try (Connection observer = dataSource.getConnection()) {
                assertTrue(awaitWaiters(observer, backendPid(uncommitted), 1, statusCode, UNTIL_IT_ARRIVES_MILLIS),
                    "precondicion: el alta espera el indice del documento");
            }
            uncommitted.commit();

            var response = statusCode.get(20, TimeUnit.SECONDS);
            assertEquals(409, response.statusCode(),
                "el choque contra el indice del documento sale como conflicto, nunca como 500");
            // El CODIGO y no solo el numero: un conflicto de otra restriccion daria el mismo
            // 409 y este caso existe para fijar cual.
            assertEquals("WRK-002", response.code());
        } finally {
            shutdown(executor);
        }

        // Que quede UNA fila no puede fallar (el indice prohibe la segunda). Lo falsable es
        // CUAL sobrevivio: si el alta hubiera ganado, el nombre seria el del cuerpo del pedido.
        assertEquals("Zz", fixtures.firstNameOfWorkerByDocument("ZTEST901"),
            "la fila que queda tiene que ser la de la otra conexion, no la del alta");
    }

    /** El status y el codigo de un pedido hecho en otro hilo. */
    private record Respuesta(int statusCode, String code) {}

    /**
     * Apaga el ejecutor sin dejar hilos vivos en una suite que comparte base. Interrumpir un
     * hilo parado dentro de una llamada HTTP no lo corta: hay que esperarlo y decirlo si no
     * termina, en vez de seguir como si nada.
     */
    private void shutdown(ExecutorService executor) throws InterruptedException {
        executor.shutdownNow();
        if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
            // Se AVISA, no se afirma: este metodo corre en el bloque final, asi que una
            // asercion aca reemplazaria la falla verdadera del caso y se perderia el
            // diagnostico de una carrera, que es lo mas caro de reproducir.
            System.err.println("AVISO: quedo un hilo vivo del caso de carrera de " 
                + "WorkerCreateResourceTest; puede pisar a la clase siguiente");
        }
    }

}
