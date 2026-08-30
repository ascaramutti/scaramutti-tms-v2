package com.scaramutti.tms.operations;

import com.scaramutti.tms.support.HermeticTestData;
import com.scaramutti.tms.support.OperationsTestData;
import com.scaramutti.tms.support.TestAuth;
import com.scaramutti.tms.support.WarehouseTestData;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integración de {@code POST /services/{id}/assignment}.
 *
 * <p>Lo que se fija acá, más allá de la forma: que el conflicto de recursos mire las DOS fuentes
 * (el recurso principal de otro viaje Y sus refuerzos — el sistema anterior solo miraba la
 * primera), que forzar quede REGISTRADO, que un rechazo no escriba una sola fila, y que el
 * despacho —que es quien asigna— reciba el detalle sin importes.
 */
@QuarkusTest
class ServiceAssignmentResourceTest {

    private static final String NOTE = "Sale del patio a las 05:00";
    private static final String REINFORCEMENT_REASON = "Relevo por horas de conducción";

    @Inject HermeticTestData fixtures;
    @Inject OperationsTestData operationsFixtures;
    @Inject WarehouseTestData warehouseFixtures;
    @Inject EntityManager entityManager;

    private int clientId;
    private int cargoTypeId;
    private int currencyId;
    private String adminToken;

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

    /**
     * Los tres recursos se comprueban uno por uno y con placas DISTINTAS entre tracto y carreta:
     * cruzarlas es el error de asignación que un test que solo pida "no es null" nunca ve.
     */
    @Test
    void assign_fromPendingAssignment_movesToPendingStartAndReturnsTheResources() {
        long id = createService();

        given()
            .header("Authorization", "Bearer " + adminToken)
            .contentType(ContentType.JSON)
            .body(assignmentPayload())
        .when()
            .post("/services/" + id + "/assignment")
        .then()
            .statusCode(200)
            .body("id", equalTo((int) id))
            .body("status", equalTo("PENDING_START"))
            .body("driver.id", equalTo(driverId))
            .body("driver.fullName", equalTo(driverName))
            .body("tractor.kind", equalTo("TRACTOR"))
            .body("tractor.id", equalTo(tractorId))
            .body("tractor.plate", equalTo(tractorPlate))
            .body("trailer.kind", equalTo("TRAILER"))
            .body("trailer.id", equalTo(trailerId))
            .body("trailer.plate", equalTo(trailerPlate))
            .body("events.size()", equalTo(2))
            .body("events[1].eventType", equalTo("ASSIGNMENT"));
    }

    @Test
    void assign_withoutTrailer_isAccepted() {
        long id = createService();
        Map<String, Object> payload = assignmentPayload();
        payload.remove("trailerId");

        assign(id, payload)
            .body("status", equalTo("PENDING_START"))
            // la clave VIAJA en null: la carreta ausente es informacion, no un dato faltante
            .body("$", hasKey("trailer"))
            .body("trailer", nullValue());
    }

    /** Un null explícito es lo mismo que la ausencia: la carreta es opcional, no se "borra". */
    @Test
    void assign_withExplicitNullTrailer_isAccepted() {
        long id = createService();
        Map<String, Object> payload = assignmentPayload();
        payload.put("trailerId", null);

        assign(id, payload).body("trailer", nullValue());
    }

    /** Lo que contesta tiene que ser lo que guardó: si no, la pantalla muestra algo que no pasó. */
    @Test
    void assign_persistsWhatItAnswers() {
        long id = createService();
        assign(id, assignmentPayload());

        JsonPath detail = detailOf(id);
        assertEquals("PENDING_START", detail.getString("status"));
        assertEquals(driverId, detail.getInt("driver.id"));
        assertEquals(tractorPlate, detail.getString("tractor.plate"));
        assertEquals(trailerPlate, detail.getString("trailer.plate"));
    }

    /** La versión se mueve, y el ETag que devuelve es el que la base tiene (no el anterior). */
    @Test
    void assign_movesTheEtag() {
        long id = createService();
        String etagBefore = etagOf(id);

        String returned = given()
            .header("Authorization", "Bearer " + adminToken)
            .contentType(ContentType.JSON)
            .body(assignmentPayload())
        .when()
            .post("/services/" + id + "/assignment")
        .then()
            .statusCode(200)
            .extract().header("ETag");

        assertNotEquals(etagBefore, returned, "asignar mueve la versión");
        assertEquals(etagOf(id), returned, "el ETag devuelto es el que quedó guardado");
    }

    /** El cuerpo depende del rol, así que ninguna cache puede reusarlo para otro usuario. */
    @Test
    void assign_isNotCacheable() {
        long id = createService();

        given()
            .header("Authorization", "Bearer " + adminToken)
            .contentType(ContentType.JSON)
            .body(assignmentPayload())
        .when()
            .post("/services/" + id + "/assignment")
        .then()
            .statusCode(200)
            .header("Cache-Control", "no-store")
            .header("Vary", "Authorization");
    }

    /**
     * Este endpoint NO es condicional: un {@code If-Match} viejo se ignora. El caso existe para
     * que nadie copie la verificación de versión del PUT sin que se note.
     */
    @Test
    void assign_withAStaleIfMatch_isIgnored() {
        long id = createService();

        given()
            .header("Authorization", "Bearer " + adminToken)
            .header("If-Match", "\"2020-01-01T00:00:00Z\"")
            .contentType(ContentType.JSON)
            .body(assignmentPayload())
        .when()
            .post("/services/" + id + "/assignment")
        .then()
            .statusCode(200);
    }

    /**
     * Los recursos PRINCIPALES van a las columnas del viaje. La tabla de refuerzos es de los
     * refuerzos: escribir ahí el principal haría que el viaje se choque consigo mismo.
     */
    @Test
    void assign_writesNothingIntoTheAdditionalResourcesTable() {
        long id = createService();
        assign(id, assignmentPayload());

        assertEquals(0, countAdditionalAssignments(id));
    }

    // ---------- Rastro ----------------------------------------------------------

    /** La nota COMPLETA, no un {@code contains}: lo que importa es que no sobre ni falte una línea. */
    @Test
    void assign_writesOneLogEntryNamingTheDriverAndThePlates() {
        long id = createService();
        assign(id, assignmentPayload());

        assertEquals(2, countEvents(id), "una entrada por ACCIÓN, no una por recurso");
        assertEquals(
            "Conductor: " + driverName + "\nTracto: " + tractorPlate
                + "\nCarreta: " + trailerPlate + "\nNota: " + NOTE,
            detailOf(id).getString("events[1].note"));
    }

    @Test
    void assign_withoutANote_omitsTheNoteLine() {
        long id = createService();
        Map<String, Object> payload = assignmentPayload();
        payload.remove("note");
        payload.remove("trailerId");
        assign(id, payload);

        assertEquals("Conductor: " + driverName + "\nTracto: " + tractorPlate,
            detailOf(id).getString("events[1].note"));
    }

    @Test
    void assign_withABlankNote_isTreatedAsNoNote() {
        long id = createService();
        Map<String, Object> payload = assignmentPayload();
        payload.put("note", "   ");
        payload.remove("trailerId");
        assign(id, payload);

        assertEquals("Conductor: " + driverName + "\nTracto: " + tractorPlate,
            detailOf(id).getString("events[1].note"));
    }

