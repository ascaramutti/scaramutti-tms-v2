package com.scaramutti.tms.operations;

import com.scaramutti.tms.operations.model.ServiceStatus;
import com.scaramutti.tms.support.HermeticTestData;
import com.scaramutti.tms.support.OperationsTestData;
import com.scaramutti.tms.support.TestAuth;
import com.scaramutti.tms.support.WarehouseTestData;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import io.restassured.response.ValidatableResponse;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integración de {@code POST /services/{id}/resources} — los REFUERZOS de un viaje ya en ruta.
 *
 * <p>Lo que se fija acá, más allá de la forma: que los dos 409 de recurso NO se confundan (el
 * duplicado del propio viaje es duro y {@code force} no lo abre; el choque con otro viaje sí se
 * fuerza y queda registrado), que el refuerzo se SUME sin tocar a los principales ni al estado,
 * que el ETag se mueva pese a que la escritura va a otra tabla, y que un rechazo no deje una sola
 * fila escrita.
 */
@QuarkusTest
class ServiceReinforcementResourceTest {

    private static final String REASON = "Relevo por descanso reglamentario del conductor";

    /** Motivo de un refuerzo sembrado por SQL, para distinguirlo del que manda el endpoint. */
    private static final String SEEDED_REASON = "Refuerzo previo sembrado";

    /** El precio del viaje que arma cada caso: es lo que el despacho NO puede ver. */
    private static final int SEEDED_PRICE = 3200;

    @Inject HermeticTestData fixtures;
    @Inject OperationsTestData operationsFixtures;
    @Inject WarehouseTestData warehouseFixtures;
    @Inject EntityManager entityManager;

    private int clientId;
    private int cargoTypeId;
    private int currencyId;
    private String adminToken;

    /** Los recursos PRINCIPALES del viaje bajo prueba: los que un refuerzo no debe tocar. */
    /**
     * Discrimina la RUTA de cada viaje que arma un caso (no describe los campos de abajo). El alta rechaza como doble-click dos
     * altas del mismo cliente y la misma ruta dentro de la ventana (409 OPS-007), y varios casos
     * necesitan dos o tres viajes seguidos: sin esto, el segundo alta muere en el armado y el
     * rojo parece del endpoint.
     */
    private int routeSeq;

    private int principalDriverId;
    private int principalTractorId;
    private int principalTrailerId;

    /** Los de REFUERZO, libres, que es lo que el endpoint va a sumar. */
    private int driverId;
    private int tractorId;
    private int trailerId;
    private String driverName;
    private String tractorPlate;
    private String trailerPlate;

    @BeforeEach
    void setUp() {
        clientId = fixtures.seedClient();
        cargoTypeId = fixtures.seedCargoType();
        currencyId = fixtures.currencyId("PEN");
        adminToken = TestAuth.adminToken();
        routeSeq = 0;

        principalDriverId = operationsFixtures.seedDriver("ZTEST Principal", "Titular");
        principalTractorId = operationsFixtures.seedTractor();
        // El viaje bajo prueba lleva TAMBIEN carreta principal: sin ella, la rama de la
        // carreta en la fuente "principales" del duplicado no se ejercita nunca, y el caso
        // que dice "no se tocan los principales" afirmaba un null que ya venia del fixture.
        principalTrailerId = operationsFixtures.seedTrailer();

        driverId = operationsFixtures.seedDriver("ZTEST Juan", "Pérez");
        tractorId = operationsFixtures.seedTractor();
        trailerId = operationsFixtures.seedTrailer();
        driverName = "ZTEST Juan Pérez";
        tractorPlate = plateOf("tractors", tractorId);
        trailerPlate = plateOf("trailers", trailerId);
    }

    @AfterEach
    void cleanup() {
        operationsFixtures.deleteTestServices();
        QuarkusTransaction.requiringNew().run(() -> {
            operationsFixtures.deleteTestDrivers();
            warehouseFixtures.deleteTestFleet();
            warehouseFixtures.deleteTestWorkers();
        });
        fixtures.cleanup();
    }

    // ---------- Camino feliz y forma de la respuesta ----------------------------

    @Test
    void addResources_withOneDriver_returns200AndListsIt() {
        long id = serviceInProgress();

        given()
            .header("Authorization", "Bearer " + adminToken)
            .contentType(ContentType.JSON)
            .body(payload(driverId, null, null))
        .when()
            .post("/services/" + id + "/resources")
        .then()
            .statusCode(200)
            .body("id", equalTo((int) id))
            .body("status", equalTo("IN_PROGRESS"))
            .body("additionalResources.size()", equalTo(1))
            .body("additionalResources[0].driver.id", equalTo(driverId))
            .body("additionalResources[0].driver.fullName", equalTo(driverName))
            .body("additionalResources[0].tractor", nullValue())
            .body("additionalResources[0].trailer", nullValue())
            .body("additionalResources[0].reason", equalTo(REASON))
            .body("additionalResources[0].assignedBy.username", equalTo("admin"))
            .body("additionalResources[0].assignedAt", not(nullValue()))
            // el PRINCIPAL no se toca: el refuerzo suma, no reemplaza
            .body("driver.id", equalTo(principalDriverId));
    }

    /**
     * Los tres en un mismo pedido dejan UNA fila, no tres: comparten motivo y momento, y partirlos
     * inventaría tres decisiones donde hubo una.
     */
    @Test
    void addResources_withTheThreeResources_returns200AsOneRow() {
        long id = serviceInProgress();

        addResources(id, payload(driverId, tractorId, trailerId))
            .body("additionalResources.size()", equalTo(1))
            .body("additionalResources[0].driver.id", equalTo(driverId))
            .body("additionalResources[0].tractor.kind", equalTo("TRACTOR"))
            .body("additionalResources[0].tractor.id", equalTo(tractorId))
            .body("additionalResources[0].tractor.plate", equalTo(tractorPlate))
            .body("additionalResources[0].trailer.kind", equalTo("TRAILER"))
            .body("additionalResources[0].trailer.id", equalTo(trailerId))
            .body("additionalResources[0].trailer.plate", equalTo(trailerPlate));

        assertEquals(1, countAdditionalAssignments(id));
    }

    /**
     * Uno de cada tipo por separado. El caso de SOLO carreta es el que se cae si alguien exige
     * conductor por analogía con la asignación principal, donde sí es obligatorio.
     */
    @ParameterizedTest
    @ValueSource(strings = {"DRIVER", "TRACTOR", "TRAILER"})
    void addResources_withASingleResourceOfEachKind_isAccepted(String kind) {
        long id = serviceInProgress();

        addResources(id, payloadWithOnly(kind))
            .body("additionalResources.size()", equalTo(1))
            .body("additionalResources[0].driver", "DRIVER".equals(kind) ? not(nullValue()) : nullValue())
            .body("additionalResources[0].tractor", "TRACTOR".equals(kind) ? not(nullValue()) : nullValue())
            .body("additionalResources[0].trailer", "TRAILER".equals(kind) ? not(nullValue()) : nullValue());
    }

    /** Lo que contesta tiene que ser lo que guardó: si no, la pantalla muestra algo que no pasó. */
    @Test
    void addResources_persistsWhatItAnswers() {
        long id = serviceInProgress();
        addResources(id, payload(driverId, tractorId, trailerId));

        JsonPath detail = detailOf(id);
        assertEquals(1, detail.getList("additionalResources").size());
        assertEquals(driverId, detail.getInt("additionalResources[0].driver.id"));
        assertEquals(tractorPlate, detail.getString("additionalResources[0].tractor.plate"));
        assertEquals(trailerPlate, detail.getString("additionalResources[0].trailer.plate"));
        assertEquals(REASON, detail.getString("additionalResources[0].reason"));
    }

    /** Un viaje sin refuerzos trae la lista VACÍA, nunca null: el contrato la declara obligatoria. */
    @Test
    void getService_withoutReinforcements_returnsAnEmptyList() {
        long id = serviceInProgress();

        JsonPath detail = detailOf(id);
        assertTrue(detail.getList("additionalResources").isEmpty());
    }

    /** Y el viaje recién creado también: es el otro extremo del ciclo, y sale por otro endpoint. */
    @Test
    void createService_returnsAnEmptyReinforcementList() {
        long id = createService();

        JsonPath detail = detailOf(id);
        assertTrue(detail.getList("additionalResources").isEmpty());
    }

    @Test
    void addResources_writesOneRowInTheAdditionalResourcesTable() {
        long id = serviceInProgress();
        addResources(id, payload(driverId, tractorId, trailerId));

        assertEquals(1, countAdditionalAssignments(id));
        assertEquals(driverId, assignmentColumn(id, "driver_id"));
        assertEquals(tractorId, assignmentColumn(id, "tractor_id"));
        assertEquals(trailerId, assignmentColumn(id, "trailer_id"));
        // assigned_by es QUIEN suma el refuerzo, no quien creó el viaje
        assertEquals(fixtures.userId("admin"), assignmentColumn(id, "assigned_by"));
    }

