package com.scaramutti.tms.operations;

import com.scaramutti.tms.support.HermeticTestData;
import com.scaramutti.tms.support.OperationsTestData;
import com.scaramutti.tms.support.TestAuth;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Integration tests de GET /services. Hermético: cada test filtra por SU cliente sintético
 * ({@code ZTEST_}), así que las aserciones no dependen de cuántos servicios reales haya en la
 * base compartida; lo único que se afirma sin filtrar es la FORMA de la página.
 */
@QuarkusTest
class ServicesListResourceTest {

    @Inject HermeticTestData fixtures;
    @Inject OperationsTestData operationsFixtures;
    @Inject EntityManager entityManager;

    private int clientId;
    private int otherClientId;
    private int cargoTypeId;
    private int currencyId;
    private String adminToken;

    @BeforeEach
    void setUp() {
        clientId = fixtures.seedClient();
        otherClientId = fixtures.seedClient();
        cargoTypeId = fixtures.seedCargoType();
        currencyId = fixtures.currencyId("PEN");
        adminToken = TestAuth.adminToken();
    }

    @AfterEach
    void cleanup() {
        operationsFixtures.deleteTestServices();
        fixtures.cleanup();
    }

    // ---------- Forma de la página --------------------------------------------

    @Test
    void list_returnsPageShapeWithDefaults() {
        given()
            .header("Authorization", "Bearer " + adminToken)
        .when()
            .get("/services")
        .then()
            .statusCode(200)
            // El cuerpo depende del rol: ningún cache intermedio debe guardarlo.
            .header("Cache-Control", equalTo("no-store"))
            .body("page", equalTo(0))
            .body("size", equalTo(20))
            .body("content", notNullValue());
    }

    // ---------- Contenido y orden ----------------------------------------------

    /**
     * Orden fijo por fecha de creación descendente. Las fechas se fuerzan al REVÉS del orden de
     * inserción a propósito: creados por el endpoint, el id y la fecha avanzan juntos, así que un
     * orden por id daría el mismo resultado y el test no probaría lo que dice. Importa de verdad
     * para el cutover, que insertará viajes históricos con fechas retroactivas.
     */
    @Test
    void list_ordersByCreationDateDescending() {
        // Las fechas NO siguen el orden de inserción ni su inverso: así el resultado esperado no
        // coincide con ordenar por id en ninguna dirección, y el test prueba lo que su nombre dice.
        String insertedFirst = createService("Piura", "Lima");
        String insertedSecond = createService("Piura", "Trujillo");
        String insertedThird = createService("Piura", "Arequipa");
        forceCreatedAt(insertedFirst, "2026-02-02T10:00:00Z");
        forceCreatedAt(insertedSecond, "2026-03-03T10:00:00Z");
        forceCreatedAt(insertedThird, "2026-01-01T10:00:00Z");

        given()
            .header("Authorization", "Bearer " + adminToken)
        .when()
            .get("/services?clientId=" + clientId)
        .then()
            .statusCode(200)
            // Orden por FECHA descendente: marzo, febrero, enero.
            .body("content.code", contains(insertedSecond, insertedFirst, insertedThird))
            .body("totalElements", equalTo(3));
    }

    /** Dos viajes con la misma fecha de creación se ordenan por id descendente, sin empates. */
    @Test
    void list_withSameCreationInstant_breaksTheTieById() {
        String older = createService("Piura", "Lima");
        String newer = createService("Piura", "Trujillo");
        forceCreatedAt(older, "2026-04-04T10:00:00Z");
        forceCreatedAt(newer, "2026-04-04T10:00:00Z");

        given()
            .header("Authorization", "Bearer " + adminToken)
        .when()
            .get("/services?clientId=" + clientId)
        .then()
            .statusCode(200)
            .body("content.code", contains(newer, older));
    }

    @Test
    void list_returnsTheEmbeddedClientAndCurrency() {
        createService("Piura", "Lima");

        given()
            .header("Authorization", "Bearer " + adminToken)
        .when()
            .get("/services?clientId=" + clientId)
        .then()
            .statusCode(200)
            .body("content[0].client.id", equalTo(clientId))
            .body("content[0].client.name", notNullValue())
            .body("content[0].client.ruc", notNullValue())
            .body("content[0].currencyCode", equalTo("PEN"))
            .body("content[0].tripScope", equalTo("PROVINCIA"))
            .body("content[0].status", equalTo("PENDING_ASSIGNMENT"));
    }

