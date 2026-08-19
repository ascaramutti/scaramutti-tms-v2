package com.scaramutti.tms.operations;

import com.scaramutti.tms.operations.model.ServiceStatus;
import com.scaramutti.tms.support.HermeticTestData;
import com.scaramutti.tms.support.OperationsTestData;
import com.scaramutti.tms.support.TestAuth;
import com.scaramutti.tms.support.WarehouseTestData;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;

import jakarta.inject.Inject;
import com.scaramutti.tms.shared.repository.ServiceAssignmentRepository;
import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integración de {@code DELETE /services/{id}/resources/{assignmentId}} — la baja de un refuerzo
 * cargado por error.
 *
 * <p>La suite es DELIBERADAMENTE chica: el endpoint es de prioridad baja y casi todos los casos
 * están acá porque matan una mutación que ningún otro mata. Hay DOS excepciones y se conservan
 * porque son baratas: {@code twice}, que ancla que el reintento no reordene el rastro por delante
 * del 404, y el del 401, que lo pone la configuración de rutas protegidas y no este endpoint. Lo que se cuida en particular son los dos modos de fallar en silencio
 * que este endpoint tiene: borrar la fila EQUIVOCADA, y borrar de más.
 *
 * <p>Los casos que discriminan esos dos modos —{@code removesExactlyThatRow} y {@code twice}—
 * siembran DOS refuerzos y dan de baja el SEGUNDO: con la fila borrada siempre en la posición 0, un
 * {@code LIMIT 1} o un {@code WHERE service_id} sin el id pasan en verde. Los que miden que el
 * recurso quede libre y la versión siembran uno solo, y está bien: cuál refuerzo se elija no cambia
 * lo que miden. El del rastro siembra dos, y el sobreviviente es portante: sin él, la aserción de
 * que la nota NO nombra al que sobrevive no mediría nada.
 */
@QuarkusTest
class ServiceReinforcementDeletionResourceTest {

    private static final String SEEDED_REASON = "Refuerzo previo sembrado";
    /** El mismo texto que escribe el service: tres copias a mano se desincronizan solas. */
    private static final String REMOVAL_DESCRIPTION = "Baja de refuerzo";

    @Inject HermeticTestData fixtures;
    @Inject OperationsTestData operationsFixtures;
    @Inject WarehouseTestData warehouseFixtures;
    @Inject EntityManager entityManager;
    @Inject ServiceAssignmentRepository serviceAssignmentRepository;

    private int clientId;
    private int cargoTypeId;
    private int currencyId;
    private String adminToken;
    private int routeSeq;

    private int principalDriverId;
    private int principalTractorId;
    private int principalTrailerId;
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

    // ---------- El camino feliz, que es donde se esconde borrar de más ----------------

    /**
     * Se da de baja el SEGUNDO refuerzo y se afirma el sobreviviente por VALOR.
     *
     * <p>Los dos detalles que hacen que este caso discrimine: se borra el segundo (no el primero) y
     * se afirma QUIÉN quedó (no cuántos). Con "queda uno" a secas, borrar la fila equivocada pasa
     * en verde; con el primero, un {@code ORDER BY id LIMIT 1} también.
     */
    @Test
    void removeReinforcement_removesExactlyThatRowAndKeepsTheOthers() {
        long id = serviceInProgress();
        long first = seedReinforcement(id, driverId, null, null, "2026-07-01T11:00:00Z");
        long second = seedReinforcement(id, null, tractorId, null, "2026-07-01T12:00:00Z");
        // El armado se VERIFICA: sin esto, "quedó uno" pasaría igual con una sola fila sembrada.
        assertEquals(2, countAssignments(id), "la siembra tiene que dejar DOS refuerzos");

        removeReinforcement(id, second)
            .statusCode(200)
            .body("id", equalTo((int) id))
            .body("status", equalTo("IN_PROGRESS"))
            .body("additionalResources.size()", equalTo(1))
            .body("additionalResources[0].id", equalTo((int) first))
            .body("additionalResources[0].driver.id", equalTo(driverId))
            .body("additionalResources[0].tractor", nullValue())
            // Los recursos PRINCIPALES no se tocan: la baja es del refuerzo, no del viaje.
            .body("driver.id", equalTo(principalDriverId))
            .body("tractor.id", equalTo(principalTractorId))
            .body("trailer.id", equalTo(principalTrailerId));

        assertTrue(assignmentExists(first), "el refuerzo que no se pidió sigue vivo");
        assertFalse(assignmentExists(second), "y el que se pidió no");
        // La fila dada de baja traía SOLO tracto: ni la nota ni la auditoría pueden inventar los
        // otros dos. Sin CONTAR líneas y filas, forzar esas ramas a `true` mete rastro falso
        // ("Carreta: (vacío)", una fila de auditoría con old_value "null") sin que nada falle.
        String note = lastEventNote(id);
        assertTrue(note.contains("Tracto: " + tractorPlate), note);
        assertFalse(note.contains("Conductor: "), "la fila no traía conductor: " + note);
        assertEquals(2, note.lines().count(), "solo el encabezado y el tracto: " + note);
        assertEquals(1, countAuditRows(id, REMOVAL_DESCRIPTION), "una sola fila de auditoría");
    }