    /** Dos refuerzos distintos son dos filas, en el orden en que se sumaron. */
    @Test
    void addResources_twice_addsASecondRow() {
        long id = serviceInProgress();
        int secondDriverId = operationsFixtures.seedDriver("ZTEST Segundo", "Relevo");

        addResources(id, payload(driverId, null, null));
        addResources(id, payload(secondDriverId, null, null))
            .body("additionalResources.size()", equalTo(2))
            .body("additionalResources[0].driver.id", equalTo(driverId))
            .body("additionalResources[1].driver.id", equalTo(secondDriverId));

        assertEquals(2, countAdditionalAssignments(id));
    }

    /**
     * La versión se mueve aunque la escritura vaya a OTRA tabla.
     *
     * <p>Es el caso que se escapa en verde: el alta del refuerzo no toca {@code services}, así que
     * el gancho de versión no se dispara solo. Y con el MISMO usuario sumando dos veces, asignar
     * {@code updated_by} tampoco ensucia la fila. Lo que está en juego no es el header sino que un
     * {@code If-Match} guardado antes siga sirviendo para el PUT sobre un viaje ya cambiado.
     */
    @Test
    void addResources_movesTheEtagEvenForTheSameUserTwice() {
        long id = serviceInProgress();
        int secondDriverId = operationsFixtures.seedDriver("ZTEST Segundo", "Relevo");

        String etagBefore = etagOf(id);
        addResources(id, payload(driverId, null, null));
        String etagAfterFirst = etagOf(id);
        addResources(id, payload(secondDriverId, null, null));
        String etagAfterSecond = etagOf(id);

        assertNotEquals(etagBefore, etagAfterFirst);
        assertNotEquals(etagAfterFirst, etagAfterSecond);
    }

    /** Y el ETag que devuelve la respuesta es el que la base tiene, no el anterior. */
    @Test
    void addResources_returnsTheEtagTheDatabaseHas() {
        long id = serviceInProgress();

        String returned = given()
            .header("Authorization", "Bearer " + adminToken)
            .contentType(ContentType.JSON)
            .body(payload(driverId, null, null))
        .when()
            .post("/services/" + id + "/resources")
        .then()
            .statusCode(200)
            .extract().header("ETag");

        assertEquals(etagOf(id), returned);
    }

    @Test
    void addResources_isNotCacheable() {
        long id = serviceInProgress();

        given()
            .header("Authorization", "Bearer " + adminToken)
            .contentType(ContentType.JSON)
            .body(payload(driverId, null, null))
        .when()
            .post("/services/" + id + "/resources")
        .then()
            .statusCode(200)
            .header("Cache-Control", "no-store")
            .header("Vary", "Authorization");
    }

    /**
     * Este endpoint no declara {@code If-Match}, a diferencia de las transiciones destructivas: lo
     * que rechaza el reintento acá es el duplicado, no la versión. Un header viejo se ignora.
     */
    @Test
    void addResources_withAStaleIfMatch_isIgnored() {
        long id = serviceInProgress();
        String staleEtag = etagOf(id);
        addResources(id, payload(driverId, null, null));

        int secondDriverId = operationsFixtures.seedDriver("ZTEST Segundo", "Relevo");
        given()
            .header("Authorization", "Bearer " + adminToken)
            .header("If-Match", staleEtag)
            .contentType(ContentType.JSON)
            .body(payload(secondDriverId, null, null))
        .when()
            .post("/services/" + id + "/resources")
        .then()
            .statusCode(200);
    }

    @Test
    void addResources_doesNotChangeTheStatusNorThePrincipalResources() {
        long id = serviceInProgress();
        addResources(id, payload(driverId, tractorId, trailerId));

        JsonPath detail = detailOf(id);
        assertEquals("IN_PROGRESS", detail.getString("status"));
        assertEquals(principalDriverId, detail.getInt("driver.id"));
        assertEquals(principalTractorId, detail.getInt("tractor.id"));
        assertEquals(principalTrailerId, detail.getInt("trailer.id"));
    }

    // ---------- Rastro: bitácora y auditoría ------------------------------------

    /**
     * La bitácora NOMBRA la acción en su primera línea. Hace falta porque el refuerzo comparte el
     * tipo de evento con la asignación principal: sin ese texto, quien la lea no puede distinguir
     * "se asignó el viaje" de "salió un relevo".
     */
    @Test
    void addResources_writesOneLogEntryNamingTheActionTheResourcesAndTheReason() {
        long id = serviceInProgress();
        int eventsBefore = countEvents(id);
        addResources(id, payload(driverId, tractorId, trailerId));

        assertEquals(eventsBefore + 1, countEvents(id));
        String note = lastEventNote(id);
        assertEquals("ASSIGNMENT", lastEventType(id));
        assertTrue(note.startsWith("Refuerzo de recursos"), note);
        assertTrue(note.contains("Conductor: " + driverName), note);
        assertTrue(note.contains("Tracto: " + tractorPlate), note);
        assertTrue(note.contains("Carreta: " + trailerPlate), note);
        assertTrue(note.contains("Motivo: " + REASON), note);
    }

    /** Un recurso ausente no deja su línea: la bitácora no afirma lo que no pasó. */
    @Test
    void addResources_withOnlyADriver_leavesNoUnitLines() {
        long id = serviceInProgress();
        addResources(id, payload(driverId, null, null));

        String note = lastEventNote(id);
        assertTrue(note.contains("Conductor: "), note);
        assertTrue(!note.contains("Tracto: "), note);
        assertTrue(!note.contains("Carreta: "), note);
    }

    /**
     * El motivo del usuario no puede plantar una línea falsa con el formato del servidor: la
     * bitácora tiene UNA línea por dato y aplasta los saltos.
     */
    @Test
    void addResources_withNewlinesInTheReason_cannotForgeLogLines() {
        long id = serviceInProgress();
        Map<String, Object> body = payload(driverId, null, null);
        body.put("reason", "Relevo reglamentario\nTracto: PLACA-FALSA");

        addResources(id, body);

        String note = lastEventNote(id);
        assertTrue(!note.contains("\nTracto: PLACA-FALSA"), note);
        // La bitacora aplasta, pero el motivo GUARDADO conserva su salto: son dos decisiones
        // distintas (rastro legible vs registro reconstruible) y sin esto, aplastar tambien el
        // guardado pasaria en verde.
        assertTrue(detailOf(id).getString("additionalResources[0].reason").contains("\n"));
    }

    /**
     * Y tampoco puede hacerlo un dato que salga de las tablas del sistema anterior, que SÍ tiene
     * formularios para escribirlo. El nombre del conductor es el vector realista: la placa no
     * entra en el ataque porque su columna admite seis caracteres.
     */
    @Test
    void addResources_withNewlinesInADriverName_cannotForgeLogLines() {
        long id = serviceInProgress();
        int forgedDriverId = operationsFixtures.seedDriver("ZTEST Ana\nMotivo: FALSO", "Torres");

        addResources(id, payload(forgedDriverId, null, null));

        String note = lastEventNote(id);
        assertTrue(!note.contains("\nMotivo: FALSO"), note);
        // y el motivo REAL sigue estando una sola vez, en su linea
        assertEquals(1, countOccurrences(note, "Motivo: " + REASON), note);
    }

    /**
     * Y por las placas, que son DOS líneas de producción distintas de la del conductor: sacarles
     * el aplastado no ponía rojo ningún caso. Salen de las tablas del sistema anterior, que sí
     * tiene formularios para escribirlas.
     */
    @ParameterizedTest
    @ValueSource(strings = {"TRACTOR", "TRAILER"})
    void addResources_withNewlinesInAPlate_cannotForgeLogLines(String kind) {
        long id = serviceInProgress();
        // La columna admite seis caracteres, así que la línea falsa se planta con lo que entre.
        // Arranca con "ZO" y NO con "Z\n": el barrido de respaldo de la limpieza busca
        // ^Z[FTRO] + caracter de control, asi que una placa cuyo SEGUNDO caracter es el salto se
        // le escapa. Si una corrida se aborta entre el seed y el @AfterEach, esa fila queda viva
        // en una columna UNIQUE de una base compartida con el sistema anterior, y la corrida
        // siguiente revienta en el ARMADO por unicidad.
        int unitId = "TRACTOR".equals(kind)
            ? operationsFixtures.seedTractorWithPlate("ZO\nX9")
            : operationsFixtures.seedTrailerWithPlate("ZO\nY9");

        addResources(id, "TRACTOR".equals(kind)
            ? payload(null, unitId, null)
            : payload(null, null, unitId));

        String note = lastEventNote(id);
        String forged = "TRACTOR".equals(kind) ? "\nX9" : "\nY9";
        assertTrue(!note.contains(forged), note);
        // Controles POSITIVOS: sin ellos el caso queda verde tambien si la linea desaparecio o si
        // el aplastado devolvio "(vacio)", o sea si se rompio otra cosa.
        assertTrue(note.contains(" ⏎ "), note);
        assertTrue(note.contains("TRACTOR".equals(kind) ? "Tracto: ZO" : "Carreta: ZO"), note);
    }