    /**
     * La fila COMPLETA contra los valores con los que se creó el servicio. Las columnas de la
     * consulta nativa se leen por POSICIÓN, así que un SELECT y un constructor desalineados
     * devuelven la fila entera con los valores cruzados (origen por destino, nombre por RUC) sin
     * que nada explote. Por eso acá se afirma por VALOR y no con "no es null": una aserción
     * floja da por buena exactamente el error que este test existe para cazar.
     */
    @Test
    void list_putsEveryColumnInItsOwnField() {
        LocalDate tentativeDate = LocalDate.now().plusDays(5);
        String code = createService("Sullana Origen", "Chiclayo Destino", tentativeDate);
        forceCreatedAt(code, "2026-02-02T10:00:00Z");
        // El fixture deja estos dos en null, y dos nulls son iguales entre sí: sin valores
        // distintos, cruzar teléfono con contacto pasaría desapercibido.
        setClientContact(clientId, "987654321", "ZTEST_ Contacto Ana");

        given()
            .header("Authorization", "Bearer " + adminToken)
        .when()
            .get("/services?clientId=" + clientId)
        .then()
            .statusCode(200)
            .body("content[0].code", equalTo(code))
            .body("content[0].origin", equalTo("Sullana Origen"))
            .body("content[0].destination", equalTo("Chiclayo Destino"))
            .body("content[0].tentativeDate", equalTo(tentativeDate.toString()))
            .body("content[0].createdAt", startsWith("2026-02-02T10:00"))
            .body("content[0].client.id", equalTo(clientId))
            .body("content[0].client.name", equalTo(nameOf(clientId)))
            .body("content[0].client.ruc", equalTo(rucOf(clientId)))
            .body("content[0].client.phone", equalTo("987654321"))
            .body("content[0].client.contactName", equalTo("ZTEST_ Contacto Ana"));
    }

    /**
     * Una página muy lejana no puede reventar: el corrimiento se ensancha a long ANTES de
     * multiplicar, y sin eso el producto desborda y la consulta termina en 500.
     *
     * <p>El ensanchado está en DOS lugares (el atajo de "más allá del total" y el parámetro de
     * corrimiento) y cada uno tapa al otro: quitando uno solo, la respuesta no cambia. Este test
     * cae cuando se pierden los dos, que es la única forma en que el desborde llega al motor.
     */
    @Test
    void list_withVeryLargePage_returnsEmptyPageInsteadOfFailing() {
        createService("Piura", "Lima");

        given()
            .header("Authorization", "Bearer " + adminToken)
        .when()
            .get("/services?page=2147483647&size=100")
        .then()
            .statusCode(200)
            .body("content", hasSize(0));
    }

    // ---------- Filtros ---------------------------------------------------------

    @Test
    void list_filtersByClient() {
        createService("Piura", "Lima");
        createServiceForClient(otherClientId, "Piura", "Cusco");

        given()
            .header("Authorization", "Bearer " + adminToken)
        .when()
            .get("/services?clientId=" + otherClientId)
        .then()
            .statusCode(200)
            .body("totalElements", equalTo(1))
            .body("content[0].client.id", equalTo(otherClientId));
    }

    @Test
    void list_filtersByStatus() {
        String pending = createService("Piura", "Lima");
        String completed = createService("Piura", "Ica");
        forceStatus(completed, "COMPLETED");

        given()
            .header("Authorization", "Bearer " + adminToken)
        .when()
            .get("/services?clientId=" + clientId + "&status=COMPLETED")
        .then()
            .statusCode(200)
            .body("content.code", contains(completed));
    }