    /**
     * El recurso queda LIBRE: otro viaje lo puede tomar sin conflicto y sin forzar.
     *
     * <p>Lo que aporta EN EXCLUSIVA es el efecto sobre la consulta de CONFLICTOS: que el recurso
     * liberado vuelva a ser tomable sin forzar. Que la baja sea física ya lo afirman los
     * {@code assignmentExists} de los otros casos, que consultan la tabla por id en crudo.
     */
    @Test
    void removeReinforcement_freesTheResourceForAnotherService() {
        long id = serviceInProgress();
        long assignmentId = seedReinforcement(id, driverId, null, null, "2026-07-01T11:00:00Z");
        long other = serviceInProgress();

        removeReinforcement(id, assignmentId).statusCode(200);

        // El espejo del caso de arriba: acá la fila traía SOLO conductor, así que el rastro tampoco
        // puede inventar tracto ni carreta.
        assertEquals(2, lastEventNote(id).lines().count(), lastEventNote(id));
        assertEquals(1, countAuditRows(id, REMOVAL_DESCRIPTION));

        given()
            .header("Authorization", "Bearer " + adminToken)
            .contentType(ContentType.JSON)
            .body(addPayload(driverId))
        .when()
            .post("/services/" + other + "/resources")
        .then()
            .statusCode(200)
            .body("additionalResources.size()", equalTo(1))
            .body("additionalResources[0].driver.id", equalTo(driverId));
    }

    // ---------- Que la baja no salga del viaje pedido ---------------------------------

    /**
     * Un refuerzo de OTRO viaje no se puede borrar, y se afirma que SIGUE VIVO.
     *
     * <p>La aserción de status sola no alcanza: una implementación que borra y después contesta 404
     * pasaría. Lo que discrimina es la supervivencia de la fila ajena.
     */
    @Test
    void removeReinforcement_whenItBelongsToAnotherService_returns404AndDeletesNothing() {
        long mine = serviceInProgress();
        long theirs = serviceInProgress();
        long mineAssignment = seedReinforcement(mine, driverId, null, null, "2026-07-01T11:00:00Z");
        long theirsAssignment = seedReinforcement(theirs, null, tractorId, null, "2026-07-01T11:00:00Z");

        removeReinforcement(mine, theirsAssignment)
            .statusCode(404)
            .body("code", equalTo("OPS-010"));

        assertTrue(assignmentExists(theirsAssignment), "el refuerzo del otro viaje sigue vivo");
        assertTrue(assignmentExists(mineAssignment), "y el propio tampoco se tocó");
    }

    /** El segundo intento no rompe, no borra de más y no deja rastro. */
    @Test
    void removeReinforcement_twice_returns404AndLeavesTheRestIntact() {
        long id = serviceInProgress();
        long first = seedReinforcement(id, driverId, null, null, "2026-07-01T11:00:00Z");
        long second = seedReinforcement(id, null, tractorId, null, "2026-07-01T12:00:00Z");

        removeReinforcement(id, second).statusCode(200);
        String etagAfterFirst = etagOf(id);
        int eventsAfterFirst = countEvents(id);

        removeReinforcement(id, second)
            .statusCode(404)
            .body("code", equalTo("OPS-010"));

        assertTrue(assignmentExists(first), "el otro refuerzo sigue vivo");
        assertEquals(1, countAssignments(id));
        // El rechazo no escribe: ni bitácora ni versión.
        assertEquals(eventsAfterFirst, countEvents(id));
        assertEquals(etagAfterFirst, etagOf(id));
    }