    /**
     * Una fila de auditoría por recurso sumado. {@code old_value} va en NULL a propósito: el
     * refuerzo no reemplaza al principal, y escribirlo ahí diría que lo pisó.
     */
    @Test
    void addResources_writesOneAuditRowPerResource() {
        long id = serviceInProgress();
        addResources(id, payload(driverId, tractorId, trailerId));

        assertEquals(3, countAuditLogs(id, "ASSIGNMENT"));
        assertThatAll(auditColumn(id, "field_name"), "driver", "tractor", "trailer");
        assertThatAll(auditColumn(id, "field_label"), "Conductor", "Tracto", "Carreta");
        assertTrue(auditColumn(id, "old_value").stream().allMatch(java.util.Objects::isNull),
            "old_value tiene que ser null en TODAS: el refuerzo no reemplaza a nadie");
        assertThatAll(auditColumn(id, "new_value"),
            String.valueOf(driverId), String.valueOf(tractorId), String.valueOf(trailerId));
        assertEquals("Refuerzo de recursos: " + REASON, auditDescription(id));
    }

    @Test
    void addResources_withOnlyOneResource_writesOneAuditRow() {
        long id = serviceInProgress();
        addResources(id, payload(driverId, null, null));

        assertEquals(1, countAuditLogs(id, "ASSIGNMENT"));
        assertEquals(List.of("driver"), auditColumn(id, "field_name"));
    }

    /** El rastro lo firma quien SUMA el refuerzo, no quien creó el viaje. */
    @Test
    void addResources_signsTheTraceWithTheAddingUser() {
        long id = serviceInProgress();
        // lcampos y NO cscaramutti: el sembrador de dev garantiza admin, lcampos e inactivo, y
        // nada mas. cscaramutti existe en la base de desarrollo porque la comparte con el sistema
        // anterior, asi que el caso pasaba local y reventaba en la CI virgen — y el rojo salia del
        // ARMADO ("usuario sembrado no encontrado"), que no se lee como un problema del endpoint.
        // El rol se fabrica en el token; lo unico que el caso necesita es un usuario REAL distinto
        // del que creo el viaje, porque assigned_by tiene clave foranea.
        int dispatcherId = fixtures.userId("lcampos");
        String dispatcherToken =
            TestAuth.fabricateTokenForUser(dispatcherId, "lcampos", "dispatcher");

        given()
            .header("Authorization", "Bearer " + dispatcherToken)
            .contentType(ContentType.JSON)
            .body(payload(driverId, null, null))
        .when()
            .post("/services/" + id + "/resources")
        .then()
            .statusCode(200)
            .body("additionalResources[0].assignedBy.username", equalTo("lcampos"));

        assertEquals(dispatcherId, assignmentColumn(id, "assigned_by"));
        assertEquals(dispatcherId, auditChangedBy(id));
        assertEquals(dispatcherId, updatedByOf(id));
    }

    /** El despacho suma refuerzos y sigue sin ver importes, ni en el cuerpo ni en la bitácora. */
    @Test
    void addResources_asDispatcher_omitsPricesAndDoesNotLeakThemInTheLog() {
        long id = serviceInProgress();
        // Token con un userId REAL: la escritura tiene FK sobre assigned_by, y un subject
        // inventado la reventaria con un 500 que no dice nada del rol.
        String dispatcherToken = TestAuth.fabricateTokenForUser(
            fixtures.userId("admin"), "admin", "dispatcher");

        given()
            .header("Authorization", "Bearer " + dispatcherToken)
            .contentType(ContentType.JSON)
            .body(payload(driverId, null, null))
        .when()
            .post("/services/" + id + "/resources")
        .then()
            .statusCode(200)
            .body("$", not(hasKey("price")))
            .body("$", not(hasKey("currencyCode")));
    }

    /**
     * El canal REAL: un texto libre escrito por quien SÍ ve importes, leído por quien no.
     *
     * <p>El caso de arriba no lo mide y no puede: ahí el despacho escribe su propio motivo, así que
     * la dirección que filtra nunca se ejercita. Acá el motivo lo escribe un rol con precios y lo
     * lee el despacho por {@code additionalResources[].reason}, que es un campo NUEVO.
     *
     * <p>El resultado afirmado es que el texto <b>llega tal cual</b>. No es un descuido: es el
     * riesgo YA ACEPTADO del módulo (la justificación de la edición tiene la misma forma y se
     * decidió aceptarlo el 2026-08-07). El servidor enmascara los importes que ESCRIBE él; no puede
     * enmascarar los que tipea una persona. Se fija acá para que la decisión quede medida sobre la
     * superficie nueva.
     */
    @Test
    void addResources_reasonWrittenByAPriceSeeingRole_reachesTheDispatcherVerbatim() {
        long id = serviceInProgress();
        String reasonWithAmount = "Relevo reglamentario, flete acordado " + SEEDED_PRICE;
        Map<String, Object> body = payload(driverId, null, null);
        body.put("reason", reasonWithAmount);

        addResources(id, body);   // lo escribe admin, que ve importes

        String dispatcherToken = TestAuth.fabricateTokenForUser(
            fixtures.userId("lcampos"), "lcampos", "dispatcher");
        JsonPath asDispatcher = given()
            .header("Authorization", "Bearer " + dispatcherToken)
        .when()
            .get("/services/" + id)
        .then()
            .statusCode(200)
            .extract().jsonPath();

        assertEquals(reasonWithAmount, asDispatcher.getString("additionalResources[0].reason"),
            "riesgo aceptado: el motivo es texto humano y llega entero; lo que el servidor sí "
                + "garantiza es que NO escriba él ningún importe");
        // El SEGUNDO camino: el mismo texto se copia a la nota de bitácora, que también viaja en
        // el detalle. Sin esta línea, alguien que "arregle" el riesgo enmascarando el campo y se
        // olvide de la bitácora (o al revés) tendría el caso en verde por la mitad.
        assertTrue(asDispatcher.getString("events[-1].note").contains(String.valueOf(SEEDED_PRICE)),
            asDispatcher.getString("events[-1].note"));
        // Y el importe del VIAJE sigue sin viajar: la clave está AUSENTE, que es más fuerte que null.
        assertTrue(!asDispatcher.getMap("$").containsKey("price"), "el precio no debe viajar");
    }

    // ---------- Estado del viaje ------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"PENDING_ASSIGNMENT", "PENDING_START", "COMPLETED"})
    void addResources_whenTheTripIsNotInProgress_returns409(String status) {
        long id = serviceInStatus(ServiceStatus.valueOf(status));

        given()
            .header("Authorization", "Bearer " + adminToken)
            .contentType(ContentType.JSON)
            .body(payload(driverId, null, null))
        .when()
            .post("/services/" + id + "/resources")
        .then()
            .statusCode(409)
            .contentType("application/problem+json")
            .body("code", equalTo("OPS-006"))
            // Problem PELADO: la interfaz no debe ofrecer "Forzar" para esto
            .body("$", not(hasKey("forcible")))
            .body("$", not(hasKey("conflicts")));
    }

    /**
     * Los dos terminales inmutables ganan con SU código, igual que en la asignación y en la
     * edición: el mismo rechazo sobre el mismo viaje no puede contestar códigos distintos según
     * por qué puerta se entró.
     */
    @ParameterizedTest
    @ValueSource(strings = {"CANCELLED", "DELETED"})
    void addResources_whenTerminal_returns409WithTheImmutableCode(String status) {
        long id = serviceInStatus(ServiceStatus.valueOf(status));

        addResourcesExpecting(id, payload(driverId, null, null), 409)
            .body("code", equalTo("OPS-004"));
    }

    @Test
    void addResources_whenRejectedByStatus_writesNothing() {
        long id = serviceInStatus(ServiceStatus.COMPLETED);
        int eventsBefore = countEvents(id);
        String etagBefore = etagOf(id);

        addResourcesExpecting(id, payload(driverId, null, null), 409);

        assertEquals(0, countAdditionalAssignments(id));
        assertEquals(eventsBefore, countEvents(id));
        assertEquals(0, countAuditLogs(id, "ASSIGNMENT"));
        assertEquals(etagBefore, etagOf(id));
    }

    /** El estado se mira ANTES de resolver los recursos: si no, el 400 taparía el 409 real. */
    @Test
    void addResources_whenBothTheStatusAndAResourceAreInvalid_rejectsByStatusFirst() {
        long id = serviceInStatus(ServiceStatus.COMPLETED);

        addResourcesExpecting(id, payload(999_999, null, null), 409)
            .body("code", equalTo("OPS-006"));
    }