    /**
     * La bitácora tiene UNA línea por dato, así que un salto de línea en la nota permitiría
     * plantar una línea con el formato del servidor en un rastro que después nadie puede editar.
     */
    @Test
    void assign_withNewlinesInTheNote_cannotForgeLogLines() {
        long id = createService();
        Map<String, Object> payload = assignmentPayload();
        payload.put("note", "ok\nConductor: OTRO CONDUCTOR");
        payload.remove("trailerId");
        assign(id, payload);

        String note = detailOf(id).getString("events[1].note");
        assertEquals("Conductor: " + driverName + "\nTracto: " + tractorPlate
            + "\nNota: ok ⏎ Conductor: OTRO CONDUCTOR", note);
        assertEquals(3, note.lines().count(), "la nota del usuario no ganó líneas propias");
        // el texto EXACTO no se pierde: queda en la auditoría, que es el registro reconstruible
        assertTrue(auditDescription(id).contains("ok\nConductor: OTRO CONDUCTOR"));
    }

    /**
     * El salto de linea puede venir de la BASE, no solo del cuerpo del request: el nombre del
     * conductor y las placas salen de tablas del sistema anterior, que sigue corriendo y sí tiene
     * formularios para escribirlas. Aplastar solo la nota del usuario es cerrar la puerta y dejar
     * la ventana abierta, y sin este caso esa mitad queda sin red.
     */
    /**
     * El salto de línea puede venir de la BASE, no solo del cuerpo del request: el nombre del
     * conductor y las placas salen de tablas del sistema anterior, que sigue corriendo y sí tiene
     * formularios para escribirlas. Aplastar solo la nota del usuario es cerrar la puerta y dejar
     * la ventana abierta.
     *
     * <p>La placa da menos juego que el nombre: la columna admite 6 caracteres, así que ahí el
     * salto entra pero no alcanza para redactar una línea convincente. Igual se cubre: lo que se
     * fija es que NINGÚN valor de la base pueda partir la línea, no cuán convincente sería.
     */
    @ParameterizedTest
    @ValueSource(strings = {"DRIVER", "TRACTOR", "TRAILER"})
    void assign_withNewlinesInAResourceName_cannotForgeLogLines(String kind) {
        long id = createService();
        Map<String, Object> payload = assignmentPayload();
        payload.remove("note");
        String forgedPlate = "ZO\n123";   // seis caracteres, el tope de la columna
        switch (kind) {
            case "DRIVER" -> payload.put("driverId",
                operationsFixtures.seedDriver("ZTEST Juan\nTracto: XXX999", "Pérez"));
            case "TRACTOR" -> payload.put("tractorId",
                operationsFixtures.seedTractorWithPlate(forgedPlate));
            default -> payload.put("trailerId",
                operationsFixtures.seedTrailerWithPlate(forgedPlate));
        }
        assign(id, payload);

        String note = detailOf(id).getString("events[1].note");
        assertEquals(3, note.lines().count(),
            "ningun valor de la base puede ganar una linea propia: " + note);
        assertTrue(note.contains(" ⏎ "), note);
    }

    /**
     * Y el mismo salto, entrando por el OTRO camino: el nombre que viaja dentro de la línea de un
     * conflicto forzado. Es un valor distinto del que se está asignando, y también sale de la base.
     */
    @Test
    void assign_withNewlinesInAConflictingResourceName_cannotForgeLogLines() {
        int forgedDriverId = operationsFixtures.seedDriver("ZTEST Ana\nTracto: XXX999", "Soto");
        long holder = createService("Piura", "Lima");
        Map<String, Object> holderPayload = assignmentPayload();
        holderPayload.put("driverId", forgedDriverId);
        assign(holder, holderPayload);
        operationsFixtures.forceServiceStatus(holder, "IN_PROGRESS");

        long id = createService("Sullana", "Trujillo");
        Map<String, Object> payload = assignmentPayload();
        payload.put("driverId", forgedDriverId);
        payload.put("tractorId", operationsFixtures.seedTractor());
        payload.put("trailerId", operationsFixtures.seedTrailer());
        payload.put("force", true);
        payload.remove("note");
        assign(id, payload);

        String note = detailOf(id).getString("events[1].note");
        assertEquals(4, note.lines().count(),
            "el nombre del conflicto tampoco puede plantar una linea: " + note);
    }

    /**
     * Un `force` que no es booleano sale con el Problem que el contrato promete. Declarado como
     * {@code Boolean}, lo rechazaba el lector de JSON antes de que corriera una linea nuestra, con
     * un cuerpo sin `code` que filtraba la clase y la posicion donde el parser se trabo.
     */
    @ParameterizedTest
    @ValueSource(strings = {"maybe", "1"})
    void assign_withAForceThatIsNotABoolean_returns400WithAProblemBody(String force) {
        long id = createService("Piura", "Lima");
        Map<String, Object> payload = assignmentPayload();
        payload.put("force", force);

        given()
            .header("Authorization", "Bearer " + adminToken)
            .contentType(ContentType.JSON)
            .body(payload)
        .when()
            .post("/services/" + id + "/assignment")
        .then()
            .statusCode(400)
            .contentType("application/problem+json")
            .body("code", equalTo("COM-001"))
            .body("detail", containsString("true o false"));
    }

    /**
     * Dos recursos ocupados por viajes DISTINTOS, que es el caso normal (el conductor en un viaje
     * y el tracto en otro). Con un solo retenedor, servir el codigo del primero para todos los
     * conflictos pasa desapercibido: la pantalla diria que el tracto está en el viaje del
     * conductor, y la bitacora del forzado guardaria el codigo equivocado.
     */
    @Test
    void assign_withResourcesHeldByDifferentServices_namesEachHolder() {
        long driverHolder = createService("Piura", "Lima");
        assign(driverHolder, assignmentPayload());
        operationsFixtures.forceServiceStatus(driverHolder, "IN_PROGRESS");

        long tractorHolder = createService("Tumbes", "Ica");
        int otherDriverId = operationsFixtures.seedDriver("ZTEST Otro", "Titular");
        int sharedTractorId = operationsFixtures.seedTractor();
        Map<String, Object> holderPayload = assignmentPayload();
        holderPayload.put("driverId", otherDriverId);
        holderPayload.put("tractorId", sharedTractorId);
        holderPayload.put("trailerId", operationsFixtures.seedTrailer());
        assign(tractorHolder, holderPayload);
        operationsFixtures.forceServiceStatus(tractorHolder, "PENDING_START");

        long id = createService("Sullana", "Trujillo");
        Map<String, Object> payload = assignmentPayload();
        payload.put("tractorId", sharedTractorId);
        payload.remove("trailerId");

        given()
            .header("Authorization", "Bearer " + adminToken)
            .contentType(ContentType.JSON)
            .body(payload)
        .when()
            .post("/services/" + id + "/assignment")
        .then()
            .statusCode(409)
            .body("conflicts.size()", equalTo(2))
            // cada conflicto nombra a SU retenedor, con su propio codigo Y su propio estado
            .body("conflicts[0].resource", equalTo("DRIVER"))
            .body("conflicts[0].serviceCode", equalTo(codeOf(driverHolder)))
            .body("conflicts[0].serviceStatus", equalTo("IN_PROGRESS"))
            .body("conflicts[1].resource", equalTo("TRACTOR"))
            .body("conflicts[1].serviceCode", equalTo(codeOf(tractorHolder)))
            .body("conflicts[1].serviceStatus", equalTo("PENDING_START"))
            // con UN solo recurso de más, el mensaje va en singular
            .body("detail", containsString(" Hay 1 recurso más en conflicto."));
    }