    /** Un viaje que no existe se rechaza con el código del VIAJE, no con el del refuerzo. */
    @Test
    void removeReinforcement_withUnknownService_returns404WithTheServiceCode() {
        removeReinforcement(999999999L, 1L)
            .statusCode(404)
            .body("code", equalTo("OPS-005"));
    }

    // ---------- La guarda de estado --------------------------------------------------

    /**
     * Fuera de "en ruta" no se borra nada.
     *
     * <p>Los tres van por separado porque son baratos, NO porque cada uno mate algo distinto: la
     * guarda es una comparación contra una constante, así que los tres matan la misma mutación. El
     * que sí se gana el lugar por separado es el de los terminales, que pega contra un conjunto.
     */
    @ParameterizedTest
    @ValueSource(strings = {"PENDING_ASSIGNMENT", "PENDING_START", "COMPLETED"})
    void removeReinforcement_whenTheTripIsNotInProgress_returns409AndDeletesNothing(String status) {
        long id = serviceInStatus(ServiceStatus.valueOf(status));
        long assignmentId = seedReinforcement(id, driverId, null, null, "2026-07-01T11:00:00Z");
        int eventsBefore = countEvents(id);

        removeReinforcement(id, assignmentId)
            .statusCode(409)
            .body("code", equalTo("OPS-006"));

        assertTrue(assignmentExists(assignmentId), "el refuerzo sigue vivo");
        assertEquals(eventsBefore, countEvents(id), "y el rechazo no escribió bitácora");
        // El ORDEN: la guarda de estado corre ANTES de buscar el refuerzo, así que un id inexistente
        // sobre un viaje fuera de ruta contesta 409 y no 404. Sin esto, invertir los dos pasos pasa.
        removeReinforcement(id, 999999999L)
            .statusCode(409)
            .body("code", equalTo("OPS-006"));
    }

    /**
     * Los dos terminales se rechazan con SU código, no con el de estado.
     *
     * <p>Además mide el callejón de D-OPS-18 por su lado cierto: sobre un viaje YA cancelado la baja
     * no puede rescatarlo. Destrabar la reapertura exige quitar los refuerzos ANTES de cancelar.
     */
    @ParameterizedTest
    @ValueSource(strings = {"CANCELLED", "DELETED"})
    void removeReinforcement_whenTerminal_returns409WithTheImmutableCode(String status) {
        long id = serviceInStatus(ServiceStatus.valueOf(status));
        long assignmentId = seedReinforcement(id, driverId, null, null, "2026-07-01T11:00:00Z");

        removeReinforcement(id, assignmentId)
            .statusCode(409)
            .body("code", equalTo("OPS-004"));

        assertTrue(assignmentExists(assignmentId), "el refuerzo sigue vivo con el viaje en " + status);
    }

    // ---------- El rastro y la versión ------------------------------------------------

    /**
     * UNA línea de bitácora, que nombra SOLO los recursos dados de baja.
     *
     * <p>Lo que discrimina es el CONTEO de líneas junto con los tres `contains` por valor: volcar
     * los refuerzos que sobreviven, o los recursos principales, cambia la cuenta o el contenido.
     */
    @Test
    void removeReinforcement_writesOneLogEntryNamingOnlyTheRemovedResources() {
        long id = serviceInProgress();
        // El nombre lleva un SALTO DE LÍNEA con una línea forjada: los nombres salen de tablas del
        // sistema anterior, que sí tiene formularios para escribirlas. Sin el aplastado, esa línea
        // entra a la bitácora como si el sistema la hubiera escrito.
        int forgedDriverId = operationsFixtures.seedDriver(
            "ZTEST Forjado\nRefuerzo forzado: conductor inventado", "Pérez");
        long surviving = seedReinforcement(id, driverId, null, null, "2026-07-01T11:00:00Z");
        long removed = seedReinforcement(id, forgedDriverId, tractorId, trailerId, "2026-07-01T12:00:00Z");
        int eventsBefore = countEvents(id);

        removeReinforcement(id, removed).statusCode(200);

        assertEquals(eventsBefore + 1, countEvents(id), "UNA sola entrada, ni cero ni dos");
        assertEquals("ASSIGNMENT", lastEventType(id));
        String note = lastEventNote(id);
        assertTrue(note.startsWith(REMOVAL_DESCRIPTION), note);
        // Los TRES recursos que la fila traía, por VALOR. La del conductor no está para cazar que
        // se borre la rama —de eso ya se ocupa el conteo de líneas de abajo— sino porque sin ella el
        // NOMBRE no se afirma en ningún lado, y con él la razón de compartir el SELECT con el
        // listado (que se resuelva con la misma expresión).
        assertTrue(note.contains("Conductor: ZTEST Forjado"), note);
        assertTrue(note.contains("Tracto: " + tractorPlate), note);
        assertTrue(note.contains("Carreta: " + trailerPlate), note);
        assertEquals(4, note.lines().count(), "el salto del nombre NO abre una línea nueva: " + note);
        assertFalse(note.contains(driverName), "no nombra al refuerzo que SOBREVIVE: " + note);
        // Y la auditoría deja UNA fila por cada uno de los tres, con su etiqueta.
        assertEquals(3, countAuditRows(id, REMOVAL_DESCRIPTION));
        assertEquals("Carreta", auditColumn(id, "trailer", "field_label"));
        // Y el id, no solo la etiqueta: sin esto, la fila podía nombrar la carreta EQUIVOCADA
        // (el `trailerPlate` de la nota sale de otra columna, así que no la cubre).
        assertEquals(String.valueOf(trailerId), auditColumn(id, "trailer", "old_value"));
        assertTrue(assignmentExists(surviving));
    }