    /**
     * Rango ASIMÉTRICO a propósito: con un rango de un solo día el predicado es simétrico y
     * confundir el piso con el techo pasaría desapercibido. Cubre de paso que ambos extremos
     * están incluidos.
     */
    @Test
    void list_filtersByTentativeDateRangeInclusive() {
        String onLowerBound = createService("Piura", "Lima", LocalDate.of(2026, 5, 10));
        String inside = createService("Piura", "Ica", LocalDate.of(2026, 5, 11));
        String onUpperBound = createService("Piura", "Cusco", LocalDate.of(2026, 5, 12));
        createService("Piura", "Tacna", LocalDate.of(2026, 5, 9));
        createService("Piura", "Puno", LocalDate.of(2026, 5, 13));
        forceCreatedAt(onLowerBound, "2026-01-03T10:00:00Z");
        forceCreatedAt(inside, "2026-01-02T10:00:00Z");
        forceCreatedAt(onUpperBound, "2026-01-01T10:00:00Z");

        given()
            .header("Authorization", "Bearer " + adminToken)
        .when()
            .get("/services?clientId=" + clientId + "&dateFrom=2026-05-10&dateTo=2026-05-12")
        .then()
            .statusCode(200)
            .body("content.code", contains(onLowerBound, inside, onUpperBound));
    }

    @Test
    void list_withOnlyDateFrom_filtersFromThatDayOn() {
        createService("Piura", "Lima", LocalDate.of(2026, 6, 1));
        String after = createService("Piura", "Ica", LocalDate.of(2026, 6, 2));

        given()
            .header("Authorization", "Bearer " + adminToken)
        .when()
            .get("/services?clientId=" + clientId + "&dateFrom=2026-06-02")
        .then()
            .statusCode(200)
            .body("content.code", contains(after));
    }

    // ---------- Eliminados ------------------------------------------------------

    /** Un servicio eliminado no ensucia el listado: nunca debió existir. */
    @Test
    void list_excludesDeletedByDefault() {
        String alive = createService("Piura", "Lima");
        String deleted = createService("Piura", "Ica");
        forceStatus(deleted, "DELETED");

        given()
            .header("Authorization", "Bearer " + adminToken)
        .when()
            .get("/services?clientId=" + clientId)
        .then()
            .statusCode(200)
            .body("content.code", contains(alive))
            .body("totalElements", equalTo(1));
    }

    /** Pero se pueden consultar pidiéndolos por su nombre. */
    @Test
    void list_withDeletedStatus_returnsThem() {
        createService("Piura", "Lima");
        String deleted = createService("Piura", "Ica");
        forceStatus(deleted, "DELETED");

        given()
            .header("Authorization", "Bearer " + adminToken)
        .when()
            .get("/services?clientId=" + clientId + "&status=DELETED")
        .then()
            .statusCode(200)
            .body("content.code", contains(deleted))
            .body("totalElements", equalTo(1));
    }

    // ---------- Búsqueda multi-palabra -----------------------------------------

    /** Cada palabra puede matchear en un campo distinto: acá una en el destino y otra en el origen. */
    @Test
    void list_withMultiWordQuery_matchesAcrossFields() {
        String target = createService("Piura", "ZQTEST Callao");
        String otherRoute = createService("Trujillo", "ZQTEST Lima");

        given()
            .header("Authorization", "Bearer " + adminToken)
            // El término va como parámetro, no incrustado en la ruta: así el espacio se codifica
            // una sola vez y llega como separador de palabras, no como literal.
            .queryParam("q", "ZQTEST Piura")
        .when()
            .get("/services")
        .then()
            .statusCode(200)
            .body("content.code", hasItem(target))
            .body("content.code", not(hasItem(otherRoute)));
    }

    /**
     * El caso que vende el contrato: una palabra pega en el NOMBRE DEL CLIENTE y la otra en el
     * destino, o sea el término cruza dos tablas. Sin esto, borrar al cliente de las columnas
     * buscables no rompería nada.
     */
    @Test
    void list_withMultiWordQuery_matchesClientNameAndDestination() {
        renameClient(clientId, HermeticTestData.PREFIX + "ZQCLIENTE Marina");
        String target = createService("Piura", "ZQDESTINO Callao");
        String otherClientService = createServiceForClient(otherClientId, "Piura", "ZQDESTINO Callao");

        given()
            .header("Authorization", "Bearer " + adminToken)
            .queryParam("q", "ZQCLIENTE ZQDESTINO")
        .when()
            .get("/services")
        .then()
            .statusCode(200)
            .body("content.code", hasItem(target))
            .body("content.code", not(hasItem(otherClientService)));
    }