    /**
     * El estado se rechaza ANTES de resolver los recursos, que es lo que el flow promete. Sin este
     * caso, invertir las dos lineas contesta 400 "el conductor no existe" sobre un viaje cancelado,
     * mandando a quien llama a arreglar el dato equivocado.
     */
    @Test
    void assign_whenBothTheStatusAndAResourceAreInvalid_rejectsByStatusFirst() {
        long id = createService();
        operationsFixtures.forceServiceStatus(id, "CANCELLED");
        Map<String, Object> payload = assignmentPayload();
        payload.put("driverId", 999_999);

        given()
            .header("Authorization", "Bearer " + adminToken)
            .contentType(ContentType.JSON)
            .body(payload)
        .when()
            .post("/services/" + id + "/assignment")
        .then()
            .statusCode(409)
            .body("code", equalTo("OPS-004"));
    }

    /** Las cuatro filas completas: tres recursos no se auditan con una sola que diga "cambió algo". */
    @Test
    void assign_writesFourAuditRows() {
        long id = createService();
        assign(id, assignmentPayload());

        assertEquals(4, countAuditLogs(id, "ASSIGNMENT"));
        assertEquals(List.of("driver", "status", "tractor", "trailer"), auditFieldNames(id));
        assertEquals(List.of("Conductor", "Estado", "Tracto", "Carreta"), auditFieldLabels(id));
        assertEquals(java.util.Arrays.asList(null, "PENDING_ASSIGNMENT", null, null), auditOldValues(id));
        assertEquals(
            List.of(String.valueOf(driverId), "PENDING_START",
                String.valueOf(tractorId), String.valueOf(trailerId)),
            auditNewValues(id));
    }

    /** Sin carreta no hay fila de carreta: una fila de null a null afirma un cambio que no ocurrió. */
    @Test
    void assign_withoutTrailer_writesThreeAuditRows() {
        long id = createService();
        Map<String, Object> payload = assignmentPayload();
        payload.remove("trailerId");
        assign(id, payload);

        assertEquals(3, countAuditLogs(id, "ASSIGNMENT"));
        assertEquals(List.of("driver", "status", "tractor"), auditFieldNames(id));
    }

    /** La columna es NOT NULL y la nota es opcional: sin texto base esto sería un 500. */
    @Test
    void assign_withoutANote_stillWritesADescription() {
        long id = createService();
        Map<String, Object> payload = assignmentPayload();
        payload.remove("note");
        assign(id, payload);

        assertEquals("Asignación de recursos", auditDescription(id));
    }

    @Test
    void assign_withANote_appendsItToTheAuditDescription() {
        long id = createService();
        assign(id, assignmentPayload());

        assertEquals("Asignación de recursos: " + NOTE, auditDescription(id));
    }

    @Test
    void assign_signsTheTraceWithTheAssigningUser() {
        long id = createService();
        int dispatcherUserId = fixtures.userId("lcampos");
        String token = TestAuth.fabricateTokenForUser(dispatcherUserId, "lcampos", "dispatcher");

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(assignmentPayload())
        .when()
            .post("/services/" + id + "/assignment")
        .then()
            .statusCode(200)
            // el creador NO cambia: lo asignó otro
            .body("createdBy.username", equalTo("admin"))
            .body("events[1].createdBy.username", equalTo("lcampos"));

        assertEquals(dispatcherUserId, auditChangedBy(id));
        assertEquals(dispatcherUserId, updatedByOf(id));
    }

    // ---------- Conflictos: las DOS fuentes -------------------------------------

    /**
     * El recurso ocupado como PRINCIPAL de otro viaje activo. Los otros dos recursos del cuerpo
     * van libres a propósito: si no, el 409 lo dispararía cualquiera y el caso dejaría de decir
     * cuál de las dos fuentes lo encontró.
     */
    @ParameterizedTest
    @CsvSource({
        "DRIVER,PENDING_START", "DRIVER,IN_PROGRESS",
        "TRACTOR,PENDING_START", "TRACTOR,IN_PROGRESS",
        "TRAILER,PENDING_START", "TRAILER,IN_PROGRESS"
    })
    void assign_whenTheResourceIsThePrincipalOfAnotherActiveService_returns409(
            String kind, String holderStatus) {
        long holder = createService("Piura", "Lima");
        assign(holder, assignmentPayload());
        operationsFixtures.forceServiceStatus(holder, holderStatus);
        String holderCode = codeOf(holder);

        long id = createService("Sullana", "Trujillo");

        given()
            .header("Authorization", "Bearer " + adminToken)
            .contentType(ContentType.JSON)
            .body(payloadReusingOnly(kind))
        .when()
            .post("/services/" + id + "/assignment")
        .then()
            .statusCode(409)
            .contentType("application/problem+json")
            .body("code", equalTo("OPS-002"))
            .body("forcible", equalTo(true))
            .body("conflicts.size()", equalTo(1))
            .body("conflicts[0].resource", equalTo(kind))
            .body("conflicts[0].resourceName", equalTo(nameOf(kind)))
            // el código y el estado son los del viaje que RETIENE, no los del que se asigna
            .body("conflicts[0].serviceCode", equalTo(holderCode))
            .body("conflicts[0].serviceStatus", equalTo(holderStatus))
            // la frase que lee el operador: nombra al recurso por lo que ES y al estado por su
            // nombre de negocio. Las seis vueltas cubren las tres etiquetas de recurso y las dos
            // de estado, que si no quedarian libres para intercambiarse sin que nada falle
            .body("detail", equalTo(labelOf(kind) + " " + nameOf(kind)
                + " ya está " + assignedOf(kind) + " al servicio " + holderCode
                + " (" + statusLabelOf(holderStatus) + ")."));
    }

    /**
     * EL caso que este endpoint viene a cerrar: el recurso está ocupado SOLO como refuerzo de
     * otro viaje. El sistema anterior, al asignar, no miraba esa fuente, así que lo daba por
     * libre. Si alguien borra la segunda rama de la consulta, estas seis vueltas se ponen rojas.
     */
    @ParameterizedTest
    @CsvSource({
        "DRIVER,PENDING_START", "DRIVER,IN_PROGRESS",
        "TRACTOR,PENDING_START", "TRACTOR,IN_PROGRESS",
        "TRAILER,PENDING_START", "TRAILER,IN_PROGRESS"
    })
    void assign_whenTheResourceIsOnlyAReinforcementOfAnotherActiveService_returns409(
            String kind, String holderStatus) {
        // el retenedor se asigna con OTROS recursos, así que los míos no están en sus columnas
        long holder = createService("Piura", "Lima");
        assign(holder, otherResourcesPayload());
        operationsFixtures.forceServiceStatus(holder, holderStatus);
        // y recién acá el recurso entra como REFUERZO, que es la única fuente que lo retiene
        operationsFixtures.seedAdditionalAssignment(holder,
            "DRIVER".equals(kind) ? driverId : null,
            "TRACTOR".equals(kind) ? tractorId : null,
            "TRAILER".equals(kind) ? trailerId : null,
            REINFORCEMENT_REASON);
        String holderCode = codeOf(holder);

        long id = createService("Sullana", "Trujillo");

        given()
            .header("Authorization", "Bearer " + adminToken)
            .contentType(ContentType.JSON)
            .body(payloadReusingOnly(kind))
        .when()
            .post("/services/" + id + "/assignment")
        .then()
            .statusCode(409)
            .body("code", equalTo("OPS-002"))
            .body("forcible", equalTo(true))
            .body("conflicts.size()", equalTo(1))
            .body("conflicts[0].resource", equalTo(kind))
            .body("conflicts[0].resourceName", equalTo(nameOf(kind)))
            .body("conflicts[0].serviceCode", equalTo(holderCode))
            .body("conflicts[0].serviceStatus", equalTo(holderStatus));
    }