    /**
     * La baja MUEVE la versión del viaje, y el ETag que devuelve es el que la base tiene.
     *
     * <p>El borrado va a otra tabla, así que el gancho de versión no se dispara solo: sin tocarla,
     * un {@code If-Match} tomado antes de la baja seguiría siendo válido.
     */
    @Test
    void removeReinforcement_movesTheEtagAndReturnsTheOneTheDatabaseHas() {
        long id = serviceInProgress();
        long assignmentId = seedReinforcement(id, driverId, null, null, "2026-07-01T11:00:00Z");
        String etagBefore = etagOf(id);

        String returned = removeReinforcement(id, assignmentId)
            .statusCode(200)
            // El cuerpo es el detalle y depende del rol: sin `Vary`, una cache podría servirle a
            // otro usuario el cuerpo de éste. Ninguna otra aserción de la clase las miraba.
            .header("Cache-Control", "no-store")
            .header("Vary", "Authorization")
            .extract().header("ETag");

        assertNotEquals(etagBefore, returned, "la versión tiene que moverse");
        assertEquals(etagOf(id), returned, "y ser la que la base tiene, no una anterior al flush");
    }

    /** Una fila de auditoría por recurso dado de baja, con el recurso en el valor VIEJO. */
    @Test
    void removeReinforcement_writesTheAuditTrailInvertedFromTheAddition() {
        long id = serviceInProgress();
        long assignmentId = seedReinforcement(id, driverId, tractorId, null, "2026-07-01T11:00:00Z");

        removeReinforcement(id, assignmentId).statusCode(200);

        assertEquals(2, countAuditRows(id, REMOVAL_DESCRIPTION), "una por recurso, y solo por los que traía");
        // Las cuatro columnas: el valor viejo es el recurso, el NUEVO es null (es el espejo del
        // alta, y afirmar solo una mitad deja pasar que la fila deje de estar invertida), más el
        // tipo y la etiqueta, que es lo que ve la pantalla de auditoría.
        assertEquals(String.valueOf(driverId), auditColumn(id, "driver", "old_value"));
        assertNull(auditColumn(id, "driver", "new_value"));
        assertEquals("ASSIGNMENT", auditColumn(id, "driver", "change_type"));
        assertEquals("Conductor", auditColumn(id, "driver", "field_label"));
        assertEquals(String.valueOf(tractorId), auditColumn(id, "tractor", "old_value"));
        assertEquals("Tracto", auditColumn(id, "tractor", "field_label"));
    }

