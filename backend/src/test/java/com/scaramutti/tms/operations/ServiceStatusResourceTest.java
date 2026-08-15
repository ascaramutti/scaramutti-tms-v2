package com.scaramutti.tms.operations;

import com.scaramutti.tms.operations.model.ServiceStatus;
import com.scaramutti.tms.operations.model.ServiceStatusTransition;
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
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integración de {@code POST /services/{id}/status}.
 *
 * <p>Lo que se fija acá, más allá de la forma: que la máquina de estados se cumpla en las
 * VEINTICUATRO celdas y no en las que a alguien se le ocurrieron; que el {@code If-Match} sea
 * condicional de verdad (obligatorio en las dos destructivas y en la reapertura, honrado pero opcional en las otras
 * dos); que el veto del despacho sea por TARGET y sobreviva a un token con dos roles; y que todo
 * rechazo no deje ni una fila de rastro.
 *
 * <p>La matriz se GENERA de los enums en vez de escribirse: un estado nuevo produce cuatro casos
 * nuevos que fallan hasta que alguien decida a dónde puede ir, en lugar de pasar inadvertido.
 */
@QuarkusTest
class ServiceStatusResourceTest {

    @Inject HermeticTestData fixtures;
    @Inject OperationsTestData operationsFixtures;
    @Inject WarehouseTestData warehouseFixtures;
    @Inject EntityManager entityManager;

    private int clientId;
    private int cargoTypeId;
    private int currencyId;
    private String adminToken;
    private int seededDriverId;
    private int seededTractorId;

    private static final String VALID_REASON = "El cliente reprogramó el embarque para agosto";