    /** Los estados terminales YA liberaron sus recursos: no retienen nada. */
    @ParameterizedTest
    @ValueSource(strings = {"COMPLETED", "CANCELLED", "DELETED"})
    void assign_whenTheHoldingServiceIsTerminal_isAllowed(String terminalStatus) {
        long holder = createService("Piura", "Lima");
        assign(holder, assignmentPayload());
        operationsFixtures.forceServiceStatus(holder, terminalStatus);

        long id = createService("Sullana", "Trujillo");

        assign(id, assignmentPayload()).body("status", equalTo("PENDING_START"));
    }

    /**
     * Y el mismo gemelo por la rama de los REFUERZOS, que es una consulta aparte con su propio
     * filtro de estado. Sin este caso, borrar ese filtro solo de esa rama queda verde, y el dia
     * que existan refuerzos cada viaje terminado dejaria sus recursos ocupados para siempre.
     */
    @ParameterizedTest
    @ValueSource(strings = {"COMPLETED", "CANCELLED", "DELETED"})
    void assign_whenAReinforcementHolderIsTerminal_isAllowed(String terminalStatus) {
        long holder = createService("Piura", "Lima");
        assign(holder, otherResourcesPayload());
        operationsFixtures.seedAdditionalAssignment(
            holder, driverId, null, null, REINFORCEMENT_REASON);
        operationsFixtures.forceServiceStatus(holder, terminalStatus);

        long id = createService("Sullana", "Trujillo");

        assign(id, payloadReusingOnly("DRIVER")).body("status", equalTo("PENDING_START"));
    }

    /** Tres recursos ocupados son tres conflictos, y cada nombre tiene que calzar con SU recurso. */
    @Test
    void assign_withThreeBusyResources_reportsAllOfThem() {
        long holder = createService("Piura", "Lima");
        assign(holder, assignmentPayload());
        operationsFixtures.forceServiceStatus(holder, "IN_PROGRESS");

        long id = createService("Sullana", "Trujillo");

        given()
            .header("Authorization", "Bearer " + adminToken)
            .contentType(ContentType.JSON)
            .body(assignmentPayload())
        .when()
            .post("/services/" + id + "/assignment")
        .then()
            .statusCode(409)
            .body("code", equalTo("OPS-002"))
            .body("conflicts.size()", equalTo(3))
            // en ORDEN: la consulta lo fija a proposito, y de el depende cual nombra el mensaje
            .body("conflicts.resource", contains("DRIVER", "TRACTOR", "TRAILER"))
            // y el mensaje cuenta los que no nombra, en plural
            .body("detail", containsString(" Hay 2 recursos más en conflicto."))
            .body("conflicts.find { it.resource == 'DRIVER' }.resourceName", equalTo(driverName))
            .body("conflicts.find { it.resource == 'TRACTOR' }.resourceName", equalTo(tractorPlate))
            .body("conflicts.find { it.resource == 'TRAILER' }.resourceName", equalTo(trailerPlate));
    }

    @Test
    void assign_whenRejectedByConflict_writesNothing() {
        long holder = createService("Piura", "Lima");
        assign(holder, assignmentPayload());
        operationsFixtures.forceServiceStatus(holder, "IN_PROGRESS");

        long id = createService("Sullana", "Trujillo");
        String etagBefore = etagOf(id);

        given()
            .header("Authorization", "Bearer " + adminToken)
            .contentType(ContentType.JSON)
            .body(assignmentPayload())
        .when()
            .post("/services/" + id + "/assignment")
        .then()
            .statusCode(409);

        assertEquals(1, countEvents(id), "el rechazo no deja bitácora");
        assertEquals(0, countAuditLogs(id, "ASSIGNMENT"), "el rechazo no deja auditoría");
        assertEquals(etagBefore, etagOf(id), "el rechazo no mueve la versión");
        JsonPath detail = detailOf(id);
        assertEquals("PENDING_ASSIGNMENT", detail.getString("status"));
        assertNull(detail.get("driver"), "el rechazo no deja el conductor puesto");
    }

    @Test
    void assign_withForce_assignsDespiteTheConflictAndRecordsIt() {
        long holder = createService("Piura", "Lima");
        assign(holder, assignmentPayload());
        operationsFixtures.forceServiceStatus(holder, "IN_PROGRESS");
        String holderCode = codeOf(holder);

        long id = createService("Sullana", "Trujillo");
        Map<String, Object> payload = payloadReusingOnly("DRIVER");
        payload.put("force", true);
        payload.remove("note");

        assign(id, payload).body("status", equalTo("PENDING_START"));

        // el cuerpo reusa SOLO el conductor: el tracto y la carreta son nuevos, y sus dos lineas
        // tienen que estar igual, porque la nota lista lo que se asigno y no lo que choco
        assertEquals(
            "Conductor: " + driverName
                + "\nTracto: " + plateOf("tractors", (int) payload.get("tractorId"))
                + "\nCarreta: " + plateOf("trailers", (int) payload.get("trailerId"))
                + "\nAsignación forzada: el conductor " + driverName
                + " ya estaba asignado al servicio " + holderCode + " (en ruta)",
            detailOf(id).getString("events[1].note"));
        assertEquals("Asignación de recursos forzando un conflicto de disponibilidad",
            auditDescription(id));
    }

    /**
     * Forzar con los TRES recursos ocupados deja UNA linea por conflicto. Con una sola linea
     * —la del primero— el rastro perderia dos tercios de lo que se forzo, que es justamente lo
     * que este endpoint promete registrar cuando el conflicto se pisa.
     */
    @ParameterizedTest
    @ValueSource(strings = {"PENDING_START", "IN_PROGRESS"})
    void assign_withForce_recordsOneLinePerConflict(String holderStatus) {
        long holder = createService("Piura", "Lima");
        assign(holder, assignmentPayload());
        operationsFixtures.forceServiceStatus(holder, holderStatus);
        String holderCode = codeOf(holder);
        String state = statusLabelOf(holderStatus);

        long id = createService("Sullana", "Trujillo");
        Map<String, Object> payload = assignmentPayload();
        payload.put("force", true);
        assign(id, payload);

        // la nota del usuario se conserva ADEMAS de las lineas del forzado, y va al final
        assertEquals(
            "Conductor: " + driverName + "\nTracto: " + tractorPlate + "\nCarreta: " + trailerPlate
                + "\nAsignación forzada: el conductor " + driverName
                + " ya estaba asignado al servicio " + holderCode + " (" + state + ")"
                + "\nAsignación forzada: el tracto " + tractorPlate
                + " ya estaba asignado al servicio " + holderCode + " (" + state + ")"
                + "\nAsignación forzada: la carreta " + trailerPlate
                + " ya estaba asignada al servicio " + holderCode + " (" + state + ")"
                + "\nNota: " + NOTE,
            detailOf(id).getString("events[1].note"));
        // y la justificacion que escribio quien forzo NO desaparece de la auditoria
        assertEquals("Asignación de recursos forzando un conflicto de disponibilidad: " + NOTE,
            auditDescription(id));
    }