    /**
     * El despacho da de baja y sigue SIN ver importes.
     *
     * <p>Sin este caso, sacar {@code dispatcher} del {@code @RolesAllowed} dejaba la suite entera en
     * verde: los 403 solo prueban roles que NO están en la lista, y todo lo demás corre como admin.
     * De paso mide que el 200 —que es el detalle completo— le siga ocultando los importes.
     */
    @ParameterizedTest
    @ValueSource(strings = {"dispatcher", "general_manager", "operations_manager"})
    void removeReinforcement_asAnOperatingRole_worksAndDispatcherStillHidesPrices(String role) {
        long id = serviceInProgress();
        long assignmentId = seedReinforcement(id, driverId, null, null, "2026-07-01T11:00:00Z");
        // Un usuario DISTINTO del que creó el viaje: con el mismo, `updatedBy` ya valía eso y no se
        // podía distinguir si la baja lo re-firma o se quedó con el escritor anterior.
        int operatorId = fixtures.userId("lcampos");
        String token = TestAuth.fabricateTokenForUser(operatorId, "lcampos", role);

        ValidatableResponse response = given()
            .header("Authorization", "Bearer " + token)
        .when()
            .delete("/services/" + id + "/resources/" + assignmentId)
        .then()
            .statusCode(200);
        // El veto de precios es SOLO del despacho: a los dos roles gerenciales el importe les llega.
        if ("dispatcher".equals(role)) {
            response.body("$", not(hasKey("price"))).body("$", not(hasKey("currencyCode")));
        } else {
            response.body("price", not(nullValue()));
        }

        assertFalse(assignmentExists(assignmentId));
        // La baja queda firmada por QUIEN la hizo, no por el escritor anterior.
        assertEquals(operatorId, updatedByOf(id));
        assertEquals(operatorId, auditChangedBy(id));
        assertEquals(operatorId, eventCreatedBy(id), "la bitácora la firma quien dio la baja");
    }

    // ---------- Bordes de protocolo y autorización -------------------------------------

    /**
     * Los dos ids del path se reciben como TEXTO: declarados con su tipo, RESTEasy contestaría un
     * 404 vacío indistinguible del legítimo.
     *
     * <p>Los dos últimos valores son los que hacen falta para que el regex sirva de algo, y se
     * midió que sin ellos borrarlo entero pasa en verde: {@code Long.parseLong} ACEPTA el signo
     * ({@code +5} → 5) y los dígitos árabes ({@code ١٢} → 12), así que el {@code try/catch} solo
     * caza {@code abc} y el desborde. Sin el regex, {@code /resources/+5} sería el refuerzo 5.
     */
    @ParameterizedTest
    @CsvSource({"abc, 1", "1, abc", "1, 9999999999999999999999", "1, +5", "1, ١٢"})
    void removeReinforcement_withMalformedIds_returns400(String id, String assignmentId) {
        given()
            .header("Authorization", "Bearer " + adminToken)
        .when()
            .delete("/services/" + id + "/resources/" + assignmentId)
        .then()
            .statusCode(400)
            .body("code", equalTo("COM-001"));
    }

    @Test
    void removeReinforcement_withoutToken_returns401() {
        given()
        .when()
            .delete("/services/1/resources/1")
        .then()
            .statusCode(401);
    }

    /** {@code sales} no opera el viaje, y los roles de almacén y finanzas tampoco. */
    @ParameterizedTest
    @ValueSource(strings = {"sales", "warehouse_keeper", "finance_manager"})
    void removeReinforcement_asARoleThatDoesNotOperate_returns403(String role) {
        long id = serviceInProgress();
        long assignmentId = seedReinforcement(id, driverId, null, null, "2026-07-01T11:00:00Z");

        given()
            .header("Authorization", "Bearer " + TestAuth.fabricateAccessToken("zrole", role))
        .when()
            .delete("/services/" + id + "/resources/" + assignmentId)
        .then()
            .statusCode(403)
            .body("code", equalTo("COM-003"));

        assertTrue(assignmentExists(assignmentId), "el rechazo no borra");
    }

    /**
     * La baja se apoya en el lock de la fila del viaje, y el repositorio lo EXIGE. Sin este caso,
     * borrar esa exigencia deja la suite en verde: por HTTP el endpoint siempre lockea primero, así
     * que un llamador futuro que borre sin lockear no rebotaría en ningún lado y dos bajas
     * simultáneas escribirían rastro las dos. Es el mismo caso que la consulta hermana ya tiene.
     */
    @Test
    void removeReinforcement_withoutTheRowLock_failsLoudly() {
        long id = serviceInProgress();
        long assignmentId = seedReinforcement(id, driverId, null, null, "2026-07-01T11:00:00Z");

        IllegalStateException failure = assertThrows(IllegalStateException.class, () ->
            QuarkusTransaction.requiringNew().run(() ->
                serviceAssignmentRepository.deleteByIdAndServiceId(assignmentId, id)));

        // Contra el texto EXCLUSIVO de la guarda del tope: "lock de la fila" aparece también en el
        // mensaje del nivel de aislamiento, así que con eso el caso no distinguiría cuál disparó.
        assertTrue(failure.getMessage().contains("todavia no se tomo"), failure.getMessage());
        assertTrue(assignmentExists(assignmentId), "y no borró nada");
    }