    /** El RUC del cliente también es buscable. */
    @Test
    void list_withQueryOnClientRuc_findsTheService() {
        String target = createService("Piura", "Lima");
        String ruc = rucOf(clientId);

        given()
            .header("Authorization", "Bearer " + adminToken)
            .queryParam("q", ruc)
        .when()
            .get("/services")
        .then()
            .statusCode(200)
            .body("content.code", hasItem(target));
    }

    @Test
    void list_withQueryOnCode_findsTheService() {
        String code = createService("Piura", "Lima");

        given()
            .header("Authorization", "Bearer " + adminToken)
        .when()
            .get("/services?q=" + code)
        .then()
            .statusCode(200)
            .body("content.code", hasItem(code));
    }

    // ---------- Precios por rol (RN-OP8) ---------------------------------------

    /**
     * La garantía es del servidor: el despacho no recibe el precio ni su moneda. Se afirma la
     * AUSENCIA de las claves, no que vengan en null, porque el contrato promete eso y porque un
     * null igual expondría la existencia del campo.
     */
    @Test
    void list_asDispatcher_omitsPriceAndCurrency() {
        createService("Piura", "Lima");

        given()
            .header("Authorization", "Bearer " + TestAuth.fabricateAccessToken("zdispatcher", "dispatcher"))
        .when()
            .get("/services?clientId=" + clientId)
        .then()
            .statusCode(200)
            .body("content", hasSize(1))
            .body("content[0].code", notNullValue())
            .body("content[0]", not(hasKey("price")))
            .body("content[0]", not(hasKey("currencyCode")));
    }

    /**
     * Los CUATRO roles que sí ven precios, uno por uno: si alguien se equivoca al escribir uno
     * en la lista positiva, la gerencia deja de ver precios y nadie se entera.
     */
    @ParameterizedTest
    @ValueSource(strings = {"admin", "sales", "general_manager", "operations_manager"})
    void list_asRoleWithPrices_includesPriceAndCurrency(String role) {
        createService("Piura", "Lima");

        JsonPath body = given()
            .header("Authorization", "Bearer " + TestAuth.fabricateAccessToken("z" + role, role))
        .when()
            .get("/services?clientId=" + clientId)
        .then()
            .statusCode(200)
            .body("content[0]", hasKey("price"))
            .body("content[0].currencyCode", equalTo("PEN"))
            .extract().jsonPath();

        // Por valor: el JSON trae la escala de la columna (3200.00), y atarse al texto haría
        // frágil la aserción.
        assertEquals(3200f, body.getFloat("content[0].price"));
    }

    /**
     * La regla es un VETO, no una suma de permisos: un token que fuera despacho Y ventas a la vez
     * sigue sin ver precios. Hoy ningún usuario tiene dos roles, pero la regla del negocio dice
     * "el despacho nunca ve precios" y el código tiene que decir lo mismo, no "tiene algún rol
     * que sí los ve".
     */
    @Test
    void list_asDispatcherWithAnotherPricedRole_stillOmitsPrices() {
        createService("Piura", "Lima");

        given()
            .header("Authorization", "Bearer "
                + TestAuth.fabricateAccessTokenWithRoles("zmixto", Set.of("dispatcher", "sales")))
        .when()
            .get("/services?clientId=" + clientId)
        .then()
            .statusCode(200)
            .body("content", hasSize(1))
            .body("content[0]", not(hasKey("price")))
            .body("content[0]", not(hasKey("currencyCode")));
    }

    /** El despacho tampoco ve precios cuando pagina o filtra: la regla no depende de la consulta. */
    @Test
    void list_asDispatcherWithFilters_stillOmitsPrices() {
        createService("Piura", "Lima");
        createService("Piura", "Ica");

        given()
            .header("Authorization", "Bearer " + TestAuth.fabricateAccessToken("zdispatcher", "dispatcher"))
        .when()
            .get("/services?clientId=" + clientId + "&status=PENDING_ASSIGNMENT&size=1")
        .then()
            .statusCode(200)
            .body("content", hasSize(1))
            .body("content", everyItem(not(hasKey("price"))))
            .body("content", everyItem(not(hasKey("currencyCode"))));
    }