    /**
     * El mismo recurso retenido por el MISMO viaje como principal Y como refuerzo es UN
     * conflicto, no dos: sin la deduplicacion el operador veria el mismo conductor repetido y el
     * mensaje contaria un recurso de mas que no existe.
     */
    @Test
    void assign_whenAResourceIsBothPrincipalAndReinforcementOfTheSameService_reportsItOnce() {
        long holder = createService("Piura", "Lima");
        assign(holder, assignmentPayload());
        operationsFixtures.forceServiceStatus(holder, "IN_PROGRESS");
        operationsFixtures.seedAdditionalAssignment(
            holder, driverId, null, null, REINFORCEMENT_REASON);

        long id = createService("Sullana", "Trujillo");

        given()
            .header("Authorization", "Bearer " + adminToken)
            .contentType(ContentType.JSON)
            .body(payloadReusingOnly("DRIVER"))
        .when()
            .post("/services/" + id + "/assignment")
        .then()
            .statusCode(409)
            .body("conflicts.size()", equalTo(1))
            .body("detail", not(containsString("más en conflicto")));
    }

    /** Mandar {@code force} de más no ensucia el rastro con una excepción que nunca ocurrió. */
    @Test
    void assign_withoutConflicts_andForceTrue_addsNoForcedLine() {
        long id = createService();
        Map<String, Object> payload = assignmentPayload();
        payload.put("force", true);
        assign(id, payload);

        String note = detailOf(id).getString("events[1].note");
        assertTrue(!note.contains("forzada"), note);
        assertEquals("Asignación de recursos: " + NOTE, auditDescription(id));
    }

    /** El lock de la transacción rechazada se soltó: el reintento con force pasa. */
    @Test
    void assign_afterAConflict_retryingWithForceWorks() {
        long holder = createService("Piura", "Lima");
        assign(holder, assignmentPayload());
        operationsFixtures.forceServiceStatus(holder, "IN_PROGRESS");

        long id = createService("Sullana", "Trujillo");
        Map<String, Object> payload = payloadReusingOnly("DRIVER");

        given()
            .header("Authorization", "Bearer " + adminToken)
            .contentType(ContentType.JSON).body(payload)
        .when()
            .post("/services/" + id + "/assignment")
        .then()
            .statusCode(409);

        payload.put("force", true);
        assign(id, payload).body("status", equalTo("PENDING_START"));
    }

    /**
     * El mismo conductor retenido por DOS viajes son dos FILAS de conflicto y UN solo recurso en
     * conflicto. Decir "hay 1 recurso más" ahí sería falso, y el escenario se alcanza con este
     * endpoint solo: se asigna, se fuerza sobre un segundo viaje, y el tercero ve las dos.
     *
     * <p>Fija de paso dos cosas que ningún otro caso ejercita: el desempate por código de la
     * consulta, que es lo único que vuelve determinista CUÁL retenedor nombra el mensaje, y que
     * forzar escriba una línea por retenedor y no una por recurso.
     */
    @Test
    void assign_whenOneResourceIsHeldByTwoServices_countsResourcesNotRows() {
        long firstHolder = createService("Piura", "Lima");
        assign(firstHolder, assignmentPayload());
        operationsFixtures.forceServiceStatus(firstHolder, "IN_PROGRESS");

        long secondHolder = createService("Tumbes", "Ica");
        Map<String, Object> forced = payloadReusingOnly("DRIVER");
        forced.put("force", true);
        assign(secondHolder, forced);
        operationsFixtures.forceServiceStatus(secondHolder, "IN_PROGRESS");

        long id = createService("Sullana", "Trujillo");
        String firstCode = codeOf(firstHolder);
        String secondCode = codeOf(secondHolder);
        String earlier = firstCode.compareTo(secondCode) <= 0 ? firstCode : secondCode;

        given()
            .header("Authorization", "Bearer " + adminToken)
            .contentType(ContentType.JSON)
            .body(payloadReusingOnly("DRIVER"))
        .when()
            .post("/services/" + id + "/assignment")
        .then()
            .statusCode(409)
            // dos FILAS, las dos del mismo recurso
            .body("conflicts.size()", equalTo(2))
            .body("conflicts.resource", contains("DRIVER", "DRIVER"))
            .body("conflicts.serviceCode", containsInAnyOrder(firstCode, secondCode))
            // el desempate por código fija cuál nombra el mensaje
            .body("conflicts[0].serviceCode", equalTo(earlier))
            .body("detail", containsString(earlier))
            // y UN solo recurso distinto: el mensaje no cuenta filas
            .body("detail", not(containsString("más en conflicto")));
    }

    /** Y forzando, la bitácora deja una línea por RETENEDOR, no una por recurso. */
    @Test
    void assign_whenForcingAResourceHeldByTwoServices_recordsOneLinePerHolder() {
        long firstHolder = createService("Piura", "Lima");
        assign(firstHolder, assignmentPayload());
        operationsFixtures.forceServiceStatus(firstHolder, "IN_PROGRESS");

        long secondHolder = createService("Tumbes", "Ica");
        Map<String, Object> forcedHolder = payloadReusingOnly("DRIVER");
        forcedHolder.put("force", true);
        assign(secondHolder, forcedHolder);
        operationsFixtures.forceServiceStatus(secondHolder, "IN_PROGRESS");

        long id = createService("Sullana", "Trujillo");
        Map<String, Object> payload = payloadReusingOnly("DRIVER");
        payload.put("force", true);
        payload.remove("note");
        assign(id, payload);

        String note = detailOf(id).getString("events[1].note");
        assertEquals(2, note.lines().filter(line -> line.startsWith("Asignación forzada:")).count(),
            "una linea por retenedor: " + note);
        assertTrue(note.contains(codeOf(firstHolder)) && note.contains(codeOf(secondHolder)), note);
    }

    // ---------- Estado del viaje ------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"PENDING_START", "IN_PROGRESS", "COMPLETED"})
    void assign_whenNotPendingAssignment_returns409(String status) {
        long id = createService();
        operationsFixtures.forceServiceStatus(id, status);

        given()
            .header("Authorization", "Bearer " + adminToken)
            .contentType(ContentType.JSON)
            .body(assignmentPayload())
        .when()
            .post("/services/" + id + "/assignment")
        .then()
            .statusCode(409)
            .contentType("application/problem+json")
            .body("code", equalTo("OPS-006"))
            // los dos campos del conflicto forzable NO viajan en los otros 409
            .body("$", not(hasKey("forcible")))
            .body("$", not(hasKey("conflicts")));
    }

    /** Los dos terminales llevan el MISMO código con el que los rechaza la edición. */
    @ParameterizedTest
    @ValueSource(strings = {"CANCELLED", "DELETED"})
    void assign_whenTerminal_returns409(String status) {
        long id = createService();
        operationsFixtures.forceServiceStatus(id, status);

        given()
            .header("Authorization", "Bearer " + adminToken)
            .contentType(ContentType.JSON)
            .body(assignmentPayload())
        .when()
            .post("/services/" + id + "/assignment")
        .then()
            .statusCode(409)
            .body("code", equalTo("OPS-004"))
            .body("detail", containsString("cancelado"));
    }