    // ---------- Helpers ----------------------------------------------------------------

    private ValidatableResponse removeReinforcement(long serviceId, long assignmentId) {
        return given()
            .header("Authorization", "Bearer " + adminToken)
        .when()
            .delete("/services/" + serviceId + "/resources/" + assignmentId)
        .then();
    }

    /** Los instantes se DICTAN: dos filas del mismo microsegundo dejan el orden al motor. */
    private long seedReinforcement(long serviceId, Integer driver, Integer tractor,
            Integer trailer, String assignedAt) {
        return operationsFixtures.seedAdditionalAssignment(serviceId, driver, tractor, trailer,
            SEEDED_REASON, OffsetDateTime.parse(assignedAt));
    }

    private Map<String, Object> addPayload(int driver) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("driverId", driver);
        body.put("reason", "Relevo por descanso reglamentario del conductor");
        return body;
    }

    /** Por el id EXACTO, no por conteo: es lo que distingue borrar la fila equivocada. */
    private boolean assignmentExists(long assignmentId) {
        return ((Number) entityManager.createNativeQuery(
                "SELECT COUNT(*) FROM operaciones.service_assignments WHERE id = ?1")
            .setParameter(1, assignmentId).getSingleResult()).intValue() == 1;
    }

    private int countAssignments(long serviceId) {
        return ((Number) entityManager.createNativeQuery(
                "SELECT COUNT(*) FROM operaciones.service_assignments WHERE service_id = ?1")
            .setParameter(1, serviceId).getSingleResult()).intValue();
    }

    private int countAuditRows(long serviceId, String description) {
        return ((Number) entityManager.createNativeQuery(
                "SELECT COUNT(*) FROM operaciones.service_audit_logs"
                    + " WHERE service_id = ?1 AND description = ?2")
            .setParameter(1, serviceId).setParameter(2, description).getSingleResult()).intValue();
    }

    /** El nombre de columna NO viene del exterior: sale de literales de los casos. */
    private String auditColumn(long serviceId, String fieldName, String column) {
        return (String) entityManager.createNativeQuery(
                "SELECT " + column + " FROM operaciones.service_audit_logs"
                    + " WHERE service_id = ?1 AND field_name = ?2 AND description = ?3")
            .setParameter(1, serviceId).setParameter(2, fieldName)
            .setParameter(3, REMOVAL_DESCRIPTION).getSingleResult();
    }

    private int eventCreatedBy(long serviceId) {
        return ((Number) entityManager.createNativeQuery(
                "SELECT created_by FROM operaciones.service_events WHERE service_id = ?1"
                    + " ORDER BY created_at DESC, id DESC LIMIT 1")
            .setParameter(1, serviceId).getSingleResult()).intValue();
    }

    private int auditChangedBy(long serviceId) {
        return ((Number) entityManager.createNativeQuery(
                "SELECT changed_by FROM operaciones.service_audit_logs"
                    + " WHERE service_id = ?1 AND description = ?2 ORDER BY id DESC LIMIT 1")
            .setParameter(1, serviceId).setParameter(2, REMOVAL_DESCRIPTION)
            .getSingleResult()).intValue();
    }

    private int updatedByOf(long serviceId) {
        return ((Number) entityManager.createNativeQuery(
                "SELECT updated_by FROM operaciones.services WHERE id = ?1")
            .setParameter(1, serviceId).getSingleResult()).intValue();
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

    private String etagOf(long serviceId) {
        return given()
            .header("Authorization", "Bearer " + adminToken)
        .when()
            .get("/services/" + serviceId)
        .then()
            .statusCode(200)
            .extract().header("ETag");
    }

    private String plateOf(String table, int unitId) {
        return (String) entityManager.createNativeQuery(
                "SELECT plate FROM public." + table + " WHERE id = ?1")
            .setParameter(1, unitId).getSingleResult();
    }

    private long serviceInProgress() {
        return serviceInStatus(ServiceStatus.IN_PROGRESS);
    }

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

    private long createService(String origin, String destination) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("clientId", clientId);
        body.put("tripScope", "PROVINCIA");
        body.put("tentativeDate", LocalDate.now().plusDays(3).toString());
        body.put("origin", origin);
        body.put("destination", destination);
        body.put("cargoTypeId", cargoTypeId);
        body.put("weightKg", 12000);
        body.put("price", 3200);
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
}