    // ---------- Paginación ------------------------------------------------------

    @Test
    void list_paginatesWithoutRepeatingOrSkipping() {
        String first = createService("Piura", "Lima");
        String second = createService("Piura", "Ica");
        String third = createService("Piura", "Cusco");

        given()
            .header("Authorization", "Bearer " + adminToken)
        .when()
            .get("/services?clientId=" + clientId + "&size=2&page=0")
        .then()
            .statusCode(200)
            .body("content.code", contains(third, second))
            .body("totalPages", equalTo(2))
            .body("first", equalTo(true))
            .body("last", equalTo(false));

        given()
            .header("Authorization", "Bearer " + adminToken)
        .when()
            .get("/services?clientId=" + clientId + "&size=2&page=1")
        .then()
            .statusCode(200)
            .body("content.code", contains(first))
            .body("last", equalTo(true));
    }

    /**
     * Una página que empieza más allá del total devuelve los MISMOS metadatos que si se hubiera
     * consultado (el service se ahorra la consulta, pero eso es una optimización invisible desde
     * afuera y este test no la verifica: fija que la optimización no cambie la respuesta).
     */
    @Test
    void list_beyondLastPage_returnsEmptyPageWithConsistentMetadata() {
        createService("Piura", "Lima");

        given()
            .header("Authorization", "Bearer " + adminToken)
        .when()
            .get("/services?clientId=" + clientId + "&size=2&page=7")
        .then()
            .statusCode(200)
            .body("content", hasSize(0))
            .body("totalElements", equalTo(1))
            .body("totalPages", equalTo(1))
            .body("numberOfElements", equalTo(0))
            .body("first", equalTo(false))
            .body("last", equalTo(true))
            .body("empty", equalTo(true));
    }

    // ---------- Validación de los parámetros ------------------------------------

    /**
     * El estado inválido responde 400 con el detalle, NO el 404 vacío que daría el framework si
     * el parámetro estuviera declarado con el tipo del enum.
     */
    @Test
    void list_withUnknownStatus_returns400_COM001() {
        given()
            .header("Authorization", "Bearer " + adminToken)
        .when()
            .get("/services?status=NAVEGANDO")
        .then()
            .statusCode(400)
            .contentType("application/problem+json")
            .body("code", equalTo("COM-001"))
            .body("detail", notNullValue());
    }

    /**
     * Un NUL incrustado en el término llegaba hasta PostgreSQL, que no admite ese byte en un
     * texto, y reventaba la consulta con un 500. Se mide por HTTP a propósito: el punto es el
     * código de respuesta de punta a punta, no que el mapper tire la excepción.
     */
    @Test
    void list_withControlCharacterInQuery_returns400() {
        given()
            .header("Authorization", "Bearer " + adminToken)
            .queryParam("q", "abc\u0000def")
        .when()
            .get("/services")
        .then()
            .statusCode(400)
            .body("code", equalTo("COM-001"));
    }

    /**
     * El formato ISO admite años de nueve cifras y la columna {@code date} no: sin la ventana de
     * fechas, esto era un 500 del motor en la consulta del total.
     */
    @Test
    void list_withDateBeyondDatabaseRange_returns400() {
        given()
            .header("Authorization", "Bearer " + adminToken)
            .queryParam("dateFrom", "+999999999-01-01")
        .when()
            .get("/services")
        .then()
            .statusCode(400)
            .body("code", equalTo("COM-001"));
    }

    /**
     * Un GET no tiene cuerpo, así que un {@code Content-Type} en la petición no puede hacerlo
     * fallar: con la declaración de tipo de entrada puesta en la clase, este listado respondía
     * 415, un código que su contrato no declara.
     */
    @Test
    void list_withContentTypeHeader_stillReturns200() {
        given()
            .header("Authorization", "Bearer " + adminToken)
            .contentType("text/plain")
        .when()
            .get("/services")
        .then()
            .statusCode(200);
    }