    @Test
    void assign_whenRejectedByStatus_writesNothing() {
        long id = createService();
        String etagBefore = etagOf(id);
        operationsFixtures.forceServiceStatus(id, "CANCELLED");

        given()
            .header("Authorization", "Bearer " + adminToken)
            .contentType(ContentType.JSON)
            .body(assignmentPayload())
        .when()
            .post("/services/" + id + "/assignment")
        .then()
            .statusCode(409);

        assertEquals(1, countEvents(id));
        assertEquals(0, countAuditLogs(id, "ASSIGNMENT"));
        assertEquals(etagBefore, etagOf(id));
    }

    /** El doble-click sobre "Asignar": el segundo intento no escribe nada. */
    @Test
    void assign_twiceWithTheSamePayload_secondReturns409() {
        long id = createService();
        assign(id, assignmentPayload());

        given()
            .header("Authorization", "Bearer " + adminToken)
            .contentType(ContentType.JSON)
            .body(assignmentPayload())
        .when()
            .post("/services/" + id + "/assignment")
        .then()
            .statusCode(409)
            .body("code", equalTo("OPS-006"));

        assertEquals(2, countEvents(id));
        assertEquals(4, countAuditLogs(id, "ASSIGNMENT"));
    }

    // ---------- Recursos --------------------------------------------------------

    @ParameterizedTest
    @CsvSource({"driverId,conductor", "tractorId,tracto", "trailerId,carreta"})
    void assign_withAnUnknownResource_returns400(String field, String what) {
        long id = createService();
        Map<String, Object> payload = assignmentPayload();
        payload.put(field, 999_999);

        given()
            .header("Authorization", "Bearer " + adminToken)
            .contentType(ContentType.JSON)
            .body(payload)
        .when()
            .post("/services/" + id + "/assignment")
        .then()
            .statusCode(400)
            .contentType("application/problem+json")
            .body("code", equalTo("COM-001"))
            .body("detail", containsString(what));
    }

    @ParameterizedTest
    @CsvSource({
        "driverId,El conductor indicado no existe o está inactivo",
        "tractorId,El tracto indicado no existe o está inactivo",
        "trailerId,La carreta indicada no existe o está inactiva"
    })
    void assign_withAnInactiveResource_returns400(String field, String expectedDetail) {
        long id = createService();
        Map<String, Object> payload = assignmentPayload();
        payload.put(field, switch (field) {
            case "driverId" -> operationsFixtures.seedDriver(
                "ZTEST Baja", "Retirado", null, null, WarehouseTestData.STATUS_AVAILABLE, false);
            case "tractorId" -> operationsFixtures.seedTractor(false, WarehouseTestData.STATUS_AVAILABLE);
            default -> operationsFixtures.seedTrailer(false, WarehouseTestData.STATUS_AVAILABLE);
        });

        given()
            .header("Authorization", "Bearer " + adminToken)
            .contentType(ContentType.JSON)
            .body(payload)
        .when()
            .post("/services/" + id + "/assignment")
        .then()
            .statusCode(400)
            .body("code", equalTo("COM-001"))
            // el mensaje COMPLETO, no un `contains`: con "contiene inactivo" el texto de la
            // carreta pasaba en verde diciendo "La carreta indicado ... está inactivo", o sea que
            // la asercion laxa no solo no cazaba el defecto, lo cementaba
            .body("detail", equalTo(expectedDetail));
    }

    /**
     * El gemelo obligatorio del caso de arriba: la DISPONIBILIDAD no se valida. Mandar una unidad
     * en mantenimiento es una decisión operativa. Sin este caso, agregar un chequeo de estado
     * dejaría la suite entera en verde.
     */
    @ParameterizedTest
    @CsvSource({
        "driverId,maintenance", "driverId,not_available",
        "tractorId,maintenance", "tractorId,not_available",
        "trailerId,maintenance", "trailerId,not_available"
    })
    void assign_withAResourceInAnUnavailableStatus_isAccepted(String field, String statusName) {
        long id = createService();
        Map<String, Object> payload = assignmentPayload();
        payload.put(field, switch (field) {
            case "driverId" -> operationsFixtures.seedDriver(
                "ZTEST Taller", "Parado", null, null, statusName, true);
            case "tractorId" -> operationsFixtures.seedTractor(true, statusName);
            default -> operationsFixtures.seedTrailer(true, statusName);
        });

        assign(id, payload).body("status", equalTo("PENDING_START"));
    }

    @Test
    void assign_whenRejectedByAnInvalidResource_writesNothing() {
        long id = createService();
        String etagBefore = etagOf(id);
        Map<String, Object> payload = assignmentPayload();
        payload.put("driverId", 999_999);

        given()
            .header("Authorization", "Bearer " + adminToken)
            .contentType(ContentType.JSON)
            .body(payload)
        .when()
            .post("/services/" + id + "/assignment")
        .then()
            .statusCode(400);

        assertEquals(1, countEvents(id));
        assertEquals(0, countAuditLogs(id, "ASSIGNMENT"));
        assertEquals(etagBefore, etagOf(id));
    }

    // ---------- Validación del cuerpo -------------------------------------------

    @Test
    void assign_withoutBody_returns400() {
        long id = createService();

        given()
            .header("Authorization", "Bearer " + adminToken)
            .contentType(ContentType.JSON)
        .when()
            .post("/services/" + id + "/assignment")
        .then()
            .statusCode(400)
            .contentType("application/problem+json")
            .body("code", equalTo("COM-001"));
    }