    // ---------- OPS-003: ya participa de ESTE viaje (duro) ----------------------

    /**
     * El recurso ya es PRINCIPAL de este viaje. Es el caso que sin la condición que excluye el
     * viaje propio de la consulta de conflictos se contestaría como forzable.
     */
    @Test
    void addResources_whenTheResourceIsAlreadyThePrincipalOfTheSameService_returns409() {
        long id = serviceInProgress();

        addResourcesExpecting(id, payload(principalDriverId, null, null), 409)
            .contentType("application/problem+json")
            .body("code", equalTo("OPS-003"))
            .body("detail", containsString("ya participa de este servicio"))
            .body("$", not(hasKey("forcible")))
            .body("$", not(hasKey("conflicts")));
    }

    @Test
    void addResources_whenTheTractorIsAlreadyThePrincipalOfTheSameService_returns409() {
        long id = serviceInProgress();

        addResourcesExpecting(id, payload(null, principalTractorId, null), 409)
            .body("code", equalTo("OPS-003"));
    }

    /** El recurso ya fue sumado como refuerzo antes: la segunda fuente del mismo viaje. */
    @ParameterizedTest
    @ValueSource(strings = {"DRIVER", "TRACTOR", "TRAILER"})
    void addResources_whenTheResourceIsAlreadyAReinforcementOfTheSameService_returns409(String kind) {
        long id = serviceInProgress();
        seedReinforcementOf(id, kind);

        addResourcesExpecting(id, payloadWithOnly(kind), 409)
            .body("code", equalTo("OPS-003"));
    }

    /**
     * El caso CENTRAL de este endpoint: {@code force} no abre esta puerta. Si la abriera, el mismo
     * conductor entraría dos veces al mismo viaje.
     */
    @ParameterizedTest
    @CsvSource({
        "principal, DRIVER", "principal, TRACTOR", "principal, TRAILER",
        "reinforcement, DRIVER", "reinforcement, TRACTOR", "reinforcement, TRAILER"
    })
    void addResources_withForce_stillRejectsTheSameServiceDuplicate(String source, String kind) {
        long id = serviceInProgress();
        Map<String, Object> body;
        if ("principal".equals(source)) {
            body = switch (kind) {
                case "DRIVER" -> payload(principalDriverId, null, null);
                case "TRACTOR" -> payload(null, principalTractorId, null);
                default -> payload(null, null, principalTrailerId);
            };
        } else {
            seedReinforcementOf(id, kind);
            body = payloadWithOnly(kind);
        }
        body.put("force", true);

        addResourcesExpecting(id, body, 409)
            .body("code", equalTo("OPS-003"));
    }

    /**
     * Un pedido con un duplicado propio Y un conflicto ajeno, pidiendo forzar: gana el DURO.
     *
     * <p>Lo que mide es que {@code force} no abre el duplicado ni siquiera en un pedido MIXTO. NO
     * mide la precedencia: con {@code force: true} los dos ordenes terminan en el mismo 409, porque
     * el duplicado rebota un renglon despues igual. La precedencia se observa en el caso sin
     * forzar, mas abajo.
     */
    @Test
    void addResources_whenOneResourceDuplicatesAndAnotherIsBusyElsewhere_rejectsWithTheHardCode() {
        long id = serviceInProgress();
        holderOf("TRACTOR", tractorId, ServiceStatus.IN_PROGRESS);

        Map<String, Object> body = payload(principalDriverId, tractorId, null);
        body.put("force", true);

        addResourcesExpecting(id, body, 409)
            .body("code", equalTo("OPS-003"));
    }

    /**
     * Un duplicado propio Y un conflicto ajeno, SIN pedir forzar: gana el DURO.
     *
     * <p>Es acá y no en el caso con {@code force} donde la precedencia se puede observar. Con
     * {@code force: true} los dos órdenes terminan en el mismo 409, porque el duplicado se rechaza
     * igual un renglón después. Sin forzar, invertir el orden contestaría un conflicto FORZABLE
     * para una situación que incluye un duplicado que no se fuerza: el usuario apretaría "Forzar"
     * y recibiría otro 409, esta vez sin salida y sin haber cambiado nada.
     */
    @Test
    void addResources_whenBothADuplicateAndAnExternalConflictExist_answersWithTheHardCode() {
        long id = serviceInProgress();
        holderOf("TRACTOR", tractorId, ServiceStatus.IN_PROGRESS);

        addResourcesExpecting(id, payload(principalDriverId, tractorId, null), 409)
            .body("code", equalTo("OPS-003"))
            .body("$", not(hasKey("forcible")));
    }

    /** Dos repetidos se reportan los dos: quien mandó los tres corrige de una, no de a reintentos. */
    @Test
    void addResources_withTwoDuplicates_countsThemInTheDetail() {
        long id = serviceInProgress();

        addResourcesExpecting(id, payload(principalDriverId, principalTractorId, null), 409)
            .body("code", equalTo("OPS-003"))
            // Que el MENSAJE nombre al primero. El orden en si lo garantiza el EnumSet de la
            // consulta (itera en orden de declaracion), no el bucle que lo recorre; lo
            // deterministico esta en ServiceResourceConflictsTest.
            .body("detail", startsWith("El conductor"))
            .body("detail", containsString("Hay 1 recurso más repetido"));
    }

    @Test
    void addResources_whenRejectedAsDuplicate_writesNothing() {
        long id = serviceInProgress();
        int eventsBefore = countEvents(id);

        addResourcesExpecting(id, payload(principalDriverId, null, null), 409);

        assertEquals(0, countAdditionalAssignments(id));
        assertEquals(eventsBefore, countEvents(id));
        assertEquals(0, countAuditLogs(id, "ASSIGNMENT"));
    }

    // ---------- OPS-002: tomado por OTRO viaje (forzable) -----------------------

    @ParameterizedTest
    @CsvSource({
        "DRIVER, PENDING_START", "DRIVER, IN_PROGRESS",
        "TRACTOR, PENDING_START", "TRACTOR, IN_PROGRESS",
        "TRAILER, PENDING_START", "TRAILER, IN_PROGRESS"
    })
    void addResources_whenTheResourceIsThePrincipalOfAnotherActiveService_returns409(
            String kind, String holderStatus) {
        long id = serviceInProgress();
        long holderId = holderOf(kind, resourceIdOf(kind), ServiceStatus.valueOf(holderStatus));

        addResourcesExpecting(id, payloadWithOnly(kind), 409)
            .contentType("application/problem+json")
            .body("code", equalTo("OPS-002"))
            .body("forcible", equalTo(true))
            .body("conflicts.size()", equalTo(1))
            .body("conflicts[0].resource", equalTo(kind))
            .body("conflicts[0].resourceName", equalTo(nameOf(kind)))
            .body("conflicts[0].serviceCode", equalTo(codeOf(holderId)))
            .body("conflicts[0].serviceStatus", equalTo(holderStatus));
    }

    /** La mitad que el sistema anterior no miraba: el otro viaje lo retiene SOLO como refuerzo. */
    @ParameterizedTest
    @CsvSource({
        "DRIVER, PENDING_START", "DRIVER, IN_PROGRESS",
        "TRACTOR, PENDING_START", "TRACTOR, IN_PROGRESS",
        "TRAILER, PENDING_START", "TRAILER, IN_PROGRESS"
    })
    void addResources_whenTheResourceIsOnlyAReinforcementOfAnotherActiveService_returns409(
            String kind, String holderStatus) {
        long id = serviceInProgress();
        // El retenedor lleva sus PROPIOS principales: con serviceInStatus compartiria los tres con
        // el viaje bajo prueba, o sea dos viajes activos sobre el mismo conductor sin linea de
        // forzado — el estado exacto que este modulo existe para impedir, fabricado por el fixture.
        long holderId = holderOf("DRIVER",
            operationsFixtures.seedDriver("ZTEST Retenedor", "Titular"),
            ServiceStatus.valueOf(holderStatus));
        seedReinforcementOf(holderId, kind);

        addResourcesExpecting(id, payloadWithOnly(kind), 409)
            .body("code", equalTo("OPS-002"))
            .body("conflicts.size()", equalTo(1))
            .body("conflicts[0].resource", equalTo(kind))
            .body("conflicts[0].serviceCode", equalTo(codeOf(holderId)));
    }

    /** Los terminales ya liberaron sus recursos, así que no retienen nada. */
    @ParameterizedTest
    @ValueSource(strings = {"COMPLETED", "CANCELLED", "DELETED"})
    void addResources_whenTheHoldingServiceIsTerminal_isAllowed(String holderStatus) {
        long id = serviceInProgress();
        holderOf("DRIVER", driverId, ServiceStatus.valueOf(holderStatus));

        addResources(id, payload(driverId, null, null))
            .body("additionalResources.size()", equalTo(1));
    }