    @Test
    void list_withShortQuery_returns400() {
        given()
            .header("Authorization", "Bearer " + adminToken)
        .when()
            .get("/services?q=ab")
        .then()
            .statusCode(400)
            .body("code", equalTo("COM-001"));
    }

    /** El estado es un dominio cerrado en mayúsculas: otra grafía no es "casi válida", es 400. */
    @Test
    void list_withLowercaseStatus_returns400() {
        given()
            .header("Authorization", "Bearer " + adminToken)
        .when()
            .get("/services?status=completed")
        .then()
            .statusCode(400)
            .body("code", equalTo("COM-001"));
    }

    /**
     * Un parámetro malformado responde 400 con su detalle, NO el 404 vacío que devuelve el
     * framework cuando el parámetro está declarado con su tipo.
     */
    @ParameterizedTest
    @ValueSource(strings = {"clientId=abc", "dateFrom=ayer", "dateTo=2026-13-45", "page=abc", "size=abc"})
    void list_withMalformedParameter_returns400_COM001(String parameter) {
        given()
            .header("Authorization", "Bearer " + adminToken)
        .when()
            .get("/services?" + parameter)
        .then()
            .statusCode(400)
            .contentType("application/problem+json")
            .body("code", equalTo("COM-001"))
            .body("detail", notNullValue());
    }

    /**
     * Un parámetro VACÍO es un filtro sin elegir (así lo serializa un formulario), no un error:
     * se ignora y los de paginación toman su valor por defecto.
     */
    @Test
    void list_withEmptyParameters_ignoresThem() {
        given()
            .header("Authorization", "Bearer " + adminToken)
        .when()
            .get("/services?clientId=&status=&dateFrom=&dateTo=&page=&size=&q=")
        .then()
            .statusCode(200)
            .body("page", equalTo(0))
            .body("size", equalTo(20));
    }

    /** Una palabra corta no acota nada: se descarta, y el término sigue valiendo por las otras. */
    @Test
    void list_withShortWordInsideQuery_ignoresThatWord() {
        String target = createService("ZQORIGEN Piura", "Lima");

        given()
            .header("Authorization", "Bearer " + adminToken)
            .queryParam("q", "ZQORIGEN de")
        .when()
            .get("/services")
        .then()
            .statusCode(200)
            .body("content.code", hasItem(target));
    }

    /** Pero si NINGUNA palabra sirve, es un error: devolver el listado entero sería mentir. */
    @Test
    void list_withOnlyShortWords_returns400() {
        given()
            .header("Authorization", "Bearer " + adminToken)
            .queryParam("q", "a de la")
        .when()
            .get("/services")
        .then()
            .statusCode(400)
            .body("code", equalTo("COM-001"));
    }

    /** Demasiadas palabras multiplican el costo de la consulta: se rechaza en vez de servirla. */
    @Test
    void list_withTooManyWords_returns400() {
        given()
            .header("Authorization", "Bearer " + adminToken)
            .queryParam("q", "uno dos tres cuatro cinco seis siete ocho nueve")
        .when()
            .get("/services")
        .then()
            .statusCode(400)
            .body("code", equalTo("COM-001"));
    }

    @Test
    void list_withTooLongQuery_returns400() {
        given()
            .header("Authorization", "Bearer " + adminToken)
            .queryParam("q", "Piura".repeat(50))
        .when()
            .get("/services")
        .then()
            .statusCode(400)
            .body("code", equalTo("COM-001"));
    }

    /** El texto del usuario vuelve recortado en el mensaje: no se refleja entero. */
    @Test
    void list_withVeryLongInvalidStatus_truncatesItInTheMessage() {
        given()
            .header("Authorization", "Bearer " + adminToken)
            .queryParam("status", "X".repeat(120))
        .when()
            .get("/services")
        .then()
            .statusCode(400)
            .body("detail", not(containsString("X".repeat(40))))
            .body("detail", containsString("…"));
    }

    @Test
    void list_withSizeOverMaximum_returns400() {
        given()
            .header("Authorization", "Bearer " + adminToken)
        .when()
            .get("/services?size=101")
        .then()
            .statusCode(400)
            .body("code", equalTo("COM-001"));
    }