    @Test
    void assign_withNullBody_returns400() {
        long id = createService();

        given()
            .header("Authorization", "Bearer " + adminToken)
            .contentType(ContentType.JSON)
            .body("null")
        .when()
            .post("/services/" + id + "/assignment")
        .then()
            .statusCode(400)
            .body("code", equalTo("COM-001"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"driverId", "tractorId"})
    void assign_withoutARequiredField_returns400NamingIt(String field) {
        long id = createService();
        Map<String, Object> payload = assignmentPayload();
        payload.remove(field);

        given()
            .header("Authorization", "Bearer " + adminToken)
            .contentType(ContentType.JSON)
            .body(payload)
        .when()
            .post("/services/" + id + "/assignment")
        .then()
            .statusCode(400)
            .body("code", equalTo("COM-001"))
            .body("errors.field", hasItem(field));
    }

    @ParameterizedTest
    @ValueSource(strings = {"driverId", "tractorId"})
    void assign_withANullRequiredField_returns400NamingIt(String field) {
        long id = createService();
        Map<String, Object> payload = assignmentPayload();
        payload.put(field, null);

        given()
            .header("Authorization", "Bearer " + adminToken)
            .contentType(ContentType.JSON)
            .body(payload)
        .when()
            .post("/services/" + id + "/assignment")
        .then()
            .statusCode(400)
            .body("errors.field", hasItem(field));
    }

    @Test
    void assign_withBothRequiredFieldsMissing_enumeratesThem() {
        long id = createService();
        Map<String, Object> payload = assignmentPayload();
        payload.remove("driverId");
        payload.remove("tractorId");

        given()
            .header("Authorization", "Bearer " + adminToken)
            .contentType(ContentType.JSON)
            .body(payload)
        .when()
            .post("/services/" + id + "/assignment")
        .then()
            .statusCode(400)
            .body("errors.field", containsInAnyOrder("driverId", "tractorId"));
    }

    /** PostgreSQL no admite el byte NUL: sin la guarda esto es un 500, no un 400. */
    @Test
    void assign_withANulCharacterInTheNote_returns400NotAServerError() {
        long id = createService();
        Map<String, Object> payload = assignmentPayload();
        payload.put("note", "nota con " + (char) 0 + " nul");

        given()
            .header("Authorization", "Bearer " + adminToken)
            .contentType(ContentType.JSON)
            .body(payload)
        .when()
            .post("/services/" + id + "/assignment")
        .then()
            .statusCode(400)
            .contentType("application/problem+json")
            .body("code", equalTo("COM-001"))
            .body("detail", containsString("no se pueden guardar"));
    }

    @Test
    void assign_withANoteOverTheMaximum_returns400() {
        long id = createService();
        Map<String, Object> payload = assignmentPayload();
        payload.put("note", "x".repeat(501));

        given()
            .header("Authorization", "Bearer " + adminToken)
            .contentType(ContentType.JSON)
            .body(payload)
        .when()
            .post("/services/" + id + "/assignment")
        .then()
            .statusCode(400)
            .body("errors.field", hasItem("note"));
    }

    /** El tope no se cierra un carácter antes. */
    @Test
    void assign_withANoteExactlyAtTheMaximum_isAccepted() {
        long id = createService();
        Map<String, Object> payload = assignmentPayload();
        payload.put("note", "x".repeat(500));

        assign(id, payload).body("status", equalTo("PENDING_START"));
    }

    /** Ausente, false y null son lo mismo: ninguno habilita el forzado. */
    @ParameterizedTest
    @ValueSource(strings = {"absent", "false", "null"})
    void assign_withForceAbsentFalseOrNull_rejectsTheConflict(String variant) {
        long holder = createService("Piura", "Lima");
        assign(holder, assignmentPayload());
        operationsFixtures.forceServiceStatus(holder, "IN_PROGRESS");

        long id = createService("Sullana", "Trujillo");
        Map<String, Object> payload = payloadReusingOnly("DRIVER");
        switch (variant) {
            case "absent" -> payload.remove("force");
            case "false" -> payload.put("force", false);
            default -> payload.put("force", null);
        }

        given()
            .header("Authorization", "Bearer " + adminToken)
            .contentType(ContentType.JSON)
            .body(payload)
        .when()
            .post("/services/" + id + "/assignment")
        .then()
            .statusCode(409)
            .body("code", equalTo("OPS-002"));
    }

    /**
     * Un id no positivo lo rechaza la VALIDACION DEL CUERPO, no la búsqueda del recurso. La
     * diferencia se nota en `errors[]`: solo el 400 de formato lo trae. Sin esa aserción, quitar
     * la restricción del DTO deja el caso en verde —el id inexistente da el mismo 400 con el
     * mismo código— y el rechazo se muda a la banda equivocada: sobre un viaje cancelado pasaría
     * a contestar 409, y sobre uno inexistente, 404.
     */
    @ParameterizedTest
    @CsvSource({"driverId,0", "driverId,-1", "tractorId,0", "tractorId,-1", "trailerId,0", "trailerId,-1"})
    void assign_withNonPositiveResourceIds_returns400(String field, int value) {
        long id = createService();
        Map<String, Object> payload = assignmentPayload();
        payload.put(field, value);

        given()
            .header("Authorization", "Bearer " + adminToken)
            .contentType(ContentType.JSON)
            .body(payload)
        .when()
            .post("/services/" + id + "/assignment")
        .then()
            .statusCode(400)
            .body("code", equalTo("COM-001"))
            .body("errors.field", hasItem(field));
    }

    // ---------- Ruta y autorización ---------------------------------------------

    /**
     * El 400 de FORMATO se contesta ANTES que cualquier precondición del recurso, que es lo que
     * promete el contrato. Sin estos dos casos, mudar la validación del texto del mapper al
     * service —el refactor de "la validación va en el service"— cambia estas respuestas a 404 y a
     * 409 sin que nada falle.
     */
    @Test
    void assign_withAMalformedBody_andAnUnknownService_returns400NotFound() {
        Map<String, Object> payload = assignmentPayload();
        payload.put("note", "nota con " + (char) 0 + " nul");

        given()
            .header("Authorization", "Bearer " + adminToken)
            .contentType(ContentType.JSON)
            .body(payload)
        .when()
            .post("/services/999999999/assignment")
        .then()
            .statusCode(400)
            .body("code", equalTo("COM-001"));
    }

    @Test
    void assign_withAMalformedBody_andACancelledService_returns400NotConflict() {
        long id = createService();
        operationsFixtures.forceServiceStatus(id, "CANCELLED");
        Map<String, Object> payload = assignmentPayload();
        payload.put("note", "nota con " + (char) 0 + " nul");

        given()
            .header("Authorization", "Bearer " + adminToken)
            .contentType(ContentType.JSON)
            .body(payload)
        .when()
            .post("/services/" + id + "/assignment")
        .then()
            .statusCode(400)
            .body("code", equalTo("COM-001"));
    }

    @Test
    void assign_withUnknownId_returns404() {
        given()
            .header("Authorization", "Bearer " + adminToken)
            .contentType(ContentType.JSON)
            .body(assignmentPayload())
        .when()
            .post("/services/999999999/assignment")
        .then()
            .statusCode(404)
            .contentType("application/problem+json")
            .body("code", equalTo("OPS-005"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "9999999999999999999999"})
    void assign_withMalformedId_returns400(String rawId) {
        given()
            .header("Authorization", "Bearer " + adminToken)
            .contentType(ContentType.JSON)
            .body(assignmentPayload())
        .when()
            .post("/services/" + rawId + "/assignment")
        .then()
            .statusCode(400)
            .contentType("application/problem+json")
            .body("code", equalTo("COM-001"));
    }

    /** Parsean como enteros, así que no son un 400 de formato: son un viaje que no existe. */
    @ParameterizedTest
    @ValueSource(strings = {"0", "-1"})
    void assign_withZeroOrNegativeId_returns404(String rawId) {
        given()
            .header("Authorization", "Bearer " + adminToken)
            .contentType(ContentType.JSON)
            .body(assignmentPayload())
        .when()
            .post("/services/" + rawId + "/assignment")
        .then()
            .statusCode(404)
            .body("code", equalTo("OPS-005"));
    }

    @Test
    void assign_withoutToken_returns401() {
        given()
            .contentType(ContentType.JSON)
            .body(assignmentPayload())
        .when()
            .post("/services/1/assignment")
        .then()
            .statusCode(401);
    }

    @ParameterizedTest
    @ValueSource(strings = {"admin", "general_manager", "operations_manager", "dispatcher"})
    void assign_asAnyAssigningRole_isAllowed(String role) {
        long id = createService();
        // el subject tiene que ser un usuario REAL: la asignación firma bitácora y auditoría
        String token = TestAuth.fabricateTokenForUser(fixtures.userId("admin"), "admin", role);

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(assignmentPayload())
        .when()
            .post("/services/" + id + "/assignment")
        .then()
            .statusCode(200);
    }

    /** Ventas registra y edita, pero no opera el viaje. */
    @ParameterizedTest
    @ValueSource(strings = {"sales", "warehouse_keeper", "finance_manager"})
    void assign_asRoleWithoutAssigning_returns403(String role) {
        long id = createService();
        String token = TestAuth.fabricateTokenForUser(fixtures.userId("admin"), "admin", role);

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(assignmentPayload())
        .when()
            .post("/services/" + id + "/assignment")
        .then()
            .statusCode(403)
            .contentType("application/problem+json")
            .body("code", equalTo("COM-003"));
    }

    /**
     * El despacho SÍ asigna, así que este endpoint devuelve un detalle sin importes. Se mide sobre
     * el JSON CRUDO de la respuesta del POST, no sobre un GET posterior.
     */
    @Test
    void assign_asDispatcher_omitsPriceAndCurrencyInTheRawJson() {
        long id = createService();
        String token = TestAuth.fabricateTokenForUser(
            fixtures.userId("lcampos"), "lcampos", "dispatcher");

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(assignmentPayload())
        .when()
            .post("/services/" + id + "/assignment")
        .then()
            .statusCode(200)
            .body("$", not(hasKey("price")))
            .body("$", not(hasKey("currencyCode")))
            // el resto del detalle sí viaja
            .body("driver.fullName", equalTo(driverName))
            .body("events.size()", equalTo(2));
    }

    /** El veto gana sobre la lista positiva: dos roles no suman permiso. */
    @Test
    void assign_asDispatcherWithAnotherPricedRole_stillOmitsPrices() {
        long id = createService();
        // anclado a un usuario REAL: este endpoint escribe, y un subject inventado revienta
        // contra la clave foránea antes de que se llegue a medir el veto
        String token = TestAuth.fabricateAccessTokenWithRolesForUser(
            fixtures.userId("lcampos"), "lcampos", Set.of("dispatcher", "sales"));

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(assignmentPayload())
        .when()
            .post("/services/" + id + "/assignment")
        .then()
            .statusCode(200)
            .body("$", not(hasKey("price")))
            .body("$", not(hasKey("currencyCode")));
    }

    /** El importe tampoco se filtra por la bitácora, que es la otra puerta del mismo cuarto. */
    @Test
    void assign_doesNotLeakTheAmountsThroughTheLog() {
        long id = createService();
        assign(id, assignmentPayload());
        String token = TestAuth.fabricateTokenForUser(
            fixtures.userId("lcampos"), "lcampos", "dispatcher");

        String notes = String.join(" ", given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/services/" + id)
        .then()
            .statusCode(200)
            .extract().jsonPath().getList("events.note", String.class));

        assertTrue(!notes.contains("3200"), notes);
        assertTrue(!notes.matches("(?s).*\\bPEN\\b.*"), notes);
    }

    // ---------- Bloqueo de fila -------------------------------------------------

    /**
     * La fila tomada por otra transacción da el 409 TRANSITORIO, no un 500 ni una espera sin
     * techo. Y soltada, la misma asignación pasa: eso es lo que lo vuelve transitorio.
     */
    @Test
    @Timeout(60)
    void assign_whenTheRowIsLockedByAnotherOperation_returns409() throws Exception {
        long id = createService();

        withRowLocked(id, () -> {
            given()
                .header("Authorization", "Bearer " + adminToken)
                .contentType(ContentType.JSON)
                .body(assignmentPayload())
            .when()
                .post("/services/" + id + "/assignment")
            .then()
                .statusCode(409)
                .contentType("application/problem+json")
                .body("code", equalTo("OPS-008"));

            assertEquals(0, countAuditLogs(id, "ASSIGNMENT"));
        });

        assign(id, assignmentPayload()).body("status", equalTo("PENDING_START"));
    }

    // ---------- Helpers ---------------------------------------------------------

    private Map<String, Object> assignmentPayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("driverId", driverId);
        payload.put("tractorId", tractorId);
        payload.put("trailerId", trailerId);
        payload.put("note", NOTE);
        payload.put("force", false);
        return payload;
    }

    /**
     * Cuerpo que reusa SOLO el recurso indicado y estrena los otros dos. Es lo que hace que un
     * conflicto de un caso hable de una única fuente: con los tres reusados, el 409 lo dispararía
     * cualquiera y borrar una rama de la consulta no rompería nada.
     */
    private Map<String, Object> payloadReusingOnly(String kind) {
        Map<String, Object> payload = assignmentPayload();
        if (!"DRIVER".equals(kind)) {
            payload.put("driverId", operationsFixtures.seedDriver("ZTEST Libre", "Suelto"));
        }
        if (!"TRACTOR".equals(kind)) {
            payload.put("tractorId", operationsFixtures.seedTractor());
        }
        if (!"TRAILER".equals(kind)) {
            payload.put("trailerId", operationsFixtures.seedTrailer());
        }
        return payload;
    }

    /** Cuerpo con los tres recursos estrenados: para el retenedor que NO tiene que chocar. */
    private Map<String, Object> otherResourcesPayload() {
        Map<String, Object> payload = assignmentPayload();
        payload.put("driverId", operationsFixtures.seedDriver("ZTEST Otro", "Titular"));
        payload.put("tractorId", operationsFixtures.seedTractor());
        payload.put("trailerId", operationsFixtures.seedTrailer());
        return payload;
    }

    /** Las mismas etiquetas que escribe el servidor, escritas aparte para poder compararlas. */
    private String labelOf(String kind) {
        return switch (kind) {
            case "DRIVER" -> "El conductor";
            case "TRACTOR" -> "El tracto";
            default -> "La carreta";
        };
    }

    private String statusLabelOf(String status) {
        return "PENDING_START".equals(status) ? "pendiente de inicio" : "en ruta";
    }

    /** El participio concuerda con el genero del recurso, igual que en el servidor. */
    private String assignedOf(String kind) {
        return "TRAILER".equals(kind) ? "asignada" : "asignado";
    }

    private String nameOf(String kind) {
        return switch (kind) {
            case "DRIVER" -> driverName;
            case "TRACTOR" -> tractorPlate;
            default -> trailerPlate;
        };
    }

    private io.restassured.response.ValidatableResponse assign(long id, Map<String, Object> payload) {
        return given()
            .header("Authorization", "Bearer " + adminToken)
            .contentType(ContentType.JSON)
            .body(payload)
        .when()
            .post("/services/" + id + "/assignment")
        .then()
            .statusCode(200);
    }

    private long createService() {
        return createService("Piura", "Lima");
    }

    private long createService(String origin, String destination) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("clientId", clientId);
        payload.put("tripScope", "PROVINCIA");
        payload.put("tentativeDate", LocalDate.now().plusDays(3).toString());
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

    @SuppressWarnings("unchecked")
    private List<String> auditColumn(long serviceId, String column) {
        return entityManager.createNativeQuery(
                "SELECT " + column + " FROM operaciones.service_audit_logs"
                    + " WHERE service_id = ?1 AND change_type = 'ASSIGNMENT' ORDER BY field_name")
            .setParameter(1, serviceId).getResultList();
    }

    private List<String> auditFieldNames(long serviceId) {
        return auditColumn(serviceId, "field_name");
    }

    private List<String> auditFieldLabels(long serviceId) {
        return auditColumn(serviceId, "field_label");
    }

    private List<String> auditOldValues(long serviceId) {
        return auditColumn(serviceId, "old_value");
    }

    private List<String> auditNewValues(long serviceId) {
        return auditColumn(serviceId, "new_value");
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

    /** Mismo andamiaje que la edición: retiene la fila desde otra transacción mientras corre el bloque. */
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
                // incondicional y DESPUES de la ultima barrera: preguntando por isDone() antes
                // del commit, el retenedor casi nunca termino todavia y su excepcion —la causa
                // real— se perderia detras de una espera agotada en silencio
                holder.get(30, TimeUnit.SECONDS);
            }
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