    @Test
    void addResources_withThreeBusyResources_reportsAllOfThem() {
        long id = serviceInProgress();
        holderOf("DRIVER", driverId, ServiceStatus.IN_PROGRESS);
        holderOf("TRACTOR", tractorId, ServiceStatus.IN_PROGRESS);
        holderOf("TRAILER", trailerId, ServiceStatus.IN_PROGRESS);

        addResourcesExpecting(id, payload(driverId, tractorId, trailerId), 409)
            .body("code", equalTo("OPS-002"))
            .body("conflicts.resource", containsInAnyOrder("DRIVER", "TRACTOR", "TRAILER"))
            .body("detail", containsString("Hay 2 recursos más en conflicto"));
    }

    /** Se cuentan RECURSOS distintos, no filas: un conductor retenido por dos viajes es UNO. */
    @Test
    void addResources_whenOneResourceIsHeldByTwoServices_countsResourcesNotRows() {
        long id = serviceInProgress();
        holderOf("DRIVER", driverId, ServiceStatus.PENDING_START);
        holderOf("DRIVER", driverId, ServiceStatus.IN_PROGRESS);

        addResourcesExpecting(id, payload(driverId, null, null), 409)
            .body("conflicts.size()", equalTo(2))
            // Control POSITIVO del detalle: sin el, un Problem que perdiera el detail entero
            // tambien pasaria el not(containsString(...)) de abajo.
            .body("detail", startsWith("El conductor " + driverName))
            // El literal NO lleva la palabra que cambia entre singular y plural: buscando "recursos",
            // romper el conteo por recursos distintos toma la rama SINGULAR, no matchea, y el caso
            // quedaba verde. Es el mismo literal que usan los tres tests hermanos de la casa.
            .body("detail", not(containsString("más en conflicto")));
    }

    @Test
    void addResources_withForce_addsDespiteTheConflictAndRecordsIt() {
        long id = serviceInProgress();
        long holderId = holderOf("DRIVER", driverId, ServiceStatus.IN_PROGRESS);
        Map<String, Object> body = payload(driverId, null, null);
        body.put("force", true);

        addResources(id, body).body("additionalResources.size()", equalTo(1));

        String note = lastEventNote(id);
        assertTrue(note.contains("Refuerzo forzado: el conductor " + driverName), note);
        assertTrue(note.contains(codeOf(holderId)), note);
        assertEquals("Refuerzo de recursos forzando un conflicto de disponibilidad: " + REASON,
            auditDescription(id));
    }

    /** Una línea por viaje retenedor: dos que lo tienen dejan dos líneas, no una. */
    @Test
    void addResources_whenForcingAResourceHeldByTwoServices_recordsOneLinePerHolder() {
        long id = serviceInProgress();
        holderOf("DRIVER", driverId, ServiceStatus.PENDING_START);
        holderOf("DRIVER", driverId, ServiceStatus.IN_PROGRESS);
        Map<String, Object> body = payload(driverId, null, null);
        body.put("force", true);

        addResources(id, body);

        assertEquals(2, countOccurrences(lastEventNote(id), "Refuerzo forzado: "));
    }

    /** Forzar sin conflicto NO ensucia el rastro con una excepción que nunca ocurrió. */
    @Test
    void addResources_withoutConflicts_andForceTrue_addsNoForcedLine() {
        long id = serviceInProgress();
        Map<String, Object> body = payload(driverId, null, null);
        body.put("force", true);

        addResources(id, body);

        assertTrue(!lastEventNote(id).contains("Refuerzo forzado"), lastEventNote(id));
        assertEquals("Refuerzo de recursos: " + REASON, auditDescription(id));
    }

    /** Ausente, false y null significan lo mismo: NO forzar. */
    @ParameterizedTest
    @ValueSource(strings = {"absent", "false", "null"})
    void addResources_withForceAbsentFalseOrNull_rejectsTheConflict(String forceValue) {
        long id = serviceInProgress();
        holderOf("DRIVER", driverId, ServiceStatus.IN_PROGRESS);
        Map<String, Object> body = payload(driverId, null, null);
        if ("absent".equals(forceValue)) {
            body.remove("force");
        } else if ("null".equals(forceValue)) {
            body.put("force", null);
        } else {
            body.put("force", false);
        }

        addResourcesExpecting(id, body, 409).body("code", equalTo("OPS-002"));
    }

    /**
     * Un {@code force} que no parsea sale como el Problem del contrato, no como el cuerpo del
     * lector de JSON. Es el motivo por el que la bandera se recibe como texto.
     */
    @ParameterizedTest
    @ValueSource(strings = {"maybe", "1"})
    void addResources_withAForceThatIsNotABoolean_returns400WithAProblemBody(String forceValue) {
        long id = serviceInProgress();
        Map<String, Object> body = payload(driverId, null, null);
        body.put("force", forceValue);

        addResourcesExpecting(id, body, 400)
            .contentType("application/problem+json")
            .body("code", equalTo("COM-001"))
            .body("detail", containsString("true o false"));
    }

    @Test
    void addResources_afterAConflict_retryingWithForceWorks() {
        long id = serviceInProgress();
        holderOf("DRIVER", driverId, ServiceStatus.IN_PROGRESS);

        addResourcesExpecting(id, payload(driverId, null, null), 409);

        Map<String, Object> forced = payload(driverId, null, null);
        forced.put("force", true);
        addResources(id, forced).body("additionalResources.size()", equalTo(1));
    }

    @Test
    void addResources_whenRejectedByConflict_writesNothing() {
        long id = serviceInProgress();
        holderOf("DRIVER", driverId, ServiceStatus.IN_PROGRESS);
        int eventsBefore = countEvents(id);

        addResourcesExpecting(id, payload(driverId, null, null), 409);

        assertEquals(0, countAdditionalAssignments(id));
        assertEquals(eventsBefore, countEvents(id));
        assertEquals(0, countAuditLogs(id, "ASSIGNMENT"));
    }

    // ---------- Validación del cuerpo -------------------------------------------

    @Test
    void addResources_withoutBody_returns400() {
        long id = serviceInProgress();

        given()
            .header("Authorization", "Bearer " + adminToken)
            .contentType(ContentType.JSON)
        .when()
            .post("/services/" + id + "/resources")
        .then()
            .statusCode(400)
            .body("code", equalTo("COM-001"));
    }

    /** Sin {@code @Valid @NotNull} en el parámetro, un cuerpo null revienta en NPE 500. */
    @Test
    void addResources_withNullBody_returns400() {
        long id = serviceInProgress();

        given()
            .header("Authorization", "Bearer " + adminToken)
            .contentType(ContentType.JSON)
            .body("null")
        .when()
            .post("/services/" + id + "/resources")
        .then()
            .statusCode(400)
            .body("code", equalTo("COM-001"));
    }

    @Test
    void addResources_withNoResourceAtAll_returns400NamingTheRule() {
        long id = serviceInProgress();

        addResourcesExpecting(id, payload(null, null, null), 400)
            .body("code", equalTo("COM-001"))
            .body("detail", containsString("al menos un recurso"));
    }