    @BeforeEach
    void setUp() {
        clientId = fixtures.seedClient();
        cargoTypeId = fixtures.seedCargoType();
        currencyId = fixtures.currencyId("PEN");
        adminToken = TestAuth.adminToken();
        seededDriverId = operationsFixtures.seedDriver("ZTEST Juan", "Pérez");
        seededTractorId = operationsFixtures.seedTractor();
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

    // ---------- La matriz de estados --------------------------------------------

    /** Los destinos legales por estado, escritos desde RN-OP1 y APARTE de la tabla del código. */
    private static final Map<ServiceStatus, Set<ServiceStatus>> LEGAL_DESTINATIONS =
        new EnumMap<>(Map.of(
            ServiceStatus.PENDING_ASSIGNMENT, EnumSet.of(
                ServiceStatus.PENDING_START, ServiceStatus.CANCELLED, ServiceStatus.DELETED),
            ServiceStatus.PENDING_START, EnumSet.of(
                ServiceStatus.IN_PROGRESS, ServiceStatus.CANCELLED, ServiceStatus.DELETED),
            ServiceStatus.IN_PROGRESS, EnumSet.of(
                ServiceStatus.COMPLETED, ServiceStatus.CANCELLED),
            ServiceStatus.COMPLETED, EnumSet.noneOf(ServiceStatus.class),
            ServiceStatus.CANCELLED, EnumSet.noneOf(ServiceStatus.class),
            ServiceStatus.DELETED, EnumSet.noneOf(ServiceStatus.class)));

    /** Los dos estados que ya no admiten nada: contestan OPS-004 y no llegan a la máquina. */
    private static final Set<ServiceStatus> IMMUTABLE =
        EnumSet.of(ServiceStatus.CANCELLED, ServiceStatus.DELETED);

    /**
     * Los 24 pares de la MAQUINA. La reapertura queda afuera y no es un olvido: su destino sale del
     * rastro del viaje, no de la tabla de arcos, así que preguntarle a la matriz por ella no tiene
     * sentido. Tiene su propio bloque de casos, arriba.
     */
    static Stream<Arguments> everyOriginAndTarget() {
        return Arrays.stream(ServiceStatus.values()).flatMap(from ->
            Arrays.stream(ServiceStatusTransition.values())
                .filter(t -> !t.restoresPreviousStatus())
                .map(t -> Arguments.of(from, t)));
    }

    /**
     * Las 24 celdas contra HTTP. La unit de la máquina no alcanza: un resource que se saltee la
     * tabla la deja verde igual, porque ahí nadie mira el cableado.
     *
     * <p>Cada celda rechazada trae de arriba su verificación de no-escritura, que es la parte que
     * más valor dio en los dos PRs anteriores: 17 comprobaciones de rastro sin escribir 17 tests.
     */
    @ParameterizedTest(name = "{0} → {1}")
    @MethodSource("everyOriginAndTarget")
    void changeStatus_overTheWholeMatrix_honorsTheStateMachine(
            ServiceStatus from, ServiceStatusTransition transition) {
        Set<ServiceStatus> legal = LEGAL_DESTINATIONS.get(from);
        if (legal == null) {
            throw new AssertionError("Estado nuevo sin fila en la matriz esperada: " + from
                + ". Decidí a dónde puede ir en vez de dejar que el test lo ignore.");
        }
        long serviceId = serviceInStatus(from);
        String etagBefore = etagOf(serviceId);
        int eventsBefore = countEvents(serviceId);

        io.restassured.response.Response response = post(serviceId,
            body(transition.name(), null, VALID_REASON), etagBefore, adminToken);

        if (legal.contains(transition.target())) {
            assertEquals(200, response.statusCode(), from + " → " + transition);
            assertEquals(transition.target().name(), response.jsonPath().getString("status"));
        } else {
            String expectedCode = IMMUTABLE.contains(from) ? "OPS-004" : "OPS-001";
            assertEquals(409, response.statusCode(), from + " → " + transition);
            assertEquals(expectedCode, response.jsonPath().getString("code"),
                from + " → " + transition);
            assertEquals(from.name(), detailOf(serviceId).getString("status"),
                "el rechazo movió el estado");
            assertEquals(eventsBefore, countEvents(serviceId), "el rechazo escribió bitácora");
            assertEquals(0, countAuditLogs(serviceId, "STATUS_CHANGE"),
                "el rechazo escribió auditoría");
            assertEquals(etagBefore, etagOf(serviceId), "el rechazo movió la versión");
        }
    }

    // ---------- Camino feliz -----------------------------------------------------

    @Test
    void changeStatus_toInProgress_movesTheStatusAndSetsTheRealStart() {
        long serviceId = serviceInStatus(ServiceStatus.PENDING_START);

        JsonPath detail = ok(serviceId, body("IN_PROGRESS", "2026-07-10T05:12:00Z", null), null);

        assertEquals("IN_PROGRESS", detail.getString("status"));
        assertEquals(instantOf("2026-07-10T05:12:00Z"), instantOf(detail.getString("startDateTime")));
        assertNull(detail.getString("endDateTime"), "finalizar es otra transición");
    }

    @Test
    void changeStatus_toCompleted_movesTheStatusAndSetsTheRealEnd() {
        long serviceId = serviceInStatus(ServiceStatus.PENDING_START);
        ok(serviceId, body("IN_PROGRESS", "2026-07-10T05:12:00Z", null), null);

        JsonPath detail = ok(serviceId, body("COMPLETED", "2026-07-10T18:30:00Z", null), null);

        assertEquals("COMPLETED", detail.getString("status"));
        assertEquals(instantOf("2026-07-10T05:12:00Z"), instantOf(detail.getString("startDateTime")),
            "finalizar no debe tocar el inicio");
        assertEquals(instantOf("2026-07-10T18:30:00Z"), instantOf(detail.getString("endDateTime")));
    }

    @ParameterizedTest
    @ValueSource(strings = { "PENDING_ASSIGNMENT", "PENDING_START", "IN_PROGRESS" })
    void changeStatus_toCancelledFromEveryActiveState_isAccepted(String from) {
        long serviceId = serviceInStatus(ServiceStatus.valueOf(from));
        String startBefore = detailOf(serviceId).getString("startDateTime");

        JsonPath detail = ok(serviceId,
            body("CANCELLED", null, VALID_REASON), etagOf(serviceId));

        assertEquals("CANCELLED", detail.getString("status"));
        // Cancelar no fecha el viaje, pero TAMPOCO le borra lo que ya tenía: un viaje que arrancó
        // y se abortó sigue registrando cuándo arrancó. Sin esta aserción, escribir null sobre el
        // inicio en la rama que "no hace nada" pasa inadvertido. Solo la parametrización que YA
        // tiene inicio la mide: en las otras dos es null contra null, y una aserción que se cumple
        // sola con un mensaje que habla del inicio borrado es peor que no tenerla.
        if (startBefore != null) {
            assertEquals(startBefore, detail.getString("startDateTime"),
                "cancelar le borró el inicio real al viaje");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = { "PENDING_ASSIGNMENT", "PENDING_START" })
    void changeStatus_toDeletedFromEveryPendingState_isAccepted(String from) {
        long serviceId = serviceInStatus(ServiceStatus.valueOf(from));

        JsonPath detail = ok(serviceId,
            body("DELETED", null, "Registro duplicado por error de digitación"), etagOf(serviceId));

        assertEquals("DELETED", detail.getString("status"));
    }

    /**
     * Eliminar NO borra: el viaje sigue existiendo y se lo puede leer por su propio enlace. Lo que
     * cambia es que deja de aparecer en el listado por defecto.
     */
    @Test
    void changeStatus_toDeleted_keepsTheTripReadableByItsOwnLink() {
        long serviceId = serviceInStatus(ServiceStatus.PENDING_ASSIGNMENT);
        List<Long> listedBeforeDeleting = listedIdsOf();
        ok(serviceId, body("DELETED", null, "Registro duplicado por digitación"), etagOf(serviceId));

        assertEquals("DELETED", detailOf(serviceId).getString("status"));
        // Control positivo: sin esto, "no aparece" no prueba nada — podría no haber aparecido nunca.
        assertTrue(listedBeforeDeleting.contains(serviceId), "el viaje no estaba listado ni antes");

        // Se filtra por el cliente de la corrida y se mira ESTE viaje, no un texto que ningún dato
        // de la suite contiene: con un listado vacío, "no aparece" pasa siempre y además barre la
        // base que se comparte con el sistema anterior.
        assertFalse(listedIdsOf().contains(serviceId),
            "el eliminado sigue apareciendo en el listado por defecto");
        assertTrue(listedIdsOf("DELETED").contains(serviceId),
            "y filtrando por eliminado tiene que estar: no se borró, se etiquetó");
    }

    @Test
    void changeStatus_returnsTheNewEtagAndTheCacheHeaders() {
        long serviceId = serviceInStatus(ServiceStatus.PENDING_START);
        String etagBefore = etagOf(serviceId);

        io.restassured.response.Response response =
            post(serviceId, body("IN_PROGRESS", null, null), null, adminToken);

        assertEquals(200, response.statusCode());
        String returned = response.header("ETag");
        assertNotEquals(etagBefore, returned, "la versión no se movió");
        assertEquals(etagOf(serviceId), returned,
            "el ETag devuelto no es el que quedó guardado: el próximo If-Match daría 412");
        assertEquals("no-store", response.header("Cache-Control"));
        assertEquals("Authorization", response.header("Vary"));
    }

    /**
     * Cancelar libera los recursos sin borrar el dato: el conflicto solo mira los estados que
     * retienen, así que el conductor queda disponible y el viaje conserva a quién tenía asignado.
     */
    @Test
    void changeStatus_toCancelled_freesTheResourcesWithoutClearingThem() {
        int driverId = operationsFixtures.seedDriver("ZTEST Juan", "Pérez");
        int tractorId = operationsFixtures.seedTractor();
        long first = createService("Piura", "Lima");
        assign(first, driverId, tractorId);

        ok(first, body("CANCELLED", null, VALID_REASON), etagOf(first));

        assertEquals(driverId, (int) detailOf(first).getInt("driver.id"),
            "cancelar borró el rastro de quién estaba asignado");
        long second = createService("Sullana", "Trujillo");
        assertEquals(200, assign(second, driverId, tractorId).statusCode(),
            "el recurso del viaje cancelado sigue retenido");
    }

    // ---------- Reapertura ---------------------------------------------------------

    /**
     * El caso que la reapertura existe para resolver, y la razón por la que el destino sale del
     * rastro y no de una constante: un viaje cancelado EN RUTA vuelve a en ruta, no a foja cero.
     * Devolverlo a "pendiente de asignación" borraría que ya había arrancado, y conserva conductor
     * y tracto (cancelar no los limpia), así que el estado y los datos quedarían contradiciéndose.
     */
    @ParameterizedTest
    @ValueSource(strings = { "PENDING_ASSIGNMENT", "PENDING_START", "IN_PROGRESS" })
    void changeStatus_reopened_restoresTheStatusTheTripHadBeforeBeingCancelled(String from) {
        long serviceId = serviceInStatus(ServiceStatus.valueOf(from));
        ok(serviceId, body("CANCELLED", null, VALID_REASON), etagOf(serviceId));

        JsonPath detail = ok(serviceId,
            body("REOPENED", null, "El cliente retomó el embarque"), etagOf(serviceId));

        assertEquals(from, detail.getString("status"),
            "la reapertura tiene que devolver el viaje al estado exacto que tenía");
    }

    /** Y lo mismo para eliminar: si resultó que el registro sí existía, se vuelve. */
    @Test
    void changeStatus_reopened_alsoUndoesADeletion() {
        long serviceId = serviceInStatus(ServiceStatus.PENDING_START);
        ok(serviceId, body("DELETED", null, "Duplicado por error de digitación"), etagOf(serviceId));

        JsonPath detail = ok(serviceId,
            body("REOPENED", null, "No era duplicado, el cliente pidió dos viajes"), etagOf(serviceId));

        assertEquals("PENDING_START", detail.getString("status"));
    }

    /** El rastro tiene que decir que fue una reapertura, no un cambio de estado más. */
    @Test
    void changeStatus_reopened_namesItselfInTheTrail() {
        long serviceId = serviceInStatus(ServiceStatus.IN_PROGRESS);
        ok(serviceId, body("CANCELLED", null, VALID_REASON), etagOf(serviceId));

        JsonPath detail = ok(serviceId,
            body("REOPENED", null, "El cliente retomó el embarque"), etagOf(serviceId));

        String note = lastEventNote(detail);
        assertTrue(note.contains("Reapertura: el viaje vuelve a en ruta"), note);
        assertTrue(note.contains("Estado: cancelado → en ruta"), note);
        assertTrue(note.contains("Motivo: El cliente retomó"), note);
        // El texto COMPLETO: "contiene Reapertura" lo cumple también el texto FORZADO, así que la
        // aserción ni siquiera distinguía las dos ramas, y borrar el destino dejaba la auditoría
        // sin decir a dónde volvió el viaje.
        assertEquals("Reapertura del viaje, que vuelve a en ruta. El cliente retomó el embarque",
            auditDescription(serviceId));
    }

    /** Solo se reabre lo que está cancelado o eliminado: terminar no es un error que deshacer. */
    @ParameterizedTest
    @ValueSource(strings = { "PENDING_ASSIGNMENT", "PENDING_START", "IN_PROGRESS", "COMPLETED" })
    void changeStatus_reopenedOnATripThatIsNotOut_returns409(String from) {
        long serviceId = serviceInStatus(ServiceStatus.valueOf(from));

        post(serviceId, body("REOPENED", null, VALID_REASON), etagOf(serviceId), adminToken)
            .then().statusCode(409)
            .body("code", equalTo("OPS-001"))
            .body("detail", containsString("Solo se reabre"));

        assertEquals(from, detailOf(serviceId).getString("status"));
        assertEquals(0, countAuditLogs(serviceId, "STATUS_CHANGE"));
    }

    /**
     * Sin rastro no se inventa un destino. Es el caso de los viajes que traiga el cutover: llegan
     * cancelados sin la fila que dice de dónde venían, y devolverlos a un estado por defecto los
     * pondría en uno que quizás nunca tuvieron.
     */
    @Test
    void changeStatus_reopenedWithoutAnAuditTrail_returns409() {
        long serviceId = serviceInStatus(ServiceStatus.PENDING_START);
        operationsFixtures.forceServiceStatus(serviceId, "CANCELLED");

        post(serviceId, body("REOPENED", null, VALID_REASON), etagOf(serviceId), adminToken)
            .then().statusCode(409)
            .body("code", equalTo("OPS-009"))
            // El mensaje se lee entero: "desde qué estado se cancelado" era gramaticalmente roto,
            // y es el caso MÁS probable de todos (todo viaje que traiga el cutover llega sin fila).
            .body("detail", containsString(
                "no registra el estado que tenía antes de quedar \"cancelado\""));

        assertEquals("CANCELLED", detailOf(serviceId).getString("status"));
        assertEquals(0, countAuditLogs(serviceId, "STATUS_CHANGE"),
            "un rechazo no escribe auditoría");
    }

    /** La gerencia de operaciones y el despacho NO reabren: es la operación más acotada. */
    @ParameterizedTest
    @ValueSource(strings = { "operations_manager", "dispatcher" })
    void changeStatus_reopenedByARoleBelowGeneralManagement_returns403(String role) {
        long serviceId = serviceInStatus(ServiceStatus.PENDING_START);
        ok(serviceId, body("CANCELLED", null, VALID_REASON), etagOf(serviceId));
        String token = TestAuth.fabricateTokenForUser(fixtures.userId("admin"), "admin", role);

        post(serviceId, body("REOPENED", null, VALID_REASON), etagOf(serviceId), token)
            .then().statusCode(403).body("code", equalTo("COM-003"));

        assertEquals("CANCELLED", detailOf(serviceId).getString("status"));
    }

    /** La gerencia general sí. Sin este caso, el veto podría estar prohibiéndoselo a todos. */
    @Test
    void changeStatus_reopenedByGeneralManagement_isAccepted() {
        long serviceId = serviceInStatus(ServiceStatus.PENDING_START);
        ok(serviceId, body("CANCELLED", null, VALID_REASON), etagOf(serviceId));
        String token = TestAuth.fabricateTokenForUser(
            fixtures.userId("admin"), "admin", "general_manager");

        assertEquals(200, post(serviceId, body("REOPENED", null, VALID_REASON),
            etagOf(serviceId), token).statusCode());
    }

    /** Y con un token de dos roles el veto sigue ganando, como en el resto de la tabla. */
    @Test
    void changeStatus_reopenedByGeneralManagementWhoIsAlsoDispatcher_stillFails() {
        long serviceId = serviceInStatus(ServiceStatus.PENDING_START);
        ok(serviceId, body("CANCELLED", null, VALID_REASON), etagOf(serviceId));
        String token = TestAuth.fabricateAccessTokenWithRolesForUser(
            fixtures.userId("admin"), "admin", Set.of("general_manager", "dispatcher"));

        post(serviceId, body("REOPENED", null, VALID_REASON), etagOf(serviceId), token)
            .then().statusCode(403).body("code", equalTo("COM-003"));
    }

    /** Reabrir deshace pero no borra: la cancelación queda en el rastro. */
    @Test
    void changeStatus_reopened_doesNotEraseTheCancellationFromTheTrail() {
        long serviceId = serviceInStatus(ServiceStatus.PENDING_START);
        ok(serviceId, body("CANCELLED", null, VALID_REASON), etagOf(serviceId));
        JsonPath detail = ok(serviceId, body("REOPENED", null, VALID_REASON), etagOf(serviceId));

        List<String> notes = detail.getList("events.note", String.class);
        assertTrue(notes.stream().anyMatch(n -> n.contains("Estado: pendiente de inicio → cancelado")),
            "la cancelación desapareció del rastro: " + notes);
        assertEquals(2, countAuditLogs(serviceId, "STATUS_CHANGE"),
            "una fila por cada movimiento: la cancelación y la reapertura");
    }

    /** Exige motivo e If-Match, igual que las dos que deshace. */
    @Test
    void changeStatus_reopenedWithoutIfMatchOrReason_isRejected() {
        long serviceId = serviceInStatus(ServiceStatus.PENDING_START);
        ok(serviceId, body("CANCELLED", null, VALID_REASON), etagOf(serviceId));

        assertEquals(412, post(serviceId, body("REOPENED", null, VALID_REASON), null, adminToken)
            .statusCode());
        post(serviceId, body("REOPENED", null, null), etagOf(serviceId), adminToken)
            .then().statusCode(400).body("code", equalTo("COM-001"));
    }

    /**
     * EL escenario que abre la reapertura, y que ninguna de las dos decisiones que lo producen es
     * equivocada por separado: cancelar conserva los recursos (para no perder quién estaba
     * asignado) y reabrir devuelve al estado exacto. En el medio, cancelado deja de retener.
     *
     * <p>Sin el chequeo, el paso 4 deja DOS viajes activos con el mismo conductor, sin conflicto
     * reportado y sin la línea de bitácora que dice que se forzó.
     */
    @Test
    void changeStatus_reopenedWhileAnotherTripTookItsResources_returns409() {
        int driverId = operationsFixtures.seedDriver("ZTEST Ana", "Quispe");
        int tractorId = operationsFixtures.seedTractor();
        long first = createService("Piura conflicto", "Lima conflicto");
        assign(first, driverId, tractorId);
        ok(first, body("CANCELLED", null, VALID_REASON), etagOf(first));

        // Con el primero cancelado, el conductor queda libre y el segundo se lo lleva SIN forzar.
        long second = createService("Sullana conflicto", "Trujillo conflicto");
        assertEquals(200, assign(second, driverId, tractorId).statusCode());

        io.restassured.response.Response response = post(first,
            body("REOPENED", null, "El cliente retomó el embarque"), etagOf(first), adminToken);

        assertEquals(409, response.statusCode(), response.body().asString());
        assertEquals("OPS-002", response.jsonPath().getString("code"));
        assertEquals(true, response.jsonPath().getBoolean("forcible"),
            "el conflicto de la reapertura tiene que ser forzable, como el de la asignación");
        assertEquals(List.of("DRIVER", "TRACTOR"),
            response.jsonPath().getList("conflicts.resource"),
            "el conductor y el tracto: los dos se los llevó el otro viaje");
        assertEquals("CANCELLED", detailOf(first).getString("status"), "el 409 movió el estado");
        assertEquals(1, countAuditLogs(first, "STATUS_CHANGE"),
            "el 409 dejó una fila de auditoría: la única legítima es la de la cancelación");
    }

    /** Y forzando SÍ reabre, pero la bitácora queda diciendo qué se pisó y con qué viaje. */
    @Test
    void changeStatus_reopenedForcingTheConflict_recordsWhatWasOverridden() {
        int driverId = operationsFixtures.seedDriver("ZTEST Ana", "Quispe");
        int tractorId = operationsFixtures.seedTractor();
        int trailerId = operationsFixtures.seedTrailer();
        long first = createService("Piura forzado", "Lima forzado");
        assign(first, driverId, tractorId);
        // Con carreta a propósito: sin ella, las tres placas del llamado son dos strings y un null,
        // y cruzar tracto con carreta pasa inadvertido.
        operationsFixtures.forceServiceResources(first, driverId, tractorId, trailerId);
        ok(first, body("CANCELLED", null, VALID_REASON), etagOf(first));
        long second = createService("Sullana forzado", "Trujillo forzado");
        io.restassured.response.Response taken = assign(second, driverId, tractorId);
        assertEquals(200, taken.statusCode(), "el otro viaje no llegó a tomar los recursos: "
            + taken.body().asString());
        String otherCode = detailOf(second).getString("code");

        // Primero sin forzar, para dejar constancia de que el conflicto EXISTE en este punto: si
        // no, un 200 posterior podría venir de que no había nada que forzar.
        io.restassured.response.Response blocked = post(first,
            body("REOPENED", null, "El cliente retomó el embarque"), etagOf(first), adminToken);
        assertEquals(409, blocked.statusCode(), blocked.body().asString());

        Map<String, Object> payload = body("REOPENED", null, "El cliente retomó el embarque");
        payload.put("force", true);
        JsonPath detail = ok(first, payload, etagOf(first));

        assertEquals("PENDING_START", detail.getString("status"));
        String note = lastEventNote(detail);
        assertTrue(note.contains("Reapertura forzada: el conductor ZTEST Ana Quispe"), note);
        String tractorPlate = plateOf("tractors", tractorId);
        assertTrue(note.contains("Reapertura forzada: el tracto " + tractorPlate), note);
        assertFalse(note.contains("el tracto " + plateOf("trailers", trailerId)),
            "la placa del tracto tiene que ser la suya, no la de la carreta: " + note);
        assertTrue(note.contains(otherCode),
            "la bitácora tiene que nombrar el viaje con el que se comparte el recurso: " + note);
        // El texto COMPLETO: el número de conflictos pisados no lo afirmaba nadie, así que la
        // auditoría —que es la tabla reconstruible— podía mentir sobre cuántos recursos se pisaron.
        assertEquals("Reapertura FORZADA del viaje, que vuelve a pendiente de inicio pisando 2 "
            + "conflicto(s) de recursos. El cliente retomó el embarque", auditDescription(first));

        assertLogLineNamingAnotherTripIsOnlyReadableByWhoCanReadThatTrip(first, second, otherCode);
    }

    /**
     * La invariante que hace INOCUO el único canal de este PR que cruza el borde del viaje: la
     * línea del forzado nombra OTRO viaje (su código y su estado) y queda PERSISTIDA, así que la
     * lee todo el que lea el detalle de éste — cinco roles, incluido ventas, que ni siquiera entra
     * a este endpoint. Hoy no filtra nada porque el detalle no autoriza por fila.
     *
     * <p>Vive en un método propio y no enterrada en el test del forzado porque lo que sostiene es
     * una condición sobre OTRO endpoint: el día que alguien le agregue autorización por fila a
     * {@code getService} —por ejemplo, que ventas vea solo los viajes de sus clientes— este par se
     * pone rojo, y tiene que ponerse rojo diciendo lo que se rompió, no dentro de un caso cuyo
     * nombre habla de la bitácora del forzado.
     */
    private void assertLogLineNamingAnotherTripIsOnlyReadableByWhoCanReadThatTrip(
            long trip, long otherTrip, String otherCode) {
        String salesToken = TestAuth.fabricateAccessToken("colado", "sales");
        String noteAsSales = given().header("Authorization", "Bearer " + salesToken)
        .when().get("/services/" + trip)
        .then().statusCode(200).extract().jsonPath()
            .getList("events.note", String.class).stream()
            .filter(n -> n.contains("forzada")).findFirst().orElse("");

        assertTrue(noteAsSales.contains(otherCode),
            "ventas lee por la bitácora el código de otro viaje");
        assertEquals(200, given().header("Authorization", "Bearer " + salesToken)
            .when().get("/services/" + otherTrip).statusCode(),
            "y eso solo es aceptable mientras pueda pedir ese otro viaje de frente");
    }

    /**
     * El mismo conflicto, pero volviendo a EN RUTA, que es el caso que el bloque nombra como su
     * motivo. Los otros dos casos de conflicto vuelven a "pendiente de inicio", así que la guarda
     * quedaba medida sobre un solo destino: escrita como {@code target != PENDING_START} pasaba
     * verde, y reabrir un viaje cancelado en ruta cuyo conductor ya se llevó otro daba 200, con dos
     * viajes activos compartiéndolo y sin la línea que dice que se forzó.
     */
    @Test
    void changeStatus_reopenedTowardsInProgressWhileAnotherTripTookItsResources_returns409() {
        int driverId = operationsFixtures.seedDriver("ZTEST Ruta", "Conflicto");
        int tractorId = operationsFixtures.seedTractor();
        long first = createService("Piura en ruta", "Lima en ruta");
        assign(first, driverId, tractorId);
        ok(first, body("IN_PROGRESS", null, null), etagOf(first));
        ok(first, body("CANCELLED", null, VALID_REASON), etagOf(first));
        long second = createService("Sullana en ruta", "Trujillo en ruta");
        assertEquals(200, assign(second, driverId, tractorId).statusCode(),
            "el otro viaje no llegó a tomar los recursos del cancelado");

        io.restassured.response.Response response = post(first,
            body("REOPENED", null, "El cliente retomó el embarque"), etagOf(first), adminToken);

        assertEquals(409, response.statusCode(), response.body().asString());
        assertEquals("OPS-002", response.jsonPath().getString("code"));
        assertEquals(true, response.jsonPath().getBoolean("forcible"));
        assertEquals(2, response.jsonPath().getList("conflicts").size());

        Map<String, Object> forced = body("REOPENED", null, "El cliente retomó el embarque");
        forced.put("force", true);
        JsonPath detail = ok(first, forced, etagOf(first));

        assertEquals("IN_PROGRESS", detail.getString("status"),
            "la reapertura forzada tiene que devolver el viaje a en ruta igual que sin forzar");
        assertTrue(lastEventNote(detail).contains("Reapertura forzada"), lastEventNote(detail));
    }

    /**
     * La otra mitad del mismo {@code if}: SOLO la reapertura consulta conflictos. Arrancar un viaje
     * no moviliza recursos —ya los tenía— así que compartirlos con otro viaje no lo bloquea. Sin
     * este caso, extender la consulta a todas las transiciones no rompe nada, y un viaje que
     * comparte conductor deja de poder arrancar: 409 donde el contrato promete 200.
     */
    @Test
    void changeStatus_toInProgressWhileAnotherTripHoldsTheSameResources_isStillAccepted() {
        int driverId = operationsFixtures.seedDriver("ZTEST Compartido", "Arranque");
        int tractorId = operationsFixtures.seedTractor();
        long first = createService("Piura compartido", "Lima compartido");
        assign(first, driverId, tractorId);
        // El segundo se los queda POR SQL: por la puerta de adelante la asignación los rechazaría,
        // que es justamente lo que hace que este solapamiento solo lo pueda producir el cutover.
        long second = createService("Sullana compartido", "Trujillo compartido");
        operationsFixtures.forceServiceStatus(second, "PENDING_START");
        operationsFixtures.forceServiceResources(second, driverId, tractorId, null);

        JsonPath detail = ok(first, body("IN_PROGRESS", null, null), etagOf(first));

        assertEquals("IN_PROGRESS", detail.getString("status"));
        assertFalse(lastEventNote(detail).contains("forzada"),
            "arrancar no reporta conflictos: no moviliza recursos");
    }

    /**
     * `force` solo fuerza cuando vale exactamente true. Las otras tres formas de entrada —ausente,
     * false y null explícito— rechazan el conflicto igual, y hay que medirlas: un cliente que
     * serialice el objeto entero manda null en los campos que no aplican, que es justamente lo que
     * hacen los formularios. Con el default invertido, ese cliente forzaría sin pedirlo y dejaría
     * dos viajes activos compartiendo conductor sin que nadie lo haya decidido.
     */
    @ParameterizedTest
    @ValueSource(strings = { "absent", "false", "null" })
    void changeStatus_reopenedWithForceNotSetToTrue_stillReportsTheConflict(String form) {
        int driverId = operationsFixtures.seedDriver("ZTEST Default", "Force");
        int tractorId = operationsFixtures.seedTractor();
        long first = createService("Piura default", "Lima default");
        assign(first, driverId, tractorId);
        ok(first, body("CANCELLED", null, VALID_REASON), etagOf(first));
        long second = createService("Sullana default", "Trujillo default");
        assertEquals(200, assign(second, driverId, tractorId).statusCode());

        Map<String, Object> payload = body("REOPENED", null, "El cliente retomó el embarque");
        switch (form) {
            case "absent" -> { }
            case "false" -> payload.put("force", false);
            default -> payload.put("force", null);
        }

        post(first, payload, etagOf(first), adminToken)
            .then().statusCode(409).body("code", equalTo("OPS-002"))
            .body("forcible", equalTo(true));

        assertEquals("CANCELLED", detailOf(first).getString("status"));
    }

    /**
     * Y el conflicto se mira sobre los TRES recursos, no sobre los dos que traen todos los demás
     * casos. La carreta retiene igual que el conductor y el tracto; sacarla de la consulta deja
     * reabrir en silencio un viaje cuya carreta ya se llevó otro, sin 409 y sin línea de forzado.
     */
    @Test
    void changeStatus_reopenedWhileAnotherTripTookOnlyItsTrailer_returns409() {
        int driverId = operationsFixtures.seedDriver("ZTEST Solo", "Carreta");
        int tractorId = operationsFixtures.seedTractor();
        int trailerId = operationsFixtures.seedTrailer();
        long first = createService("Piura carreta", "Lima carreta");
        assign(first, driverId, tractorId);
        operationsFixtures.forceServiceResources(first, driverId, tractorId, trailerId);
        ok(first, body("CANCELLED", null, VALID_REASON), etagOf(first));

        // El otro viaje se lleva SOLO la carreta: con sus propios conductor y tracto, el único
        // conflicto posible es el tercer recurso, que es el que ningún otro caso aísla.
        long second = createService("Sullana carreta", "Trujillo carreta");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("driverId", operationsFixtures.seedDriver("ZTEST Otro", "Conductor"));
        payload.put("tractorId", operationsFixtures.seedTractor());
        payload.put("trailerId", trailerId);
        assertEquals(200, given().header("Authorization", "Bearer " + adminToken)
            .contentType(ContentType.JSON).body(payload)
            .when().post("/services/" + second + "/assignment").statusCode());

        io.restassured.response.Response response = post(first,
            body("REOPENED", null, "El cliente retomó el embarque"), etagOf(first), adminToken);

        assertEquals(409, response.statusCode(), response.body().asString());
        assertEquals("OPS-002", response.jsonPath().getString("code"));
        assertEquals(List.of("TRAILER"), response.jsonPath().getList("conflicts.resource"),
            "el conflicto de la carreta no se reportó");
    }

    /**
     * Y un `force` que no es booleano sale con el Problem que el contrato promete, no con el 400
     * del lector de JSON. Es el mismo agujero que ya se cerró en el destino y en la fecha: un campo
     * tipado que no parsea lo rechaza Jackson antes de que corra una línea nuestra, y este proyecto
     * no tiene manejador de ese error, así que el cuerpo sale sin `code` y filtrando la clase, la
     * línea y la columna donde el parser se trabó.
     */
    @ParameterizedTest
    @ValueSource(strings = { "maybe", "1", "TRUE!" })
    void changeStatus_withAForceThatIsNotABoolean_returns400WithAProblemBody(String force) {
        long serviceId = serviceInStatus(ServiceStatus.PENDING_START);
        ok(serviceId, body("CANCELLED", null, VALID_REASON), etagOf(serviceId));
        Map<String, Object> payload = body("REOPENED", null, VALID_REASON);
        payload.put("force", force);

        post(serviceId, payload, etagOf(serviceId), adminToken)
            .then().statusCode(400)
            .contentType("application/problem+json")
            .body("code", equalTo("COM-001"))
            .body("detail", containsString("true o false"));
    }

    /** Y las dos formas que SÍ son booleanas siguen entrando, escritas como texto o como booleano. */
    @ParameterizedTest
    @ValueSource(strings = { "boolean", "text", "upper" })
    void changeStatus_withForceInEitherJsonForm_isAccepted(String form) {
        int driverId = operationsFixtures.seedDriver("ZTEST Forma", form);
        int tractorId = operationsFixtures.seedTractor();
        long first = createService("Piura forma " + form, "Lima forma " + form);
        assign(first, driverId, tractorId);
        ok(first, body("CANCELLED", null, VALID_REASON), etagOf(first));
        long second = createService("Sullana forma " + form, "Trujillo forma " + form);
        assertEquals(200, assign(second, driverId, tractorId).statusCode());

        Map<String, Object> payload = body("REOPENED", null, "El cliente retomó el embarque");
        payload.put("force", switch (form) {
            case "boolean" -> Boolean.TRUE;
            case "upper" -> "TRUE";
            default -> "true";
        });

        assertEquals("PENDING_START",
            ok(first, payload, etagOf(first)).getString("status"));
    }

    /**
     * Dos viajes distintos se llevaron el MISMO conductor (el segundo forzando), así que reabrir el
     * primero encuentra dos FILAS de un solo RECURSO. Los dos números divergen a propósito y cada
     * salida usa el suyo: el 409 cuenta recursos distintos (decir "hay 1 recurso más" con el mismo
     * conductor dos veces sería falso) y la auditoría cuenta filas, para que empate con las líneas
     * de bitácora, que son una por fila pisada. Todos los demás casos usan conductor + tracto, o
     * sea el único donde los dos números coinciden y la divergencia no se puede medir.
     */
    @Test
    void changeStatus_reopenedAgainstTwoTripsHoldingTheSameResource_countsRowsAndResourcesApart() {
        int driverId = operationsFixtures.seedDriver("ZTEST Doble", "Retencion");
        int tractorId = operationsFixtures.seedTractor();
        long first = createService("Piura doble", "Lima doble");
        // El tracto es SUYO y no lo toma nadie más: así el único conflicto posible es el conductor,
        // y las dos filas que salen son del mismo recurso. Dejarlo sin tracto no sirve: reabrir
        // hacia "pendiente de inicio" exige conductor y tracto, y el 409 sería otro.
        assign(first, driverId, tractorId);
        ok(first, body("CANCELLED", null, VALID_REASON), etagOf(first));

        long second = createService("Sullana doble", "Trujillo doble");
        assertEquals(200, assign(second, driverId, operationsFixtures.seedTractor()).statusCode());
        long third = createService("Chiclayo doble", "Piura doble 2");
        Map<String, Object> forcedAssignment = new LinkedHashMap<>();
        forcedAssignment.put("driverId", driverId);
        forcedAssignment.put("tractorId", operationsFixtures.seedTractor());
        forcedAssignment.put("force", true);
        assertEquals(200, given().header("Authorization", "Bearer " + adminToken)
            .contentType(ContentType.JSON).body(forcedAssignment)
            .when().post("/services/" + third + "/assignment").statusCode());

        io.restassured.response.Response blocked = post(first,
            body("REOPENED", null, "El cliente retomó el embarque"), etagOf(first), adminToken);

        assertEquals(409, blocked.statusCode(), blocked.body().asString());
        assertEquals(2, blocked.jsonPath().getList("conflicts").size(), "son dos filas");
        assertFalse(blocked.jsonPath().getString("detail").contains("recurso más en conflicto"),
            "el mismo conductor retenido por dos viajes es UN recurso: " + blocked.jsonPath()
                .getString("detail"));

        Map<String, Object> payload = body("REOPENED", null, "El cliente retomó el embarque");
        payload.put("force", true);
        JsonPath detail = ok(first, payload, etagOf(first));

        assertEquals(2, lastEventNote(detail).lines()
            .filter(line -> line.startsWith("Reapertura forzada:")).count(),
            "una línea de bitácora por fila pisada: " + lastEventNote(detail));
        assertTrue(auditDescription(first).contains("pisando 2 conflicto(s)"),
            "la auditoría cuenta filas, para empatar con la bitácora: " + auditDescription(first));
    }

    /**
     * Los tres campos del cuerpo se declaran como texto para que un valor mal tipado no lo rechace
     * el lector de JSON, que contestaría sin `code` y filtrando internos del parser. Eso vale para
     * los escalares —Jackson los convierte a texto— pero NO para un objeto ni un arreglo, que
     * siguen cayendo en el mismo agujero. Se fija acá para que quede medido dónde termina la
     * técnica, y no en la prosa de un javadoc.
     */
    @ParameterizedTest
    @ValueSource(strings = { "target", "dateTime", "note", "force" })
    void changeStatus_withANonScalarField_returns400(String field) {
        long serviceId = serviceInStatus(ServiceStatus.PENDING_START);
        Map<String, Object> payload = body("IN_PROGRESS", null, null);
        payload.put(field, Map.of());

        post(serviceId, payload, null, adminToken).then().statusCode(400);

        assertEquals("PENDING_START", detailOf(serviceId).getString("status"),
            "un cuerpo mal formado no puede haber movido el viaje");
    }

    /** Sin conflicto, forzar no cambia nada ni ensucia la bitácora. */
    @Test
    void changeStatus_reopenedWithoutConflict_doesNotWriteAForcedLine() {
        long serviceId = serviceInStatus(ServiceStatus.PENDING_START);
        ok(serviceId, body("CANCELLED", null, VALID_REASON), etagOf(serviceId));

        JsonPath detail = ok(serviceId, body("REOPENED", null, VALID_REASON), etagOf(serviceId));

        assertFalse(lastEventNote(detail).contains("Reapertura forzada"),
            "se registró un forzado que no existió");
    }

    /** Reabrir hacia un estado que NO retiene recursos no consulta conflictos ni los necesita. */
    @Test
    void changeStatus_reopenedTowardsPendingAssignment_needsNoConflictCheck() {
        int driverId = operationsFixtures.seedDriver("ZTEST Sin", "Retencion");
        int tractorId = operationsFixtures.seedTractor();
        long serviceId = serviceInStatus(ServiceStatus.PENDING_ASSIGNMENT);
        // El viaje CONSERVA recursos y otro se los lleva: si la guarda mirara solo "es una
        // reapertura" y no "el destino retiene", esto daría 409. Sin recursos el caso no medía
        // nada, porque no había conflicto posible que reportar.
        operationsFixtures.forceServiceResources(serviceId, driverId, tractorId, null);
        ok(serviceId, body("CANCELLED", null, VALID_REASON), etagOf(serviceId));
        long other = createService("Sullana libre", "Trujillo libre");
        assertEquals(200, assign(other, driverId, tractorId).statusCode());

        JsonPath detail = ok(serviceId, body("REOPENED", null, VALID_REASON), etagOf(serviceId));

        assertEquals("PENDING_ASSIGNMENT", detail.getString("status"));
        assertFalse(lastEventNote(detail).contains("forzada"),
            "reabrir hacia un estado que NO retiene no puede reportar conflicto");
    }

    /**
     * Un viaje con REFUERZOS no se reabre todavía: sus recursos adicionales también vuelven a
     * quedar retenidos y la consulta de conflictos solo mira los tres principales. Validar tres y
     * restablecer N sería el mismo agujero que este bloque cierra, por la otra mitad.
     *
     * <p>Es un límite con fecha de vencimiento: hoy ningún endpoint escribe esa tabla, así que por
     * la API es inalcanzable; se abre con el cutover y se cierra cuando exista el endpoint de
     * refuerzos.
     */
    @ParameterizedTest
    @ValueSource(strings = { "DRIVER", "TRACTOR", "TRAILER" })
    void changeStatus_reopenedOnATripWithAdditionalResources_returns409(String kind) {
        long serviceId = serviceInStatus(ServiceStatus.PENDING_START);
        ok(serviceId, body("CANCELLED", null, VALID_REASON), etagOf(serviceId));
        // Los tres tipos, porque el refuerzo puede traer uno solo: contar únicamente los que llevan
        // conductor dejaría pasar al que es solo tracto, y ese también vuelve a quedar retenido.
        seedReinforcement(serviceId, kind);

        post(serviceId, body("REOPENED", null, VALID_REASON), etagOf(serviceId), adminToken)
            .then().statusCode(409)
            .body("code", equalTo("OPS-009"))
            .body("detail", containsString("recursos de refuerzo"));

        assertEquals("CANCELLED", detailOf(serviceId).getString("status"));
    }

    /**
     * Y el límite llega hasta donde tiene que llegar: hacia un estado que NO retiene, los refuerzos
     * tampoco vuelven a quedar retenidos, así que no hay nada que verificar y la reapertura procede.
     * Sin este caso, subir la guarda un renglón —fuera del bloque que solo corre al volver a
     * retener— deja la suite verde y prohíbe transicionar cualquier viaje que tenga refuerzos.
     */
    @Test
    void changeStatus_reopenedWithAdditionalResourcesTowardsPendingAssignment_isAccepted() {
        long serviceId = serviceInStatus(ServiceStatus.PENDING_ASSIGNMENT);
        ok(serviceId, body("CANCELLED", null, VALID_REASON), etagOf(serviceId));
        seedReinforcement(serviceId, "DRIVER");

        JsonPath detail = ok(serviceId, body("REOPENED", null, VALID_REASON), etagOf(serviceId));

        assertEquals("PENDING_ASSIGNMENT", detail.getString("status"));
    }

    /**
     * El veto se resuelve ANTES de tocar la base, así que un rol sin permiso no puede usar los
     * códigos de error para averiguar qué viajes existen. Sin este caso, mover la autorización
     * adentro del bloque lockeado —el refactor natural, porque todo lo demás vive ahí— deja la
     * suite entera en verde y convierte el endpoint en un oráculo de existencia.
     */
    @Test
    void changeStatus_asAVetoedRoleOnAnUnknownId_returns403AndNot404() {
        String token = TestAuth.fabricateTokenForUser(
            fixtures.userId("admin"), "admin", "dispatcher");

        post(999999999L, body("CANCELLED", null, VALID_REASON), "\"x\"", token)
            .then().statusCode(403).body("code", equalTo("COM-003"));
    }

    /**
     * `force` solo lo lee la reapertura: pedirlo en las otras cuatro es un 400. Es la única bandera
     * que autoriza pisar la reja de conflictos —o sea, poner dos viajes activos sobre el mismo
     * conductor—, así que "aceptado e ignorado" sería el peor default el día que una segunda
     * transición se vuelva forzable: quedaría vivo para un rol vetado de todas las transiciones
     * para las que se diseñó.
     */
    @ParameterizedTest
    @ValueSource(strings = { "IN_PROGRESS", "COMPLETED", "CANCELLED", "DELETED" })
    void changeStatus_forcingATransitionThatDoesNotUseIt_returns400(String target) {
        // Las CUATRO, no solo las dos que fechan el viaje: medirlo sobre esas dos deja pasar una
        // guarda escrita como "las que tienen columna de fecha", que es una propiedad accidental.
        long serviceId = serviceInStatus(
            "COMPLETED".equals(target) ? ServiceStatus.IN_PROGRESS : ServiceStatus.PENDING_START);
        Map<String, Object> payload = body(target, null, VALID_REASON);
        payload.put("force", true);

        post(serviceId, payload, etagOf(serviceId), adminToken)
            .then().statusCode(400)
            .body("code", equalTo("COM-001"))
            .body("detail", containsString("Forzar solo aplica al reabrir"));

        assertNotEquals(target, detailOf(serviceId).getString("status"), "el 400 movió el estado");
    }

    /** Pero `false` y `null` no son "forzar", así que ahí no hay nada que rechazar. */
    @ParameterizedTest
    @ValueSource(strings = { "false", "null" })
    void changeStatus_withForceNotSetOnATransitionThatDoesNotUseIt_isAccepted(String form) {
        long serviceId = serviceInStatus(ServiceStatus.PENDING_START);
        Map<String, Object> payload = body("IN_PROGRESS", null, null);
        payload.put("force", "false".equals(form) ? Boolean.FALSE : null);

        assertEquals("IN_PROGRESS", ok(serviceId, payload, null).getString("status"));
    }

    /** Y reabrir hacia "pendiente de inicio" exige recursos, igual que llegar ahí asignando. */
    @Test
    void changeStatus_reopenedTowardsPendingStartWithoutResources_returns409() {
        long serviceId = serviceInStatus(ServiceStatus.PENDING_START);
        ok(serviceId, body("CANCELLED", null, VALID_REASON), etagOf(serviceId));
        operationsFixtures.forceServiceResources(serviceId, null, null, null);
        int eventsBefore = countEvents(serviceId);
        int auditBefore = countAuditLogs(serviceId, "STATUS_CHANGE");

        post(serviceId, body("REOPENED", null, VALID_REASON), etagOf(serviceId), adminToken)
            .then().statusCode(409)
            .body("code", equalTo("OPS-009"))
            // El mensaje nombra el DESTINO y no un verbo fijo: por acá el usuario pidió reabrir,
            // no iniciar, aunque la guarda sea la misma.
            .body("detail", containsString("no se puede pasar a \"pendiente de inicio\""));

        assertEquals("CANCELLED", detailOf(serviceId).getString("status"));
        assertEquals(eventsBefore, countEvents(serviceId), "un rechazo no escribe bitácora");
        assertEquals(auditBefore, countAuditLogs(serviceId, "STATUS_CHANGE"),
            "un rechazo no escribe auditoría");
    }

    // ---------- If-Match condicional ---------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = { "CANCELLED", "DELETED" })
    void changeStatus_toADestructiveTargetWithoutIfMatch_returns412(String target) {
        long serviceId = serviceInStatus(ServiceStatus.PENDING_START);

        io.restassured.response.Response response =
            post(serviceId, body(target, null, VALID_REASON), null, adminToken);

        assertEquals(412, response.statusCode());
        assertEquals("COM-004", response.jsonPath().getString("code"));
        assertEquals("PENDING_START", detailOf(serviceId).getString("status"));
        assertEquals(0, countAuditLogs(serviceId, "STATUS_CHANGE"));
    }

    @ParameterizedTest
    @ValueSource(strings = { "CANCELLED", "DELETED" })
    void changeStatus_toADestructiveTargetWithAStaleIfMatch_returns412(String target) {
        long serviceId = serviceInStatus(ServiceStatus.PENDING_START);

        io.restassured.response.Response response = post(serviceId,
            body(target, null, VALID_REASON), "\"2020-01-01T00:00:00Z\"", adminToken);

        assertEquals(412, response.statusCode());
        assertEquals("COM-004", response.jsonPath().getString("code"));
    }

    /**
     * La otra mitad de la regla, que es la que se rompe si alguien copia la verificación de
     * versión del PUT y la aplica a las cinco transiciones: iniciar y finalizar NO la exigen.
     */
    @ParameterizedTest
    @ValueSource(strings = { "IN_PROGRESS", "COMPLETED" })
    void changeStatus_toANonDestructiveTargetWithoutIfMatch_isAccepted(String target) {
        long serviceId = serviceInStatus(
            "IN_PROGRESS".equals(target) ? ServiceStatus.PENDING_START : ServiceStatus.IN_PROGRESS);

        io.restassured.response.Response response =
            post(serviceId, body(target, null, null), null, adminToken);

        assertEquals(200, response.statusCode());
    }

    /**
     * Y el contrato dice que ahí "se ignora". No se ignora: si viene, se honra. Ignorar un
     * If-Match presente viola la semántica de HTTP y, peor, le da al cliente una protección que
     * cree tener y no tiene. El contrato quedó corregido en este PR.
     */
    @ParameterizedTest
    @ValueSource(strings = { "IN_PROGRESS", "COMPLETED" })
    void changeStatus_toANonDestructiveTargetWithAStaleIfMatch_isStillHonored(String target) {
        long serviceId = serviceInStatus(
            "IN_PROGRESS".equals(target) ? ServiceStatus.PENDING_START : ServiceStatus.IN_PROGRESS);

        io.restassured.response.Response response = post(serviceId,
            body(target, null, null), "\"2020-01-01T00:00:00Z\"", adminToken);

        assertEquals(412, response.statusCode());
        assertEquals("COM-004", response.jsonPath().getString("code"));
    }

    /** El If-Match se compara contra una versión que SE MUEVE, no contra una constante. */
    @Test
    void changeStatus_withAnEtagThatAnotherTransitionInvalidated_returns412() {
        long serviceId = serviceInStatus(ServiceStatus.PENDING_START);
        String staleEtag = etagOf(serviceId);
        ok(serviceId, body("IN_PROGRESS", null, null), null);

        io.restassured.response.Response response =
            post(serviceId, body("CANCELLED", null, VALID_REASON), staleEtag, adminToken);

        assertEquals(412, response.statusCode());
    }

    /**
     * El orden de los rechazos, que es la decisión de diseño del service. Un 412 dice "recargá y
     * reintentá", y recargar no arregla nada cuando desde ese estado no se puede ir a ningún lado.
     */
    @Test
    void changeStatus_onATerminalServiceWithAStaleIfMatch_answersTheImmutabilityFirst() {
        long serviceId = serviceInStatus(ServiceStatus.CANCELLED);

        io.restassured.response.Response response = post(serviceId,
            body("DELETED", null, VALID_REASON), "\"2020-01-01T00:00:00Z\"", adminToken);

        assertEquals(409, response.statusCode());
        assertEquals("OPS-004", response.jsonPath().getString("code"));
    }

    @Test
    void changeStatus_withAnInvalidTransitionAndAStaleIfMatch_answersTheTransitionFirst() {
        long serviceId = serviceInStatus(ServiceStatus.COMPLETED);

        io.restassured.response.Response response = post(serviceId,
            body("CANCELLED", null, VALID_REASON), "\"2020-01-01T00:00:00Z\"", adminToken);

        assertEquals(409, response.statusCode());
        assertEquals("OPS-001", response.jsonPath().getString("code"));
    }

    /** El detalle del OPS-001 nombra los DOS estados en es-PE, no solo el que se pidió. */
    @Test
    void changeStatus_whenTheTransitionDoesNotExist_namesBothStatesInSpanish() {
        long serviceId = serviceInStatus(ServiceStatus.COMPLETED);

        post(serviceId, body("IN_PROGRESS", null, null), null, adminToken)
            .then().statusCode(409)
            .body("code", equalTo("OPS-001"))
            .body("detail", containsString("completado"))
            .body("detail", containsString("en ruta"));
    }

    // ---------- Fechas y RN-OP13 --------------------------------------------------

    @Test
    void changeStatus_toCompletedExactlyAtTheStart_isAccepted() {
        long serviceId = serviceInStatus(ServiceStatus.IN_PROGRESS);
        OffsetDateTime start = OffsetDateTime.parse("2026-07-10T05:12:00Z");
        operationsFixtures.forceServiceDates(serviceId, start, null);

        JsonPath detail = ok(serviceId, body("COMPLETED", "2026-07-10T05:12:00Z", null), null);

        assertEquals(instantOf(detail.getString("startDateTime")),
            instantOf(detail.getString("endDateTime")), "el borde de igualdad tiene que entrar");
    }

    @Test
    void changeStatus_toCompletedBeforeTheStart_returns400AndWritesNothing() {
        long serviceId = serviceInStatus(ServiceStatus.IN_PROGRESS);
        operationsFixtures.forceServiceDates(
            serviceId, OffsetDateTime.parse("2026-07-10T05:12:00Z"), null);
        int eventsBefore = countEvents(serviceId);

        post(serviceId, body("COMPLETED", "2026-07-10T05:11:59Z", null), null, adminToken)
            .then().statusCode(400)
            .body("code", equalTo("COM-001"))
            .body("detail", containsString("anterior a la de inicio"));

        assertEquals("IN_PROGRESS", detailOf(serviceId).getString("status"));
        assertEquals(eventsBefore, countEvents(serviceId));
    }

    /**
     * El caso que se rompe en silencio si RN-OP13 solo se valida cuando la fecha VIENE en el
     * cuerpo: un viaje cuyo inicio quedó en el futuro se cerraría "ahora", o sea antes de empezar.
     */
    @Test
    void changeStatus_toCompletedWithoutDateTimeAfterAFutureStart_returns400() {
        long serviceId = serviceInStatus(ServiceStatus.IN_PROGRESS);
        operationsFixtures.forceServiceDates(
            serviceId, OffsetDateTime.now(ZoneOffset.UTC).plusDays(30), null);

        post(serviceId, body("COMPLETED", null, null), null, adminToken)
            .then().statusCode(400).body("code", equalTo("COM-001"));
    }

    /**
     * Un viaje SIN inicio NO se cierra (ruling del dueño, 2026-08-13).
     *
     * <p>El fixture tiene que forzar el estado por SQL justamente porque por la aplicación este
     * caso es inalcanzable: pasar a "en ruta" siempre escribe el inicio, y a "completado" solo se
     * llega desde "en ruta". Si la guarda dispara en producción, la fila entró por fuera.
     */
    @Test
    void changeStatus_toCompletedOnATripWithoutAStart_returns409() {
        long serviceId = serviceInStatus(ServiceStatus.IN_PROGRESS);
        operationsFixtures.forceServiceDates(serviceId, null, null);
        int eventsBefore = countEvents(serviceId);

        post(serviceId, body("COMPLETED", null, null), null, adminToken)
            .then().statusCode(409)
            .body("code", equalTo("OPS-009"))
            .body("detail", containsString("no registra cuándo inició"));

        assertEquals("IN_PROGRESS", detailOf(serviceId).getString("status"));
        assertEquals(eventsBefore, countEvents(serviceId));
        assertEquals(0, countAuditLogs(serviceId, "STATUS_CHANGE"));
    }

    /**
     * La otra mitad, que es la que hace que la guarda no moleste a nadie: iniciar SIEMPRE escribe
     * el inicio, aunque el cuerpo no lo mande. Ese es el invariante que vuelve inalcanzable al caso
     * de arriba, y sin este test la guarda parecería una restricción arbitraria.
     */
    @Test
    void changeStatus_toInProgress_alwaysWritesTheStartSoFinishingIsNeverBlocked() {
        long serviceId = serviceInStatus(ServiceStatus.PENDING_START);

        JsonPath started = ok(serviceId, body("IN_PROGRESS", null, null), null);
        assertNotNull(started.getString("startDateTime"),
            "iniciar sin fecha tiene que dejar el inicio puesto igual");

        assertEquals(200, post(serviceId, body("COMPLETED", null, null), null, adminToken)
            .statusCode(), "el viaje que arrancó por la aplicación siempre se puede cerrar");
    }

    /**
     * La simétrica: no se arranca un viaje sin recursos. Inalcanzable por la API —a "pendiente de
     * inicio" solo se llega asignando, y asignar exige conductor y tracto—, así que el fixture
     * tiene que romperla a mano.
     */
    @ParameterizedTest
    @ValueSource(strings = { "driver", "tractor", "both" })
    void changeStatus_toInProgressWithoutResources_returns409(String missing) {
        long serviceId = serviceInStatus(ServiceStatus.PENDING_START);
        operationsFixtures.forceServiceResources(serviceId,
            "tractor".equals(missing) ? seededDriverId : null,
            "driver".equals(missing) ? seededTractorId : null,
            null);
        int eventsBefore = countEvents(serviceId);

        post(serviceId, body("IN_PROGRESS", null, null), null, adminToken)
            .then().statusCode(409)
            .body("code", equalTo("OPS-009"))
            .body("detail", containsString("conductor y tracto"));

        assertEquals("PENDING_START", detailOf(serviceId).getString("status"));
        assertEquals(eventsBefore, countEvents(serviceId));
        assertEquals(0, countAuditLogs(serviceId, "STATUS_CHANGE"));
    }

    /** La carreta sigue siendo OPCIONAL: sin ella el viaje arranca igual (hay carga que no la lleva). */
    @Test
    void changeStatus_toInProgressWithoutATrailer_isAccepted() {
        long serviceId = serviceInStatus(ServiceStatus.PENDING_START);
        operationsFixtures.forceServiceResources(
            serviceId, seededDriverId, seededTractorId, null);

        assertEquals(200, post(serviceId, body("IN_PROGRESS", null, null), null, adminToken)
            .statusCode());
    }

    /** Las SALIDAS no piden nada: tienen que estar disponibles para los viajes que quedaron a medias. */
    @Test
    void changeStatus_toCancelledOnATripWithoutResources_isStillAccepted() {
        long serviceId = serviceInStatus(ServiceStatus.PENDING_START);
        operationsFixtures.forceServiceResources(serviceId, null, null, null);

        assertEquals(200, post(serviceId, body("CANCELLED", null, VALID_REASON),
            etagOf(serviceId), adminToken).statusCode());
    }

    /** Y el 409 es del CIERRE, no de la fila: cancelar un viaje sin inicio sigue andando. */
    @Test
    void changeStatus_toCancelledOnATripWithoutAStart_isStillAccepted() {
        long serviceId = serviceInStatus(ServiceStatus.IN_PROGRESS);
        operationsFixtures.forceServiceDates(serviceId, null, null);

        assertEquals(200, post(serviceId, body("CANCELLED", null, VALID_REASON),
            etagOf(serviceId), adminToken).statusCode());
    }

    @Test
    void changeStatus_withoutDateTime_usesNow() {
        long serviceId = serviceInStatus(ServiceStatus.PENDING_START);
        OffsetDateTime before = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1);

        JsonPath detail = ok(serviceId, body("IN_PROGRESS", null, null), null);

        OffsetDateTime stored = OffsetDateTime.parse(detail.getString("startDateTime"));
        assertTrue(stored.isAfter(before), "el default no se resolvió a ahora: " + stored);
        // El techo importa tanto como el piso: sin él, un default de "ahora + un día" pasa igual
        // y el viaje queda declarado como iniciado mañana.
        assertTrue(stored.isBefore(OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(1)),
            "el default se fue al futuro: " + stored);
    }

    @Test
    void changeStatus_withADateTimeInAnotherOffset_normalizesItToUtc() {
        long serviceId = serviceInStatus(ServiceStatus.PENDING_START);

        JsonPath detail = ok(serviceId, body("IN_PROGRESS", "2026-07-10T00:12:00-05:00", null), null);

        // El texto EXACTO, no el instante: comparar instantes no puede fallar, porque cambiar de
        // huso los preserva por definición. Y la diferencia sí se ve: sin normalizar, el POST
        // responde con el huso que mandó el cliente y un GET posterior con Z, o sea el mismo campo
        // con dos representaciones según la puerta.
        assertEquals("2026-07-10T05:12:00Z", detail.getString("startDateTime"));
    }

    /**
     * Mandar una fecha a una transición que no fecha nada es 400, no un dato que se descarte en
     * silencio: aceptar el valor y contestar 200 deja al cliente creyendo que guardó algo que
     * nunca existió.
     */
    /**
     * Las TRES que no fechan el viaje, la reapertura incluida. El mensaje nombra las que sí aplican
     * y no las que no: enumerar las excluidas obliga a corregirlo cada vez que se suma una, y ya se
     * quedó viejo una vez.
     */
    @ParameterizedTest
    @ValueSource(strings = { "CANCELLED", "DELETED", "REOPENED" })
    void changeStatus_toATargetThatDoesNotDateTheTripWithADateTime_returns400(String target) {
        long serviceId = serviceInStatus(ServiceStatus.PENDING_START);
        if ("REOPENED".equals(target)) {
            ok(serviceId, body("CANCELLED", null, VALID_REASON), etagOf(serviceId));
        }

        post(serviceId, body(target, "2026-07-10T05:12:00Z", VALID_REASON),
                etagOf(serviceId), adminToken)
            .then().statusCode(400)
            .body("code", equalTo("COM-001"))
            .body("detail", containsString("solo aplica al iniciar o al finalizar"));
    }

    /**
     * Reabrir hacia "en ruta" exige conductor y tracto, igual que llegar ahí por primera vez.
     * Restaurar un estado no puede ser más barato que alcanzarlo: las guardas se preguntan por el
     * target RESUELTO, no por el pedido, que en la reapertura es null.
     */
    @Test
    void changeStatus_reopenedTowardsInProgressWithoutResources_returns409() {
        long serviceId = serviceInStatus(ServiceStatus.IN_PROGRESS);
        ok(serviceId, body("CANCELLED", null, VALID_REASON), etagOf(serviceId));
        operationsFixtures.forceServiceResources(serviceId, null, null, null);

        post(serviceId, body("REOPENED", null, VALID_REASON), etagOf(serviceId), adminToken)
            .then().statusCode(409)
            .body("code", equalTo("OPS-009"))
            .body("detail", containsString("conductor y tracto"));

        assertEquals("CANCELLED", detailOf(serviceId).getString("status"));
    }

    /**
     * Un rastro que nombra un estado del que NO SE VUELVE. Guardar solo el valor desconocido era
     * media guarda: "eliminado" y "completado" el enum sí los conoce, y sin esto la reapertura
     * aterrizaba ahí sin pasar por la máquina de estados — un viaje cancelado terminaba eliminado,
     * fuera de listados e indicadores, con la bitácora diciendo "vuelve a eliminado".
     */
    @ParameterizedTest
    @ValueSource(strings = { "DELETED", "COMPLETED", "CANCELLED" })
    void changeStatus_reopenedTowardsAStatusThereIsNoComingBackFrom_returns409(String restored) {
        long serviceId = serviceInStatus(ServiceStatus.PENDING_START);
        ok(serviceId, body("CANCELLED", null, VALID_REASON), etagOf(serviceId));
        QuarkusTransaction.requiringNew().run(() -> entityManager.createNativeQuery(
                "UPDATE operaciones.service_audit_logs SET old_value = ?1 "
                    + "WHERE service_id = ?2 AND field_name = 'status'")
            .setParameter(1, restored).setParameter(2, serviceId).executeUpdate());

        post(serviceId, body("REOPENED", null, VALID_REASON), etagOf(serviceId), adminToken)
            .then().statusCode(409)
            .body("code", equalTo("OPS-009"))
            .body("detail", containsString("del que no se vuelve"));

        assertEquals("CANCELLED", detailOf(serviceId).getString("status"));
    }

    /**
     * El rastro no se lee como "la última fila" sino como "la fila que PUSO al viaje donde está".
     * Buscar la más reciente obliga a confiar en un orden, y los dos posibles fallan por lados
     * distintos: por id, las filas que importe el cutover se leen en el orden en que las insertó el
     * script; por fecha, se depende del reloj de la instancia que escribió. Las dos fallas
     * devuelven un estado ANTERIOR al que el viaje tenía, que es un valor legal y por lo tanto
     * invisible para todas las guardas.
     */
    @Test
    void changeStatus_reopenedWithATrailThatMentionsOtherTransitions_usesTheOneThatCancelledIt() {
        long serviceId = serviceInStatus(ServiceStatus.PENDING_START);
        ok(serviceId, body("CANCELLED", null, VALID_REASON), etagOf(serviceId));
        // Una fila MÁS VIEJA pero con id MÁS ALTO, que es lo que produce un script de migración
        // que inserta agrupando por tipo de cambio en vez de por fecha.
        QuarkusTransaction.requiringNew().run(() -> entityManager.createNativeQuery(
                "INSERT INTO operaciones.service_audit_logs "
                    + "(service_id, changed_by, change_type, field_name, field_label, old_value, "
                    + "new_value, description, logged_at) "
                    + "SELECT ?1, s.created_by, 'STATUS_CHANGE', 'status', 'Estado', "
                    + "'PENDING_ASSIGNMENT', 'PENDING_START', 'Migrado', "
                    + "now() + interval '1 day' FROM operaciones.services s WHERE s.id = ?1")
            .setParameter(1, serviceId).executeUpdate());

        JsonPath detail = ok(serviceId,
            body("REOPENED", null, "El cliente retomó el embarque"), etagOf(serviceId));

        assertEquals("PENDING_START", detail.getString("status"),
            "ganó una fila que habla de OTRA transición");
    }

    /**
     * Un viaje que salió y volvió DOS veces: la reapertura tiene que leer la última cancelación, no
     * la primera. Es el único caso donde el orden del rastro decide algo —el filtro por el estado
     * que puso al viaje donde está deja dos filas candidatas, no una— y sin él las dos direcciones
     * del orden quedan indistinguibles.
     */
    @Test
    void changeStatus_reopenedAfterTwoCycles_restoresTheStateOfTheLastOne() {
        long serviceId = serviceInStatus(ServiceStatus.PENDING_START);
        ok(serviceId, body("CANCELLED", null, VALID_REASON), etagOf(serviceId));
        ok(serviceId, body("REOPENED", null, "El cliente retomó el embarque"), etagOf(serviceId));
        ok(serviceId, body("IN_PROGRESS", null, null), etagOf(serviceId));
        ok(serviceId, body("CANCELLED", null, "Se abortó el viaje ya en ruta"), etagOf(serviceId));

        JsonPath detail = ok(serviceId,
            body("REOPENED", null, "El cliente retomó el embarque otra vez"), etagOf(serviceId));

        assertEquals("IN_PROGRESS", detail.getString("status"),
            "se leyó la PRIMERA cancelación en vez de la última");
    }

    /** Y el valor que el 409 refleja se recorta, como todo texto que un error devuelve. */
    @Test
    void changeStatus_reopenedWithAnUnknownPreviousStatus_abbreviatesWhatItEchoes() {
        long serviceId = serviceInStatus(ServiceStatus.PENDING_START);
        ok(serviceId, body("CANCELLED", null, VALID_REASON), etagOf(serviceId));
        String tooLong = "ESTADO_MIGRADO_CON_UN_NOMBRE_INTERMINABLE_QUE_NADIE_QUIERE_LEER_ENTERO";
        QuarkusTransaction.requiringNew().run(() -> entityManager.createNativeQuery(
                "UPDATE operaciones.service_audit_logs SET old_value = ?1 "
                    + "WHERE service_id = ?2 AND field_name = 'status'")
            .setParameter(1, tooLong).setParameter(2, serviceId).executeUpdate());

        post(serviceId, body("REOPENED", null, VALID_REASON), etagOf(serviceId), adminToken)
            .then().statusCode(409)
            .body("code", equalTo("OPS-009"))
            .body("detail", containsString("ESTADO_MIGRADO_CON_UN_NOMBRE_I…"))
            .body("detail", not(containsString("QUE_NADIE_QUIERE_LEER_ENTERO")));
    }

    /** Y un estado anterior que el enum ya no conoce es 409, no un error del servidor. */
    @Test
    void changeStatus_reopenedWithAnUnknownPreviousStatus_returns409NotAServerError() {
        long serviceId = serviceInStatus(ServiceStatus.PENDING_START);
        ok(serviceId, body("CANCELLED", null, VALID_REASON), etagOf(serviceId));
        QuarkusTransaction.requiringNew().run(() -> entityManager.createNativeQuery(
                "UPDATE operaciones.service_audit_logs SET old_value = 'ESTADO_QUE_YA_NO_EXISTE' "
                    + "WHERE service_id = ?1 AND field_name = 'status'")
            .setParameter(1, serviceId).executeUpdate());
        int eventsBefore = countEvents(serviceId);

        post(serviceId, body("REOPENED", null, VALID_REASON), etagOf(serviceId), adminToken)
            .then().statusCode(409)
            .body("code", equalTo("OPS-009"))
            .body("detail", containsString("ya no existe"));

        assertEquals("CANCELLED", detailOf(serviceId).getString("status"));
        assertEquals(eventsBefore, countEvents(serviceId), "un rechazo no escribe bitácora");
    }

    /** El año de nueve cifras que el ISO admite y la columna no: 400, nunca 500. */
    @ParameterizedTest
    @ValueSource(strings = { "1899-12-31T23:59:59Z", "3000-01-01T00:00:00Z",
        "+999999999-12-31T23:59:59Z" })
    void changeStatus_withADateTimeOutsideTheBusinessWindow_returns400(String dateTime) {
        long serviceId = serviceInStatus(ServiceStatus.PENDING_START);

        post(serviceId, body("IN_PROGRESS", dateTime, null), null, adminToken)
            .then().statusCode(400).body("code", equalTo("COM-001"))
            // El sujeto es el del PEDIDO, no el del PUT: la guarda es compartida y recibe el
            // nombre por parámetro, así que un copy-paste de "La fecha de inicio real" sobrevive
            // a todo lo demás.
            .body("detail", containsString("La fecha de la transición"));
    }

    /**
     * El caso que destapo la bateria en vivo: con el campo declarado como {@code OffsetDateTime},
     * un valor que no parsea salia con un 400 SIN cuerpo de Problem, con content-type comun y
     * filtrando el nombre de la clase y la posicion donde el parser se trabo. El contrato promete
     * un Problem con su codigo.
     */
    @ParameterizedTest
    @ValueSource(strings = { "ayer", "2026-13-45T00:00:00Z", "2026-07-10", "05:12:00" })
    void changeStatus_withAMalformedDateTime_returns400WithAProblemBody(String dateTime) {
        long serviceId = serviceInStatus(ServiceStatus.PENDING_START);

        io.restassured.response.Response response =
            post(serviceId, body("IN_PROGRESS", dateTime, null), null, adminToken);

        assertEquals(400, response.statusCode());
        assertEquals("COM-001", response.jsonPath().getString("code"),
            "el 400 no trae el cuerpo del contrato: " + response.body().asString());
        assertTrue(response.contentType().contains("problem+json"),
            "content-type: " + response.contentType());
        assertFalse(response.body().asString().contains("objectName"),
            "el cuerpo filtra internos del parser: " + response.body().asString());
    }

    /** Ignorada por la regla de negocio no quiere decir exenta de parsear. */
    @Test
    void changeStatus_toCancelledWithAnOutOfWindowDateTime_returns400() {
        long serviceId = serviceInStatus(ServiceStatus.PENDING_START);

        post(serviceId, body("CANCELLED", "3000-01-01T00:00:00Z", VALID_REASON),
                etagOf(serviceId), adminToken)
            .then().statusCode(400).body("code", equalTo("COM-001"));
    }

    // ---------- La nota y el motivo ------------------------------------------------

    /**
     * El texto libre se llama distinto según lo que signifique: en cancelar y eliminar es el MOTIVO
     * (obligatorio) y en iniciar y finalizar es una nota. Los mensajes tienen que decirle a la
     * persona el nombre del campo que mandó, y no hay otro caso que distinga los dos nombres.
     */
    @ParameterizedTest
    @CsvSource({ "IN_PROGRESS, La nota", "CANCELLED, El motivo" })
    void changeStatus_withANulCharacterInTheNote_returns400NamingWhatTheTextMeans(
            String target, String subject) {
        long serviceId = serviceInStatus(ServiceStatus.PENDING_START);

        post(serviceId, body(target, null, "Motivo válido " + ((char) 0) + " de más de diez"),
                etagOf(serviceId), adminToken)
            .then().statusCode(400)
            .body("code", equalTo("COM-001"))
            .body("detail", containsString(subject + " tiene caracteres que no se pueden guardar"));
    }

    @Test
    void changeStatus_withNewlinesInTheNote_cannotForgeLogLines() {
        long serviceId = serviceInStatus(ServiceStatus.PENDING_START);

        JsonPath detail = ok(serviceId,
            body("IN_PROGRESS", null, "primera línea\nEstado: en ruta → completado"), null);

        String note = lastEventNote(detail);
        assertTrue(note.contains("⏎"), "el salto no se aplastó: " + note);
        assertEquals(1, note.lines().filter(line -> line.startsWith("Nota:")).count(),
            "la nota se partió en más de una línea");
        assertEquals(1, note.lines().filter(line -> line.startsWith("Estado:")).count(),
            "la nota plantó una segunda línea de Estado con el formato del servidor");
    }

    @Test
    void changeStatus_toCancelledWithAReasonPaddedToReachTheMinimum_returns400() {
        long serviceId = serviceInStatus(ServiceStatus.PENDING_START);

        post(serviceId, body("CANCELLED", null, "corta     "), etagOf(serviceId), adminToken)
            .then().statusCode(400)
            .body("code", equalTo("COM-001"))
            .body("detail", containsString("El motivo necesita al menos 10"));
    }

    @ParameterizedTest
    @ValueSource(strings = { "CANCELLED", "DELETED" })
    void changeStatus_toADestructiveTargetWithoutAReason_returns400(String target) {
        long serviceId = serviceInStatus(ServiceStatus.PENDING_START);

        post(serviceId, body(target, null, null), etagOf(serviceId), adminToken)
            .then().statusCode(400)
            .body("code", equalTo("COM-001"))
            .body("$", not(hasKey("errors")));
    }

    @Test
    void changeStatus_toCancelledWithAReasonOfExactlyTen_isAccepted() {
        long serviceId = serviceInStatus(ServiceStatus.PENDING_START);

        assertEquals(200, post(serviceId, body("CANCELLED", null, "diez chars"),
            etagOf(serviceId), adminToken).statusCode());
    }

    /** El borde EXACTO: 500 entra. Sin esto, bajar el tope a 400 deja la suite verde. */
    @Test
    void changeStatus_withANoteOfExactlyTheMaximum_isAccepted() {
        long serviceId = serviceInStatus(ServiceStatus.PENDING_START);

        assertEquals(200, post(serviceId, body("IN_PROGRESS", null, "x".repeat(500)), null,
            adminToken).statusCode());
    }

    @Test
    void changeStatus_withANoteOverTheMaximum_returns400NamingTheField() {
        long serviceId = serviceInStatus(ServiceStatus.PENDING_START);

        post(serviceId, body("IN_PROGRESS", null, "x".repeat(501)), null, adminToken)
            .then().statusCode(400)
            .body("code", equalTo("COM-001"))
            .body("errors.field", org.hamcrest.Matchers.hasItem(containsString("note")));
    }

    @Test
    void changeStatus_toInProgressWithABlankNote_omitsTheNoteLine() {
        long serviceId = serviceInStatus(ServiceStatus.PENDING_START);

        JsonPath detail = ok(serviceId, body("IN_PROGRESS", null, "   "), null);

        assertFalse(lastEventNote(detail).contains("Nota:"));
    }

    // ---------- target y forma del cuerpo -------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = { "NAVEGANDO", "in_progress", "PENDING_ASSIGNMENT", "PENDING_START" })
    void changeStatus_withATargetThatIsNotRequestable_returns400(String target) {
        long serviceId = serviceInStatus(ServiceStatus.PENDING_START);

        post(serviceId, body(target, null, null), null, adminToken)
            .then().statusCode(400)
            .body("code", equalTo("COM-001"))
            .body("detail", containsString(
                "IN_PROGRESS, COMPLETED, CANCELLED, DELETED, REOPENED"));
    }

    /**
     * Las dos fronteras del orden de guardas que el javadoc del método llama "la decisión de diseño"
     * y que ningún caso medía: todos los tests de rastro roto mandan el ETag fresco, así que
     * intercambiar el 412 con las guardas de al lado los dejaba en verde.
     *
     * <p>Arriba del 412 va la resolución del destino: sin destino no hay nada que versionar, y a un
     * rastro que hay que sanear no lo arregla recargar. Abajo va la guarda de datos de etapa: ahí
     * recargar SÍ sirve, porque el viaje pudo cambiar mientras el usuario miraba la pantalla.
     */
    @Test
    void changeStatus_reopenedWithoutATrailAndAStaleIfMatch_answersTheTrailFirst() {
        long serviceId = serviceInStatus(ServiceStatus.PENDING_START);
        operationsFixtures.forceServiceStatus(serviceId, "CANCELLED");

        post(serviceId, body("REOPENED", null, VALID_REASON), "\"2020-01-01T00:00:00Z\"", adminToken)
            .then().statusCode(409).body("code", equalTo("OPS-009"));
    }

    @Test
    void changeStatus_reopenedWithoutResourcesAndAStaleIfMatch_answersTheVersionFirst() {
        long serviceId = serviceInStatus(ServiceStatus.PENDING_START);
        ok(serviceId, body("CANCELLED", null, VALID_REASON), etagOf(serviceId));
        operationsFixtures.forceServiceResources(serviceId, null, null, null);

        post(serviceId, body("REOPENED", null, VALID_REASON), "\"2020-01-01T00:00:00Z\"", adminToken)
            .then().statusCode(412).body("code", equalTo("COM-004"));
    }

    /** El destino se recorta antes de resolverlo: un espacio de más no cambia lo que se pidió. */
    @Test
    void changeStatus_withATargetPaddedWithSpaces_isAccepted() {
        long serviceId = serviceInStatus(ServiceStatus.PENDING_START);

        assertEquals("IN_PROGRESS",
            ok(serviceId, body(" IN_PROGRESS ", null, null), null).getString("status"));
    }

    /**
     * Sin la anotación declarativa, el mapper contesta 400 igual pero con OTRA forma de cuerpo (sin
     * el arreglo `errors`), y esa diferencia es la que hace falta fijar: es la que distingue un 400
     * de forma de uno de negocio.
     */
    @ParameterizedTest
    @ValueSource(strings = { "", "   " })
    void changeStatus_withABlankTarget_returns400NamingTheField(String target) {
        long serviceId = serviceInStatus(ServiceStatus.PENDING_START);

        post(serviceId, body(target, null, null), null, adminToken)
            .then().statusCode(400)
            .body("code", equalTo("COM-001"))
            .body("errors.field", org.hamcrest.Matchers.hasItem(containsString("target")));
    }

    @Test
    void changeStatus_withoutTarget_returns400() {
        long serviceId = serviceInStatus(ServiceStatus.PENDING_START);

        given().header("Authorization", "Bearer " + adminToken).contentType(ContentType.JSON)
            .body(Map.of("note", "sin target"))
        .when().post("/services/" + serviceId + "/status")
        .then().statusCode(400).body("code", equalTo("COM-001"));
    }

    @Test
    void changeStatus_withoutBody_returns400() {
        long serviceId = serviceInStatus(ServiceStatus.PENDING_START);

        given().header("Authorization", "Bearer " + adminToken).contentType(ContentType.JSON)
        .when().post("/services/" + serviceId + "/status")
        .then().statusCode(400);
    }

    @Test
    void changeStatus_withAnUnknownProperty_ignoresIt() {
        long serviceId = serviceInStatus(ServiceStatus.PENDING_START);
        Map<String, Object> payload = body("IN_PROGRESS", null, null);
        payload.put("colado", "x");

        assertEquals(200, post(serviceId, payload, null, adminToken).statusCode());
    }

    // ---------- Roles y veto por target ---------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = { "admin", "general_manager", "operations_manager", "dispatcher" })
    void changeStatus_toInProgress_isAllowedForEveryOperatingRole(String role) {
        long serviceId = serviceInStatus(ServiceStatus.PENDING_START);
        String token = TestAuth.fabricateTokenForUser(fixtures.userId("admin"), "admin", role);

        assertEquals(200, post(serviceId, body("IN_PROGRESS", null, null), null, token).statusCode());
    }

    @ParameterizedTest
    @ValueSource(strings = { "CANCELLED", "DELETED" })
    void changeStatus_asDispatcher_cannotRequestADestructiveTarget(String target) {
        long serviceId = serviceInStatus(ServiceStatus.PENDING_START);
        String token = TestAuth.fabricateTokenForUser(
            fixtures.userId("admin"), "admin", "dispatcher");
        int eventsBefore = countEvents(serviceId);
        String etagBefore = etagOf(serviceId);

        post(serviceId, body(target, null, VALID_REASON), etagOf(serviceId), token)
            .then().statusCode(403).body("code", equalTo("COM-003"));

        assertEquals("PENDING_START", detailOf(serviceId).getString("status"));
        assertEquals(eventsBefore, countEvents(serviceId));
        assertEquals(0, countAuditLogs(serviceId, "STATUS_CHANGE"));
        assertEquals(etagBefore, etagOf(serviceId), "el 403 movió la versión");
    }

    /**
     * El caso que define la regla. Con UN rol por token, un veto y una lista de permitidos se
     * comportan igual; solo un token con dos roles los distingue. Hoy la tabla limita a un rol por
     * usuario, así que este escenario solo se alcanza fabricando el token — y eso no lo hace
     * decorativo: la regla se escribe una vez y tiene que sobrevivir al día en que eso cambie.
     */
    @Test
    void changeStatus_asDispatcherWithADecidingRole_stillCannotCancel() {
        long serviceId = serviceInStatus(ServiceStatus.PENDING_START);
        String token = TestAuth.fabricateAccessTokenWithRolesForUser(
            fixtures.userId("admin"), "admin", Set.of("dispatcher", "operations_manager"));

        post(serviceId, body("CANCELLED", null, VALID_REASON), etagOf(serviceId), token)
            .then().statusCode(403).body("code", equalTo("COM-003"));
    }

    /** Y la otra mitad: el veto es por TARGET, no una prohibición del endpoint. */
    @Test
    void changeStatus_asDispatcherWithADecidingRole_canStillStart() {
        long serviceId = serviceInStatus(ServiceStatus.PENDING_START);
        String token = TestAuth.fabricateAccessTokenWithRolesForUser(
            fixtures.userId("admin"), "admin", Set.of("dispatcher", "operations_manager"));

        assertEquals(200, post(serviceId, body("IN_PROGRESS", null, null), null, token).statusCode());
    }

    @ParameterizedTest
    @ValueSource(strings = { "sales", "warehouse_keeper", "finance_manager" })
    void changeStatus_asARoleOutsideTheEndpoint_returns403(String role) {
        long serviceId = serviceInStatus(ServiceStatus.PENDING_START);
        String token = TestAuth.fabricateAccessToken("colado", role);

        assertEquals(403, post(serviceId, body("IN_PROGRESS", null, null), null, token).statusCode());
    }

    @Test
    void changeStatus_withoutToken_returns401() {
        long serviceId = serviceInStatus(ServiceStatus.PENDING_START);

        given().contentType(ContentType.JSON).body(body("IN_PROGRESS", null, null))
        .when().post("/services/" + serviceId + "/status")
        .then().statusCode(401);
    }

    // ---------- Precios --------------------------------------------------------------

    /**
     * Este endpoint es una VÍA NUEVA hacia el detalle completo, y el despacho entra por ella. Se
     * mide sobre el JSON crudo del POST, no sobre un GET posterior.
     */
    @Test
    void changeStatus_asDispatcher_omitsTheAmountsFromTheResponse() {
        long serviceId = serviceInStatus(ServiceStatus.PENDING_START);
        String token = TestAuth.fabricateTokenForUser(
            fixtures.userId("admin"), "admin", "dispatcher");

        io.restassured.response.Response response =
            post(serviceId, body("IN_PROGRESS", null, null), null, token);

        response.then().statusCode(200)
            .body("$", not(hasKey("price")))
            .body("$", not(hasKey("currencyCode")));
        // Se busca la CLAVE y no el número: el cuerpo lleva ids de seis cifras de una base que
        // comparte v1, así que "contiene 3200" se pone rojo el día que a un viaje le toca un id
        // que lo contenga. Buscar la clave además alcanza a un precio anidado, que el otro no veía.
        assertFalse(response.body().asString().contains("\"price\""),
            "el importe apareció en el cuerpo que recibe el despacho");
        // La bitácora es OTRO canal y se mide donde sí puede fallar: el único código que escribe
        // importes en una nota es la edición, que ya los enmascara, y eso lo fija su propia suite.
        // Acá alcanza con que ninguna línea que ESTE endpoint escribe nombre un importe.
        assertTrue(response.jsonPath().getList("events.note", String.class).stream()
                .noneMatch(note -> note != null && note.contains("Precio")),
            "una línea de este endpoint nombró el precio");
    }

    /** El negativo de arriba solo vale si alguien ve el positivo por la MISMA puerta. */
    @Test
    void changeStatus_asAPricedRole_includesTheAmounts() {
        long serviceId = serviceInStatus(ServiceStatus.PENDING_START);

        post(serviceId, body("IN_PROGRESS", null, null), null, adminToken)
            .then().statusCode(200)
            .body("price", equalTo(3200f))
            .body("currencyCode", equalTo("PEN"));
    }

    // ---------- Ruta -----------------------------------------------------------------

    @Test
    void changeStatus_withUnknownId_returns404() {
        post(999999999L, body("IN_PROGRESS", null, null), null, adminToken)
            .then().statusCode(404).body("code", equalTo("OPS-005"));
    }

    @ParameterizedTest
    @ValueSource(strings = { "abc", "9999999999999999999999", "1 " })
    void changeStatus_withAMalformedId_returns400(String id) {
        given().header("Authorization", "Bearer " + adminToken).contentType(ContentType.JSON)
            .body(body("IN_PROGRESS", null, null))
        .when().post("/services/" + id + "/status")
        .then().statusCode(400).body("code", equalTo("COM-001"));
    }

    // ---------- El rastro -------------------------------------------------------------

    @Test
    void changeStatus_writesOneLogEntryAndTheAuditRows() {
        long serviceId = serviceInStatus(ServiceStatus.PENDING_START);
        int eventsBefore = countEvents(serviceId);

        // El instante se elige para que Lima y UTC caigan en DIAS distintos (02:00Z = 21:00 del
        // día anterior en Lima). Con una marca del mediodía, "contiene 10/07/2026" pasa igual
        // escriba en el huso que escriba, y la aserción no mediría lo que su mensaje afirma.
        // Transiciona un usuario DISTINTO del que creó el viaje. Con el mismo, `updated_by` y
        // `changed_by` ya valían eso desde el alta y las aserciones comparaban contra un valor
        // derivado de la misma fuente que prueban: borrar `service.updatedBy = userId` quedaba verde.
        String otherUser = TestAuth.fabricateTokenForUser(
            fixtures.userId("lcampos"), "lcampos", "operations_manager");
        io.restassured.response.Response response = post(serviceId,
            body("IN_PROGRESS", "2026-07-10T02:00:00Z", "Salió"), null, otherUser);
        assertEquals(200, response.statusCode(), response.body().asString());
        JsonPath detail = response.jsonPath();

        assertEquals(eventsBefore + 1, countEvents(serviceId), "una entrada por acción");
        String note = lastEventNote(detail);
        assertTrue(note.contains("Estado: pendiente de inicio → en ruta"), note);
        assertTrue(note.contains("Inicio real: 09/07/2026 21:00:00"),
            "la bitácora tiene que escribir en hora de Perú, con la hora completa: " + note);
        assertTrue(note.contains("Nota: Salió"), note);
        assertFalse(note.contains("Reapertura"),
            "solo la reapertura escribe su línea; el resto de las transiciones no: " + note);
        assertEquals("STATUS_CHANGE", lastEventType(detail),
            "un cambio de estado marcado como otra cosa se pinta mal en el detalle");

        // La auditoría se LEE, no solo se cuenta: sin esto, invertir el de/a o duplicar una fila
        // y borrar la otra dejan el conteo intacto.
        assertEquals(List.of("startDateTime", "status"), auditFieldNames(serviceId));
        assertEquals(List.of("Inicio real", "Estado"), auditFieldLabels(serviceId));
        assertEquals(Arrays.asList(null, "PENDING_START"), auditOldValues(serviceId));
        assertEquals(List.of("2026-07-10T02:00Z", "IN_PROGRESS"), auditNewValues(serviceId));
        // El texto COMPLETO, no dos "contiene": la etiqueta y la nota juntas satisfacen los dos
        // fragmentos sin el texto base, así que borrarlo —o contar toda transición como una
        // reapertura— quedaba verde.
        assertEquals("Cambio de estado a en ruta. Salió", auditDescription(serviceId),
            "la nota tiene que AGREGARSE a la descripción, no reemplazarla");
        assertEquals(fixtures.userId("lcampos"), auditChangedBy(serviceId),
            "la auditoría firmó con el creador en vez de con quien actuó");
        assertEquals(fixtures.userId("lcampos"), updatedByOf(serviceId),
            "updated_by se quedó con el id del alta");
        assertEquals("lcampos", detail.getList("events.createdBy.username", String.class)
            .get(countEvents(serviceId) - 1), "el autor de la bitácora no es quien transicionó");
    }

    /** B1: el camino de FINALIZAR tenía cero verificación de rastro; sus dos ramas se podían borrar. */
    @Test
    void changeStatus_toCompleted_writesTheEndInTheTrailAndTheLog() {
        long serviceId = serviceInStatus(ServiceStatus.IN_PROGRESS);
        // El fin previo se fuerza a propósito: sin él, `previousEnd → null` en la rama END pasa
        // inadvertido. Es la gemela de la rama START, que sí estaba medida.
        operationsFixtures.forceServiceDates(serviceId,
            OffsetDateTime.parse("2026-07-09T00:00:00Z"),
            OffsetDateTime.parse("2026-07-09T12:00:00Z"));

        JsonPath detail = ok(serviceId, body("COMPLETED", "2026-07-10T02:00:00Z", null), null);

        assertEquals(2, countAuditLogs(serviceId, "STATUS_CHANGE"),
            "el estado y el fin real: una fila por columna cambiada");
        assertEquals(List.of("endDateTime", "status"), auditFieldNames(serviceId));
        assertEquals(List.of("Fin real", "Estado"), auditFieldLabels(serviceId),
            "la etiqueta de la rama END se puede escribir como la de START sin que nada falle");
        assertEquals(List.of("2026-07-09T12:00Z", "IN_PROGRESS"), auditOldValues(serviceId),
            "el valor anterior del fin tiene que ser el que la fila TENÍA");
        assertEquals(List.of("2026-07-10T02:00Z", "COMPLETED"), auditNewValues(serviceId));
        String note = lastEventNote(detail);
        assertTrue(note.contains("Estado: en ruta → completado"), note);
        assertTrue(note.contains("Fin real: 09/07/2026 21:00:00"),
            "la bitácora del cierre no dice cuándo terminó el viaje: " + note);
        // Sin nota, la descripción es el texto base SOLO. Sin esto, borrar el ternario que
        // distingue la nota nula deja auditado "Cambio de estado a completado. null" en la tabla
        // que nadie corrige después, y es el caso común: iniciar y finalizar no piden motivo.
        assertEquals("Cambio de estado a completado", auditDescription(serviceId));
    }

    /** La fila que ya traía la marca: el rastro no puede afirmar que antes no había nada. */
    @Test
    void changeStatus_onARowThatAlreadyHadTheDate_auditsTheRealPreviousValue() {
        long serviceId = serviceInStatus(ServiceStatus.PENDING_START);
        operationsFixtures.forceServiceDates(
            serviceId, OffsetDateTime.parse("2026-07-01T10:00:00Z"), null);

        ok(serviceId, body("IN_PROGRESS", "2026-07-10T02:00:00Z", null), null);

        assertEquals(List.of("2026-07-01T10:00Z", "PENDING_START"), auditOldValues(serviceId),
            "la auditoría afirmó que el inicio estaba vacío cuando la fila ya lo tenía");
    }

    /**
     * La otra dirección de RN-OP13, que la guarda original no miraba: una fila del cutover con el
     * FIN puesto y todavía sin arrancar. Sin esto el par queda invertido y lo agarra el CHECK de
     * la tabla al volcar, o sea un 500 donde el contrato promete 400.
     */
    @Test
    void changeStatus_toInProgressAfterAStoredEnd_returns400NotAServerError() {
        long serviceId = serviceInStatus(ServiceStatus.PENDING_START);
        operationsFixtures.forceServiceDates(
            serviceId, null, OffsetDateTime.parse("2026-07-01T10:00:00Z"));

        post(serviceId, body("IN_PROGRESS", "2026-07-10T02:00:00Z", null), null, adminToken)
            .then().statusCode(400).body("code", equalTo("COM-001"))
            // El mensaje habla de la fecha que se está escribiendo. Sin fijarlo, quien INICIA lee
            // uno sobre la fecha de fin, que no es el campo que mandó.
            .body("detail", containsString("posterior a la de fin"));

        assertEquals("PENDING_START", detailOf(serviceId).getString("status"));
        assertEquals(0, countAuditLogs(serviceId, "STATUS_CHANGE"));
    }

    @Test
    void changeStatus_toCancelled_writesOnlyTheStatusAuditRowAndCallsTheTextReason() {
        long serviceId = serviceInStatus(ServiceStatus.PENDING_START);

        JsonPath detail = ok(serviceId, body("CANCELLED", null, VALID_REASON), etagOf(serviceId));

        assertEquals(1, countAuditLogs(serviceId, "STATUS_CHANGE"),
            "cancelar no fecha el viaje: no hay fila de fecha");
        assertTrue(lastEventNote(detail).contains("Motivo: " + VALID_REASON),
            "el texto de una transición destructiva se titula Motivo, no Nota");
    }

    // ---------- Bloqueo y concurrencia ------------------------------------------------

    /**
     * Sin este caso, cambiar la lectura con lock por un {@code findById} común deja la suite
     * ENTERA en verde: el 404 lo sigue dando la búsqueda, y ningún otro test toca dos
     * transacciones. Se perderían el tope de espera, la relectura de la fila y la traducción del
     * conflicto, y `OPS-008` quedaría declarado en el contrato sin nada que lo produzca.
     */
    @Test
    @Timeout(60)
    void changeStatus_whenTheRowIsLockedByAnotherOperation_returns409() throws Exception {
        long serviceId = serviceInStatus(ServiceStatus.PENDING_START);

        withRowLocked(serviceId, () ->
            post(serviceId, body("IN_PROGRESS", null, null), null, adminToken)
                .then().statusCode(409).body("code", equalTo("OPS-008")));

        assertEquals("PENDING_START", detailOf(serviceId).getString("status"));
        assertEquals(0, countAuditLogs(serviceId, "STATUS_CHANGE"),
            "el conflicto de lock dejó rastro de una transición que no ocurrió");
        // Y soltada la fila, la MISMA llamada pasa: el 409 es transitorio, no un rechazo de negocio.
        assertEquals(200, post(serviceId, body("IN_PROGRESS", null, null), null, adminToken)
            .statusCode());
    }

    // ---------- Helpers ----------------------------------------------------------------

    /**
     * Un viaje en el estado pedido. Los estados posteriores al alta se fuerzan por SQL: el camino
     * real pasa por la asignación, que ya tiene su propia suite, y encadenar endpoints acá haría
     * que un defecto de la asignación se lea como un defecto de este.
     *
     * <p>Forzar el estado NO alcanza: hay que dejar la fila como la dejaría la aplicación. Un viaje
     * en ruta SIEMPRE tiene su inicio, porque la transición que lo pone ahí lo escribe. Fabricarlo
     * sin fecha produce una fila que por la API es inalcanzable, y entonces los tests miden el
     * comportamiento sobre un estado que no existe. Los casos que SÍ quieren esa fila rota —la
     * que puede traer el cutover— la piden explícitamente con {@code forceServiceDates}.
     */
    private long serviceInStatus(ServiceStatus status) {
        long serviceId = createService("Piura " + status, "Lima " + status);
        if (status == ServiceStatus.PENDING_ASSIGNMENT) {
            return serviceId;
        }
        operationsFixtures.forceServiceStatus(serviceId, status.name());
        // Todo estado posterior al alta pasa por la asignacion, asi que la fila tiene recursos.
        operationsFixtures.forceServiceResources(
            serviceId, seededDriverId, seededTractorId, null);
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

    private Map<String, Object> body(String target, String dateTime, String note) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("target", target);
        if (dateTime != null) {
            payload.put("dateTime", dateTime);
        }
        if (note != null) {
            payload.put("note", note);
        }
        return payload;
    }

    private io.restassured.response.Response post(
            long serviceId, Map<String, Object> payload, String ifMatch, String token) {
        var request = given().header("Authorization", "Bearer " + token).contentType(ContentType.JSON);
        if (ifMatch != null) {
            request = request.header("If-Match", ifMatch);
        }
        return request.body(payload).when().post("/services/" + serviceId + "/status");
    }

    private JsonPath ok(long serviceId, Map<String, Object> payload, String ifMatch) {
        io.restassured.response.Response response = post(serviceId, payload, ifMatch, adminToken);
        assertEquals(200, response.statusCode(), response.body().asString());
        return response.jsonPath();
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

        return given().header("Authorization", "Bearer " + adminToken).contentType(ContentType.JSON)
            .body(payload)
        .when().post("/services")
        .then().statusCode(201).extract().jsonPath().getLong("id");
    }

    private io.restassured.response.Response assign(long serviceId, int driverId, int tractorId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("driverId", driverId);
        payload.put("tractorId", tractorId);
        return given().header("Authorization", "Bearer " + adminToken).contentType(ContentType.JSON)
            .body(payload)
        .when().post("/services/" + serviceId + "/assignment");
    }

    /** Los ids que el listado devuelve para el cliente de esta corrida, con el filtro que se pida. */
    private List<Long> listedIdsOf(String... status) {
        String url = "/services?clientId=" + clientId + "&size=100"
            + (status.length == 0 ? "" : "&status=" + status[0]);
        return given().header("Authorization", "Bearer " + adminToken)
        .when().get(url)
        .then().statusCode(200).extract().jsonPath().getList("content.id", Long.class);
    }

    /** Un refuerzo del viaje que trae UN solo recurso, del tipo pedido. */
    private void seedReinforcement(long serviceId, String kind) {
        operationsFixtures.seedAdditionalAssignment(serviceId,
            "DRIVER".equals(kind) ? operationsFixtures.seedDriver("ZTEST Refuerzo", kind) : null,
            "TRACTOR".equals(kind) ? operationsFixtures.seedTractor() : null,
            "TRAILER".equals(kind) ? operationsFixtures.seedTrailer() : null,
            "ZTEST refuerzo del viaje");
    }

    /** La placa que la base tiene para esa unidad, para comparar contra la que salió en la línea. */
    private String plateOf(String table, int fleetUnitId) {
        return (String) entityManager
            .createNativeQuery("SELECT plate FROM public." + table + " WHERE id = ?1")
            .setParameter(1, fleetUnitId).getSingleResult();
    }

    private JsonPath detailOf(long serviceId) {
        return given().header("Authorization", "Bearer " + adminToken)
        .when().get("/services/" + serviceId)
        .then().statusCode(200).extract().jsonPath();
    }

    private String etagOf(long serviceId) {
        return given().header("Authorization", "Bearer " + adminToken)
        .when().get("/services/" + serviceId)
        .then().statusCode(200).extract().header("ETag");
    }

    private String lastEventNote(JsonPath detail) {
        List<String> notes = detail.getList("events.note", String.class);
        return notes.get(notes.size() - 1);
    }

    /**
     * El INSTANTE, para comparar sin depender de cómo cada lado lo serialice. Comparar los textos
     * no sirve: {@code OffsetDateTime.toString()} omite los segundos cuando son cero, así que
     * "05:12Z" y "05:12:00Z" son el mismo momento y dos cadenas distintas.
     */
    private java.time.Instant instantOf(String isoDateTime) {
        return isoDateTime == null ? null : OffsetDateTime.parse(isoDateTime)
            .truncatedTo(ChronoUnit.SECONDS).toInstant();
    }

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
                    awaitQuietly(locked);   // avisa que ya la tiene
                    awaitQuietly(locked);   // espera a que el bloque haya terminado
                });
                awaitQuietly(finished);     // y avisa que la transacción ya cerró
            });
            try {
                locked.await(30, TimeUnit.SECONDS);
                body.run();
            } finally {
                // Libera al que retiene el lock SIN tapar la falla de arriba: si la barrera ya se
                // rompió, el error que importa es el primero, no este. Pero si el retenedor ya
                // terminó (típicamente porque reventó antes de tomar el lock), se lo consulta acá:
                // si no, su excepción —la causa real— se pierde y aflora un timeout sin sentido.
                awaitQuietly(locked);
                if (holder.isDone()) {
                    holder.get(1, TimeUnit.SECONDS);
                }
                // Y se ESPERA a que suelte la fila, también cuando el bloque falló: si no, el
                // borrado del @AfterEach corre contra una fila todavía lockeada y su conexión no
                // tiene tope. La suite colgada taparía la aserción rota, que es lo que importa.
                awaitQuietly(finished);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * Espera en la barrera sin propagar. Se usa donde una falla de sincronización NO es la falla
     * que el test quiere reportar: enmascararla ahí convierte un error claro en un
     * {@code BrokenBarrierException} sin información.
     */
    private static void awaitQuietly(CyclicBarrier barrier) {
        try {
            barrier.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception ignored) {
            // barrera rota o espera agotada: la falla real ya la reporta quien corresponde
        }
    }

    private int countEvents(long serviceId) {
        return ((Number) entityManager.createNativeQuery(
                "SELECT COUNT(*) FROM operaciones.service_events WHERE service_id = ?1")
            .setParameter(1, serviceId).getSingleResult()).intValue();
    }

    @SuppressWarnings("unchecked")
    private List<String> auditColumn(long serviceId, String column) {
        return entityManager.createNativeQuery(
                "SELECT " + column + " FROM operaciones.service_audit_logs"
                    + " WHERE service_id = ?1 AND change_type = 'STATUS_CHANGE' ORDER BY field_name")
            .setParameter(1, serviceId).getResultList();
    }

    private List<String> auditFieldNames(long serviceId) { return auditColumn(serviceId, "field_name"); }

    private List<String> auditFieldLabels(long serviceId) { return auditColumn(serviceId, "field_label"); }

    private List<String> auditOldValues(long serviceId) { return auditColumn(serviceId, "old_value"); }

    private List<String> auditNewValues(long serviceId) { return auditColumn(serviceId, "new_value"); }

    /**
     * La descripción del ÚLTIMO cambio de estado. Antes se pedía la única distinta, lo cual valía
     * cuando un viaje solo podía moverse una vez; con la reapertura un mismo viaje acumula varias.
     */
    @SuppressWarnings("unchecked")
    private String auditDescription(long serviceId) {
        List<String> descriptions = entityManager.createNativeQuery(
                "SELECT description FROM operaciones.service_audit_logs"
                    + " WHERE service_id = ?1 AND change_type = 'STATUS_CHANGE' ORDER BY id DESC")
            .setParameter(1, serviceId).getResultList();
        return descriptions.get(0);
    }

    private int auditChangedBy(long serviceId) {
        return ((Number) entityManager.createNativeQuery(
                "SELECT DISTINCT changed_by FROM operaciones.service_audit_logs"
                    + " WHERE service_id = ?1 AND change_type = 'STATUS_CHANGE'")
            .setParameter(1, serviceId).getSingleResult()).intValue();
    }

    private int updatedByOf(long serviceId) {
        return ((Number) entityManager.createNativeQuery(
                "SELECT updated_by FROM operaciones.services WHERE id = ?1")
            .setParameter(1, serviceId).getSingleResult()).intValue();
    }

    private String lastEventType(JsonPath detail) {
        List<String> types = detail.getList("events.eventType", String.class);
        return types.get(types.size() - 1);
    }

    private int countAuditLogs(long serviceId, String changeType) {
        return ((Number) entityManager.createNativeQuery(
                "SELECT COUNT(*) FROM operaciones.service_audit_logs"
                    + " WHERE service_id = ?1 AND change_type = ?2")
            .setParameter(1, serviceId).setParameter(2, changeType).getSingleResult()).intValue();
    }
}