    @Test
    void list_withNegativePage_returns400() {
        given()
            .header("Authorization", "Bearer " + adminToken)
        .when()
            .get("/services?page=-1")
        .then()
            .statusCode(400)
            .body("code", equalTo("COM-001"));
    }

    // ---------- Autorización ----------------------------------------------------

    @Test
    void list_asWarehouseKeeper_returns403() {
        given()
            .header("Authorization", "Bearer " + TestAuth.fabricateAccessToken("zkeeper", "warehouse_keeper"))
        .when()
            .get("/services")
        .then()
            .statusCode(403)
            .body("code", equalTo("COM-003"));
    }

    @Test
    void list_withoutToken_returns401() {
        given()
        .when()
            .get("/services")
        .then()
            .statusCode(401);
    }

    // ---------- Helpers ---------------------------------------------------------

    /** Crea un servicio por el endpoint real y devuelve su código. */
    private String createService(String origin, String destination) {
        return createService(origin, destination, LocalDate.now().plusDays(3));
    }

    private String createService(String origin, String destination, LocalDate tentativeDate) {
        return createServiceForClient(clientId, origin, destination, tentativeDate);
    }

    private String createServiceForClient(int client, String origin, String destination) {
        return createServiceForClient(client, origin, destination, LocalDate.now().plusDays(3));
    }

    private String createServiceForClient(int client, String origin, String destination,
                                          LocalDate tentativeDate) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("clientId", client);
        payload.put("tripScope", "PROVINCIA");
        payload.put("tentativeDate", tentativeDate.toString());
        payload.put("origin", origin);
        payload.put("destination", destination);
        payload.put("cargoTypeId", cargoTypeId);
        payload.put("weightKg", 12000);
        payload.put("price", 3200);
        payload.put("currencyId", currencyId);

        return given()
            .header("Authorization", "Bearer " + adminToken)
            .contentType(ContentType.JSON)
            .body(payload)
        .when()
            .post("/services")
        .then()
            .statusCode(201)
            .extract().jsonPath().getString("code");
    }

    /** Fuerza la fecha de creación por SQL: el alta siempre pone "ahora". */
    private void forceCreatedAt(String code, String instant) {
        QuarkusTransaction.requiringNew().run(() -> entityManager.createNativeQuery(
                "UPDATE operaciones.services SET created_at = CAST(?1 AS timestamptz) WHERE code = ?2")
            .setParameter(1, instant).setParameter(2, code).executeUpdate());
    }

    /** Renombra al cliente sintético conservando el prefijo, que es por donde se limpia. */
    private void renameClient(int client, String name) {
        QuarkusTransaction.requiringNew().run(() -> entityManager.createNativeQuery(
                "UPDATE public.clients SET name = ?1 WHERE id = ?2")
            .setParameter(1, name).setParameter(2, client).executeUpdate());
    }

    private String nameOf(int client) {
        return (String) entityManager.createNativeQuery(
                "SELECT name FROM public.clients WHERE id = ?1")
            .setParameter(1, client).getSingleResult();
    }

    private void setClientContact(int client, String phone, String contactName) {
        QuarkusTransaction.requiringNew().run(() -> entityManager.createNativeQuery(
                "UPDATE public.clients SET phone = ?1, contact_name = ?2 WHERE id = ?3")
            .setParameter(1, phone).setParameter(2, contactName).setParameter(3, client)
            .executeUpdate());
    }

    private String rucOf(int client) {
        return (String) entityManager.createNativeQuery(
                "SELECT ruc FROM public.clients WHERE id = ?1")
            .setParameter(1, client).getSingleResult();
    }

    /**
     * Fuerza el estado por SQL: las transiciones tienen su propio endpoint, que todavía no
     * existe, y este test necesita servicios en estados que hoy nadie puede producir.
     */
    private void forceStatus(String code, String status) {
        QuarkusTransaction.requiringNew().run(() -> entityManager.createNativeQuery(
                "UPDATE operaciones.services SET status = ?1 WHERE code = ?2")
            .setParameter(1, status).setParameter(2, code).executeUpdate());
    }
}