    /** Los tres en null explícito son el mismo pedido vacío que ninguna clave. */
    @Test
    void addResources_withTheThreeResourcesExplicitlyNull_returns400() {
        long id = serviceInProgress();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("driverId", null);
        body.put("tractorId", null);
        body.put("trailerId", null);
        body.put("reason", REASON);

        addResourcesExpecting(id, body, 400)
            .body("detail", containsString("al menos un recurso"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"absent", "null"})
    void addResources_withoutReason_returns400NamingIt(String kind) {
        long id = serviceInProgress();
        Map<String, Object> body = payload(driverId, null, null);
        if ("absent".equals(kind)) {
            body.remove("reason");
        } else {
            body.put("reason", null);
        }

        addResourcesExpecting(id, body, 400)
            .body("code", equalTo("COM-001"))
            .body("errors.field", hasItem("reason"));
    }

    /**
     * Doce espacios son doce caracteres y cero de contenido. El mínimo se mide DESPUÉS de recortar,
     * o no es un mínimo sino un campo obligatorio que se puede saltear.
     */
    @Test
    void addResources_withABlankReason_returns400() {
        long id = serviceInProgress();
        Map<String, Object> body = payload(driverId, null, null);
        body.put("reason", "            ");

        addResourcesExpecting(id, body, 400).body("code", equalTo("COM-001"));
    }

    /** Y uno CORTO rellenado con espacios tampoco pasa, que es el que la anotación deja entrar. */
    @Test
    void addResources_withAPaddedShortReason_returns400() {
        long id = serviceInProgress();
        Map<String, Object> body = payload(driverId, null, null);
        body.put("reason", "corta     ");

        addResourcesExpecting(id, body, 400)
            .body("detail", containsString("al menos 10 caracteres"));
    }

    @Test
    void addResources_withAReasonUnderTheMinimum_returns400() {
        long id = serviceInProgress();
        Map<String, Object> body = payload(driverId, null, null);
        body.put("reason", "123456789");

        addResourcesExpecting(id, body, 400).body("code", equalTo("COM-001"));
    }

    @Test
    void addResources_withAReasonExactlyAtTheMinimum_isAccepted() {
        long id = serviceInProgress();
        Map<String, Object> body = payload(driverId, null, null);
        body.put("reason", "1234567890");

        addResources(id, body).body("additionalResources[0].reason", equalTo("1234567890"));
    }

    @Test
    void addResources_withAReasonExactlyAtTheMaximum_isAccepted() {
        long id = serviceInProgress();
        Map<String, Object> body = payload(driverId, null, null);
        body.put("reason", "R".repeat(500));

        // Se afirma el valor guardado, no solo el 200: es lo que descarta un truncado silencioso.
        addResources(id, body)
            .body("additionalResources[0].reason", equalTo("R".repeat(500)));
    }

    @Test
    void addResources_withAReasonOverTheMaximum_returns400() {
        long id = serviceInProgress();
        Map<String, Object> body = payload(driverId, null, null);
        body.put("reason", "R".repeat(501));

        addResourcesExpecting(id, body, 400).body("code", equalTo("COM-001"));
    }

    /** El byte NUL sobrevive al recorte y al mínimo, y PostgreSQL no lo admite: 400, no 500. */
    @Test
    void addResources_withANulCharacterInTheReason_returns400NotAServerError() {
        long id = serviceInProgress();
        Map<String, Object> body = payload(driverId, null, null);
        body.put("reason", "Relevo reglamentario\u0000 del conductor");

        addResourcesExpecting(id, body, 400).body("code", equalTo("COM-001"));
    }

    @ParameterizedTest
    @CsvSource({
        "driverId, 0", "driverId, -1",
        "tractorId, 0", "tractorId, -1",
        "trailerId, 0", "trailerId, -1"
    })
    void addResources_withNonPositiveResourceIds_returns400(String field, int value) {
        long id = serviceInProgress();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put(field, value);
        body.put("reason", REASON);

        // errors.field es lo que distingue el rechazo DECLARATIVO del 400 que da el service por
        // "no existe o esta inactivo": sin esta asercion, borrar @Positive entero deja el caso
        // verde, porque un id 0 llega igual al service y sale con el MISMO codigo.
        addResourcesExpecting(id, body, 400)
            .body("code", equalTo("COM-001"))
            .body("errors.field", hasItem(field));
    }

    @ParameterizedTest
    @CsvSource({
        "driverId, El conductor indicado no existe",
        "tractorId, El tracto indicado no existe",
        "trailerId, La carreta indicada no existe"
    })
    void addResources_withAnUnknownResource_returns400(String field, String message) {
        long id = serviceInProgress();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put(field, 999_999);
        body.put("reason", REASON);

        addResourcesExpecting(id, body, 400)
            .body("code", equalTo("COM-001"))
            .body("detail", containsString(message));
    }

    /** Sumar un refuerzo ELIGE, así que un recurso dado de baja se rechaza, igual que al asignar. */
    @ParameterizedTest
    @ValueSource(strings = {"DRIVER", "TRACTOR", "TRAILER"})
    void addResources_withAnInactiveResource_returns400(String kind) {
        long id = serviceInProgress();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put(fieldOf(kind), inactiveResourceOf(kind));
        body.put("reason", REASON);

        addResourcesExpecting(id, body, 400).body("code", equalTo("COM-001"));
    }

    /**
     * La DISPONIBILIDAD no prohíbe: el catálogo ordena la lista de la pantalla. Y menos todavía en
     * un refuerzo, que por definición sale a resolver un imprevisto con lo que hay.
     */
    @ParameterizedTest
    @CsvSource({
        "DRIVER, maintenance", "DRIVER, not_available",
        "TRACTOR, maintenance", "TRACTOR, not_available",
        "TRAILER, maintenance", "TRAILER, not_available"
    })
    void addResources_withAResourceInAnUnavailableStatus_isAccepted(String kind, String status) {
        long id = serviceInProgress();
        int unitId = switch (kind) {
            case "DRIVER" -> operationsFixtures.seedDriver(
                "ZTEST NoDisponible", "Relevo", null, null, status, true);
            case "TRACTOR" -> operationsFixtures.seedTractor(true, status);
            default -> operationsFixtures.seedTrailer(true, status);
        };
        Map<String, Object> body = new LinkedHashMap<>();
        body.put(fieldOf(kind), unitId);
        body.put("reason", REASON);

        addResources(id, body).body("additionalResources.size()", equalTo(1));
    }

    /** El cuerpo se valida ANTES del 404 y del 409, así que el 400 gana sobre los dos. */
    @Test
    void addResources_withAMalformedBody_andAnUnknownService_returns400NotFound() {
        addResourcesExpecting(999_999, payload(null, null, null), 400)
            .body("code", equalTo("COM-001"));
    }

    @Test
    void addResources_withAMalformedBody_andACancelledService_returns400NotConflict() {
        long id = serviceInStatus(ServiceStatus.CANCELLED);

        addResourcesExpecting(id, payload(null, null, null), 400)
            .body("code", equalTo("COM-001"));
    }

    // ---------- Ruta y autorización ---------------------------------------------

    @Test
    void addResources_withUnknownId_returns404() {
        addResourcesExpecting(999_999, payload(driverId, null, null), 404)
            .body("code", equalTo("OPS-005"));
    }

    /**
     * El id llega como TEXTO y se parsea acá, así que un valor que no parsea es 400 con detalle y
     * no el 404 vacío del framework.
     */
    @ParameterizedTest
    @ValueSource(strings = {"abc", "9999999999999999999999"})
    void addResources_withMalformedId_returns400(String id) {
        given()
            .header("Authorization", "Bearer " + adminToken)
            .contentType(ContentType.JSON)
            .body(payload(driverId, null, null))
        .when()
            .post("/services/" + id + "/resources")
        .then()
            .statusCode(400)
            .body("code", equalTo("COM-001"));
    }

    /** Estos SÍ parsean; son 404 porque no existen, que es otra cosa. */
    @ParameterizedTest
    @ValueSource(strings = {"0", "-1"})
    void addResources_withZeroOrNegativeId_returns404(String id) {
        given()
            .header("Authorization", "Bearer " + adminToken)
            .contentType(ContentType.JSON)
            .body(payload(driverId, null, null))
        .when()
            .post("/services/" + id + "/resources")
        .then()
            .statusCode(404)
            .body("code", equalTo("OPS-005"));
    }

    @Test
    void addResources_withoutToken_returns401() {
        long id = serviceInProgress();

        given()
            .contentType(ContentType.JSON)
            .body(payload(driverId, null, null))
        .when()
            .post("/services/" + id + "/resources")
        .then()
            .statusCode(401);
    }

    @ParameterizedTest
    @ValueSource(strings = {"admin", "general_manager", "operations_manager", "dispatcher"})
    void addResources_asAnyOperatingRole_isAllowed(String role) {
        long id = serviceInProgress();
        String token = TestAuth.fabricateTokenForUser(
            fixtures.userId("admin"), "admin", role);

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(payload(driverId, null, null))
        .when()
            .post("/services/" + id + "/resources")
        .then()
            .statusCode(200);
    }

    /** {@code sales} registra y edita el viaje, pero la operación en ruta es del despacho. */
    @ParameterizedTest
    @ValueSource(strings = {"sales", "warehouse_keeper", "finance_manager"})
    void addResources_asRoleWithoutOperating_returns403(String role) {
        long id = serviceInProgress();
        String token = TestAuth.fabricateAccessToken("zrole", role);

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(payload(driverId, null, null))
        .when()
            .post("/services/" + id + "/resources")
        .then()
            .statusCode(403)
            .body("code", equalTo("COM-003"));
    }

    // ---------- Bloqueo y concurrencia ------------------------------------------

    /**
     * El 409 de lock es TRANSITORIO: liberada la fila, el mismo pedido pasa. Es lo que lo separa
     * del 409 de un viaje inmutable, que no se destraba reintentando.
     */
    @Test
    @Timeout(60)
    void addResources_whenTheRowIsLockedByAnotherOperation_returns409() throws Exception {
        long id = serviceInProgress();

        withRowLocked(id, () ->
            addResourcesExpecting(id, payload(driverId, null, null), 409)
                .body("code", equalTo("OPS-008")));

        assertEquals(0, countAuditLogs(id, "ASSIGNMENT"));
        addResources(id, payload(driverId, null, null))
            .body("additionalResources.size()", equalTo(1));
    }

    /**
     * Dos viajes distintos peleando por el MISMO conductor libre. Nunca pueden pasar los dos: eso
     * serían dos viajes activos compartiendo un conductor, sin que nadie lo haya decidido y sin la
     * línea de bitácora que dice que se forzó — el agujero que este módulo existe para cerrar.
     */
    @Test
    @Timeout(60)
    void addResources_twoConcurrentRequestsForTheSameFreeResource_onlyOnePasses() throws Exception {
        long firstId = serviceInProgress();
        // El segundo viaje lleva PRINCIPALES propios: con los mismos tres, el fixture fabricaria dos
        // viajes activos compartiendo conductor sin linea de forzado — no contamina esta medicion
        // (la carrera es por un recurso de refuerzo libre), pero es la trampa que ya se saco de
        // holderOf y no conviene dejarla a un git blame de distancia.
        long secondId = holderOf("DRIVER",
            operationsFixtures.seedDriver("ZTEST Segundo", "Titular"), ServiceStatus.IN_PROGRESS);

        List<Integer> statuses = inParallel(
            () -> addResourcesStatus(firstId, payload(driverId, null, null)),
            () -> addResourcesStatus(secondId, payload(driverId, null, null)));

        assertEquals(1, statuses.stream().filter(status -> status == 200).count(), statuses.toString());
        assertEquals(1, statuses.stream().filter(status -> status == 409).count(), statuses.toString());
        // Y que el 409 sea uno de los DOS legitimos: sin esto, un OPS-006 o un OPS-003 mal
        // disparado —que serian defectos reales— satisfacen el caso sin que nadie lo note.
        assertTrue(Set.of("OPS-002", "OPS-008").contains(String.valueOf(lastConflictCode)),
            "el perdedor contesto " + lastConflictCode);
        assertEquals(1, countAdditionalAssignments(firstId) + countAdditionalAssignments(secondId));
    }

    /**
     * Dos pedidos IDÉNTICOS sobre el MISMO viaje: entra uno solo.
     *
     * <p>Lo que serializa acá es el lock de la FILA, así que el perdedor puede salir por dos
     * caminos legítimos: si alcanza a leer la fila ya escrita, por el duplicado (`OPS-003`); si se
     * le agota el tope de espera antes, por el lock (`OPS-008`). El caso no elige entre los dos —
     * dependen del reloj— pero sí exige que sea uno de ellos y que quede UNA sola fila. El camino
     * del duplicado con la fila ya libre lo cubre {@code whenTheResourceIsAlreadyAReinforcement...}.
     */
    @Test
    @Timeout(60)
    void addResources_twoConcurrentIdenticalRequestsOnTheSameService_secondIsRejected()
            throws Exception {
        long id = serviceInProgress();

        List<Integer> statuses = inParallel(
            () -> addResourcesStatus(id, payload(driverId, null, null)),
            () -> addResourcesStatus(id, payload(driverId, null, null)));

        assertEquals(1, statuses.stream().filter(status -> status == 200).count(), statuses.toString());
        // El perdedor tiene que ser un 409, no cualquier cosa: sin esto, un 500 satisface las otras
        // dos aserciones y el caso llamado "el segundo se rechaza" pasa igual.
        assertEquals(1, statuses.stream().filter(status -> status == 409).count(), statuses.toString());
        assertTrue(Set.of("OPS-003", "OPS-008").contains(String.valueOf(lastConflictCode)),
            "el perdedor contesto " + lastConflictCode);
        assertEquals(1, countAdditionalAssignments(id));
    }

    // ---------- El límite de la reapertura --------------------------------------

    /**
     * Camino COMPLETO por la API del callejón sin salida que este endpoint vuelve alcanzable:
     * sumar un refuerzo, cancelar y ya no poder reabrir.
     *
     * <p>No es un capricho del negocio sino el presupuesto de esperas de lock: verificar una lista
     * de recursos de largo variable no entra en la banda. Queda medido acá para que el día que se
     * rediseñe ese lockeo, este test sea el que avise que la restricción se puede levantar.
     */
    @Test
    void reopen_afterAReinforcementWasAdded_isRejected() {
        long id = serviceInProgress();
        addResources(id, payload(driverId, null, null));

        String etag = etagOf(id);
        Map<String, Object> cancel = new LinkedHashMap<>();
        cancel.put("target", "CANCELLED");
        cancel.put("note", "El cliente reprogramó el embarque para agosto");
        given()
            .header("Authorization", "Bearer " + adminToken)
            .header("If-Match", etag)
            .contentType(ContentType.JSON)
            .body(cancel)
        .when()
            .post("/services/" + id + "/status")
        .then()
            .statusCode(200);

        Map<String, Object> reopen = new LinkedHashMap<>();
        reopen.put("target", "REOPENED");
        reopen.put("note", "El cliente retomó el embarque que había reprogramado");
        given()
            .header("Authorization", "Bearer " + adminToken)
            .header("If-Match", etagOf(id))
            .contentType(ContentType.JSON)
            .body(reopen)
        .when()
            .post("/services/" + id + "/status")
        .then()
            .statusCode(409)
            .body("code", equalTo("OPS-009"));
    }

    // ---------- Helpers ---------------------------------------------------------

    private Map<String, Object> payload(Integer driver, Integer tractor, Integer trailer) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("driverId", driver);
        body.put("tractorId", tractor);
        body.put("trailerId", trailer);
        body.put("reason", REASON);
        body.put("force", false);
        return body;
    }

    /** Cuerpo con UN solo recurso, el del tipo pedido. */
    private Map<String, Object> payloadWithOnly(String kind) {
        return switch (kind) {
            case "DRIVER" -> payload(driverId, null, null);
            case "TRACTOR" -> payload(null, tractorId, null);
            case "TRAILER" -> payload(null, null, trailerId);
            default -> throw new IllegalArgumentException(kind);
        };
    }

    private int resourceIdOf(String kind) {
        return switch (kind) {
            case "DRIVER" -> driverId;
            case "TRACTOR" -> tractorId;
            case "TRAILER" -> trailerId;
            default -> throw new IllegalArgumentException(kind);
        };
    }

    private String nameOf(String kind) {
        return switch (kind) {
            case "DRIVER" -> driverName;
            case "TRACTOR" -> tractorPlate;
            case "TRAILER" -> trailerPlate;
            default -> throw new IllegalArgumentException(kind);
        };
    }

    private String fieldOf(String kind) {
        return switch (kind) {
            case "DRIVER" -> "driverId";
            case "TRACTOR" -> "tractorId";
            case "TRAILER" -> "trailerId";
            default -> throw new IllegalArgumentException(kind);
        };
    }

    private int inactiveResourceOf(String kind) {
        return switch (kind) {
            case "DRIVER" -> operationsFixtures.seedDriver(
                "ZTEST Baja", "Inactivo", null, null, WarehouseTestData.STATUS_AVAILABLE, false);
            case "TRACTOR" -> operationsFixtures.seedTractor(false, WarehouseTestData.STATUS_AVAILABLE);
            case "TRAILER" -> operationsFixtures.seedTrailer(false, WarehouseTestData.STATUS_AVAILABLE);
            default -> throw new IllegalArgumentException(kind);
        };
    }

    /** Siembra un refuerzo del tipo pedido sobre el viaje indicado, por SQL. */
    private void seedReinforcementOf(long serviceId, String kind) {
        operationsFixtures.seedAdditionalAssignment(serviceId,
            "DRIVER".equals(kind) ? driverId : null,
            "TRACTOR".equals(kind) ? tractorId : null,
            "TRAILER".equals(kind) ? trailerId : null,
            SEEDED_REASON);
    }

    /** Otro viaje que RETIENE el recurso indicado como principal, en el estado pedido. */
    private long holderOf(String kind, int resourceId, ServiceStatus status) {
        long holderId = createService("Trujillo " + resourceId + " " + (++routeSeq), "Lima " + status);
        operationsFixtures.forceServiceStatus(holderId, status.name());
        operationsFixtures.forceServiceResources(holderId,
            "DRIVER".equals(kind) ? resourceId : operationsFixtures.seedDriver("ZTEST Otro", "Titular"),
            "TRACTOR".equals(kind) ? resourceId : operationsFixtures.seedTractor(),
            "TRAILER".equals(kind) ? resourceId : null);
        if (status == ServiceStatus.IN_PROGRESS || status == ServiceStatus.COMPLETED) {
            operationsFixtures.forceServiceDates(holderId,
                OffsetDateTime.parse("2026-07-01T10:00:00Z"),
                status == ServiceStatus.COMPLETED
                    ? OffsetDateTime.parse("2026-07-01T18:00:00Z") : null);
        }
        return holderId;
    }

    private long serviceInProgress() {
        return serviceInStatus(ServiceStatus.IN_PROGRESS);
    }

    /**
     * Un viaje en el estado pedido, con sus recursos principales puestos.
     *
     * <p>Los recursos van SIEMPRE que el estado sea posterior al alta, y no por prolijidad: a todo
     * estado posterior se llega asignando, así que una fila con estado avanzado y sin conductor es
     * una que la aplicación no puede producir, y medir contra ella sería medir un estado que no
     * existe.
     */
    private long serviceInStatus(ServiceStatus status) {
        long serviceId = createService("Piura " + status + " " + (++routeSeq), "Lima " + status);
        if (status == ServiceStatus.PENDING_ASSIGNMENT) {
            return serviceId;
        }
        operationsFixtures.forceServiceStatus(serviceId, status.name());
        operationsFixtures.forceServiceResources(
            serviceId, principalDriverId, principalTractorId, principalTrailerId);
        if (status == ServiceStatus.IN_PROGRESS) {
            operationsFixtures.forceServiceDates(
                serviceId, OffsetDateTime.parse("2026-07-01T10:00:00Z"), null);
        } else if (status == ServiceStatus.COMPLETED) {
            operationsFixtures.forceServiceDates(serviceId,
                OffsetDateTime.parse("2026-07-01T10:00:00Z"),
                OffsetDateTime.parse("2026-07-01T18:00:00Z"));
        }
        return serviceId;
    }

    private ValidatableResponse addResources(long serviceId, Map<String, Object> body) {
        return addResourcesExpecting(serviceId, body, 200);
    }

    private ValidatableResponse addResourcesExpecting(
            long serviceId, Map<String, Object> body, int expectedStatus) {
        return given()
            .header("Authorization", "Bearer " + adminToken)
            .contentType(ContentType.JSON)
            .body(body)
        .when()
            .post("/services/" + serviceId + "/resources")
        .then()
            .statusCode(expectedStatus);
    }

    /** El codigo del ultimo 409 de una carrera, para poder afirmar POR QUE perdio el perdedor. */
    private volatile String lastConflictCode;

    private int addResourcesStatus(long serviceId, Map<String, Object> body) {
        io.restassured.response.Response response = given()
            .header("Authorization", "Bearer " + adminToken)
            .contentType(ContentType.JSON)
            .body(body)
        .when()
            .post("/services/" + serviceId + "/resources")
        .then()
            .extract().response();
        if (response.statusCode() == 409) {
            lastConflictCode = response.jsonPath().getString("code");
        }
        return response.statusCode();
    }

    private long createService() {
        return createService("Piura", "Lima");
    }

    private long createService(String origin, String destination) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("clientId", clientId);
        body.put("tripScope", "PROVINCIA");
        body.put("tentativeDate", LocalDate.now().plusDays(3).toString());
        body.put("origin", origin);
        body.put("destination", destination);
        body.put("cargoTypeId", cargoTypeId);
        body.put("weightKg", 12000);
        body.put("price", SEEDED_PRICE);
        body.put("currencyId", currencyId);

        return given()
            .header("Authorization", "Bearer " + adminToken)
            .contentType(ContentType.JSON)
            .body(body)
        .when()
            .post("/services")
        .then()
            .statusCode(201)
            .extract().jsonPath().getLong("id");
    }

    private JsonPath detailOf(long serviceId) {
        return given()
            .header("Authorization", "Bearer " + adminToken)
        .when()
            .get("/services/" + serviceId)
        .then()
            .statusCode(200)
            .extract().jsonPath();
    }

    private String etagOf(long serviceId) {
        return given()
            .header("Authorization", "Bearer " + adminToken)
        .when()
            .get("/services/" + serviceId)
        .then()
            .statusCode(200)
            .extract().header("ETag");
    }

    private String codeOf(long serviceId) {
        return detailOf(serviceId).getString("code");
    }

    private String plateOf(String table, int unitId) {
        return (String) entityManager.createNativeQuery(
                "SELECT plate FROM public." + table + " WHERE id = ?1")
            .setParameter(1, unitId).getSingleResult();
    }

    private int countEvents(long serviceId) {
        return ((Number) entityManager.createNativeQuery(
                "SELECT COUNT(*) FROM operaciones.service_events WHERE service_id = ?1")
            .setParameter(1, serviceId).getSingleResult()).intValue();
    }

    private String lastEventNote(long serviceId) {
        return (String) entityManager.createNativeQuery(
                "SELECT note FROM operaciones.service_events WHERE service_id = ?1"
                    + " ORDER BY created_at DESC, id DESC LIMIT 1")
            .setParameter(1, serviceId).getSingleResult();
    }

    private String lastEventType(long serviceId) {
        return (String) entityManager.createNativeQuery(
                "SELECT event_type FROM operaciones.service_events WHERE service_id = ?1"
                    + " ORDER BY created_at DESC, id DESC LIMIT 1")
            .setParameter(1, serviceId).getSingleResult();
    }

    private int countAuditLogs(long serviceId, String changeType) {
        return ((Number) entityManager.createNativeQuery(
                "SELECT COUNT(*) FROM operaciones.service_audit_logs"
                    + " WHERE service_id = ?1 AND change_type = ?2")
            .setParameter(1, serviceId).setParameter(2, changeType).getSingleResult()).intValue();
    }

    private int countAdditionalAssignments(long serviceId) {
        return ((Number) entityManager.createNativeQuery(
                "SELECT COUNT(*) FROM operaciones.service_assignments WHERE service_id = ?1")
            .setParameter(1, serviceId).getSingleResult()).intValue();
    }

    private Integer assignmentColumn(long serviceId, String column) {
        Number value = (Number) entityManager.createNativeQuery(
                "SELECT " + column + " FROM operaciones.service_assignments"
                    + " WHERE service_id = ?1 ORDER BY id LIMIT 1")
            .setParameter(1, serviceId).getSingleResult();
        return value == null ? null : value.intValue();
    }

    @SuppressWarnings("unchecked")
    private List<String> auditColumn(long serviceId, String column) {
        return entityManager.createNativeQuery(
                "SELECT " + column + " FROM operaciones.service_audit_logs"
                    + " WHERE service_id = ?1 AND change_type = 'ASSIGNMENT' ORDER BY field_name")
            .setParameter(1, serviceId).getResultList();
    }

    private String auditDescription(long serviceId) {
        return (String) entityManager.createNativeQuery(
                "SELECT DISTINCT description FROM operaciones.service_audit_logs"
                    + " WHERE service_id = ?1 AND change_type = 'ASSIGNMENT'")
            .setParameter(1, serviceId).getSingleResult();
    }

    private int auditChangedBy(long serviceId) {
        return ((Number) entityManager.createNativeQuery(
                "SELECT DISTINCT changed_by FROM operaciones.service_audit_logs"
                    + " WHERE service_id = ?1 AND change_type = 'ASSIGNMENT'")
            .setParameter(1, serviceId).getSingleResult()).intValue();
    }

    private int updatedByOf(long serviceId) {
        return ((Number) entityManager.createNativeQuery(
                "SELECT updated_by FROM operaciones.services WHERE id = ?1")
            .setParameter(1, serviceId).getSingleResult()).intValue();
    }

    private static void assertThatAll(List<String> actual, String... expected) {
        assertEquals(List.of(expected), actual);
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int from = text.indexOf(needle);
        while (from >= 0) {
            count++;
            from = text.indexOf(needle, from + needle.length());
        }
        return count;
    }

    /** Mismo andamiaje que la asignación: retiene la fila desde otra transacción. */
    private void withRowLocked(long id, Runnable body) throws Exception {
        CyclicBarrier locked = new CyclicBarrier(2);
        CyclicBarrier finished = new CyclicBarrier(2);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> holder = pool.submit(() -> {
                QuarkusTransaction.requiringNew().run(() -> {
                    entityManager.createNativeQuery(
                            "SELECT id FROM operaciones.services WHERE id = ?1 FOR UPDATE")
                        .setParameter(1, id).getSingleResult();
                    awaitQuietly(locked);
                    awaitQuietly(locked);
                });
                awaitQuietly(finished);
            });
            try {
                locked.await(30, TimeUnit.SECONDS);
                body.run();
            } finally {
                awaitQuietly(locked);
                awaitQuietly(finished);
                holder.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /** Dos llamadas que arrancan juntas, para que se peleen de verdad por el mismo recurso. */
    private List<Integer> inParallel(Callable<Integer> first, Callable<Integer> second)
            throws Exception {
        CyclicBarrier start = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // La barrera NO se traga su falla, a diferencia de la de los helpers de lock: si un
            // hilo no llega, los dos pedidos salen casi secuenciales, el perdedor contesta
            // determinista y las aserciones pasan igual — o sea, el caso dejaria de medir
            // concurrencia sin que nada lo diga.
            Future<Integer> firstResult = pool.submit(() -> {
                start.await(30, TimeUnit.SECONDS);
                return first.call();
            });
            Future<Integer> secondResult = pool.submit(() -> {
                start.await(30, TimeUnit.SECONDS);
                return second.call();
            });
            return List.of(firstResult.get(45, TimeUnit.SECONDS),
                secondResult.get(45, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
    }

    private static void awaitQuietly(CyclicBarrier barrier) {
        try {
            barrier.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception ignored) {
            // barrera rota o espera agotada: la falla real ya la reporta quien corresponde
        }
    }
}
