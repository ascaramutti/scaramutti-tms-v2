package com.scaramutti.tms.operations;

import com.scaramutti.tms.support.HermeticTestData;
import com.scaramutti.tms.support.OperationsTestData;
import com.scaramutti.tms.support.TestAuth;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
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
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integración de {@code GET /services/{id}}. Lo que se fija acá, más allá de la forma: que el
 * detalle aplique la MISMA regla de precios que el listado (el despacho no los ve) y que el ETag
 * que devuelve sea el que después van a exigir la edición y las transiciones.
 */
@QuarkusTest
class ServiceDetailResourceTest {

    @Inject HermeticTestData fixtures;
    @Inject OperationsTestData operationsFixtures;
    @Inject EntityManager entityManager;

    private int clientId;
    private int cargoTypeId;
    private int currencyId;
    private String adminToken;

    @BeforeEach
    void setUp() {
        clientId = fixtures.seedClient();
        cargoTypeId = fixtures.seedCargoType();
        currencyId = fixtures.currencyId("PEN");
        adminToken = TestAuth.adminToken();
    }

    @AfterEach
    void cleanup() {
        operationsFixtures.deleteTestServices();
        fixtures.cleanup();
    }

    // ---------- Forma de la respuesta -------------------------------------------

    /**
     * La respuesta COMPLETA contra los valores con los que se creó el viaje, campo por campo y por
     * valor: una respuesta de detalle es exactamente donde un campo mal mapeado pasa desapercibido
     * si solo se afirma que "no es null".
     */
    @Test
    void getService_returnsEveryFieldWithItsOwnValue() {
        LocalDate tentativeDate = LocalDate.now().plusDays(3);
        long id = createService("Sullana Origen", "Chiclayo Destino");

        given()
            .header("Authorization", "Bearer " + adminToken)
        .when()
            .get("/services/" + id)
        .then()
            .statusCode(200)
            .body("id", equalTo((int) id))
            .body("code", matchesPattern("SRV-\\d{4,}"))
            .body("client.id", equalTo(clientId))
            .body("client.name", equalTo(nameOfClient()))
            .body("origin", equalTo("Sullana Origen"))
            .body("destination", equalTo("Chiclayo Destino"))
            .body("tentativeDate", equalTo(tentativeDate.toString()))
            .body("tripScope", equalTo("PROVINCIA"))
            .body("cargoType.id", equalTo(cargoTypeId))
            .body("cargoType.name", equalTo(nameOfCargoType()))
            .body("weightKg", equalTo(12000f))
            .body("status", equalTo("PENDING_ASSIGNMENT"))
            .body("price", equalTo(3200f))
            .body("currencyCode", equalTo("PEN"))
            .body("createdBy.username", equalTo("admin"))
            .body("createdAt", notNullValue())
            .body("updatedAt", notNullValue());
    }

    /** Las medidas y las observaciones son opcionales: ausentes en el alta viajan en null. */
    @Test
    void getService_withoutOptionalMeasures_returnsThemAsNull() {
        long id = createService("Piura", "Lima");

        given()
            .header("Authorization", "Bearer " + adminToken)
        .when()
            .get("/services/" + id)
        .then()
            .statusCode(200)
            .body("lengthM", nullValue())
            .body("widthM", nullValue())
            .body("heightM", nullValue())
            .body("observations", nullValue());
    }

    /**
     * El cuerpo depende del rol, así que ningún cache intermedio puede guardarlo: serviría la
     * respuesta de un rol a otro.
     */
    @Test
    void getService_isNotCacheable() {
        long id = createService("Piura", "Lima");

        given()
            .header("Authorization", "Bearer " + adminToken)
        .when()
            .get("/services/" + id)
        .then()
            .statusCode(200)
            .header("Cache-Control", equalTo("no-store"));
    }

    // ---------- Bitácora ---------------------------------------------------------

    /** El alta deja su línea, con el autor y el tipo de evento. */
    @Test
    void getService_returnsTheCreationEntryOfTheLog() {
        long id = createService("Piura", "Lima");

        given()
            .header("Authorization", "Bearer " + adminToken)
        .when()
            .get("/services/" + id)
        .then()
            .statusCode(200)
            .body("events", hasSize(1))
            .body("events[0].eventType", equalTo("CREATED"))
            .body("events[0].note", notNullValue())
            .body("events[0].createdBy.username", equalTo("admin"))
            .body("events[0].createdAt", notNullValue());
    }

    /**
     * La bitácora viaja del evento más viejo al más nuevo. Se fuerzan tres instantes DESORDENADOS
     * respecto del orden de inserción: sin la cláusula de orden, la base devolvería el orden de
     * inserción y el test pasaría igual sin probar nada.
     */
    @Test
    void getService_returnsTheLogOldestFirst() {
        long id = createService("Piura", "Lima");
        long first = onlyEventId(id);
        long second = insertEvent(id, "STATUS_CHANGE", "segunda", "2026-03-03T10:00:00Z");
        long third = insertEvent(id, "FIELD_EDIT", "tercera", "2026-01-01T10:00:00Z");
        forceEventCreatedAt(first, "2026-02-02T10:00:00Z");

        given()
            .header("Authorization", "Bearer " + adminToken)
        .when()
            .get("/services/" + id)
        .then()
            .statusCode(200)
            // enero, febrero, marzo: el tercero insertado va primero
            .body("events.id", contains((int) third, (int) first, (int) second));
    }

    /** Dos líneas del mismo instante se desempatan por id, para que el orden no quede al azar. */
    @Test
    void getService_withTwoEntriesAtTheSameInstant_breaksTheTieById() {
        long id = createService("Piura", "Lima");
        long first = onlyEventId(id);
        long second = insertEvent(id, "FIELD_EDIT", "misma hora", "2026-02-02T10:00:00Z");
        forceEventCreatedAt(first, "2026-02-02T10:00:00Z");

        given()
            .header("Authorization", "Bearer " + adminToken)
        .when()
            .get("/services/" + id)
        .then()
            .statusCode(200)
            .body("events.id", contains((int) first, (int) second));
    }

    /**
     * La bitácora es la del viaje pedido y solo esa. Sin dos viajes con líneas propias, un
     * repositorio que olvidara el filtro devolvería la bitácora de TODOS y la suite no se
     * enteraría: pasaría a mostrarle a cualquiera las notas de viajes ajenos.
     */
    @Test
    void getService_returnsOnlyTheLogOfTheRequestedService() {
        long one = createService("Piura", "Lima");
        long other = createService("Piura", "Trujillo");
        long ownEntry = onlyEventId(one);
        long ownSecond = insertEvent(one, "FIELD_EDIT", "propia", "2027-02-02T10:00:00Z");
        long foreignEntry = onlyEventId(other);
        insertEvent(other, "FIELD_EDIT", "ajena", "2027-02-02T10:00:00Z");

        given()
            .header("Authorization", "Bearer " + adminToken)
        .when()
            .get("/services/" + one)
        .then()
            .statusCode(200)
            .body("events", hasSize(2))
            .body("events.id", contains((int) ownEntry, (int) ownSecond))
            .body("events.id", not(hasItem((int) foreignEntry)))
            .body("events.note", not(hasItem("ajena")));
    }

    /**
     * El creador del viaje NO siempre está entre los autores de la bitácora, y ese es el camino
     * que el lote no cubre: si el respaldo se rompiera, el detalle saldría sin creador en vez de
     * fallar.
     */
    @Test
    void getService_whenTheCreatorWroteNoLogEntry_stillReturnsThem() {
        long id = createService("Piura", "Lima");
        int anotherUserId = anyOtherUserId();
        reassignEventAuthor(onlyEventId(id), anotherUserId);

        given()
            .header("Authorization", "Bearer " + adminToken)
        .when()
            .get("/services/" + id)
        .then()
            .statusCode(200)
            .body("createdBy.username", equalTo("admin"))
            .body("events[0].createdBy.username", not(equalTo("admin")));
    }

    // ---------- ETag --------------------------------------------------------------

    /**
     * El ETag es lo que van a exigir la edición y las transiciones. Se verifica lo que de verdad
     * importa para el cliente: que venga, que tenga la forma que manda el protocolo (entre
     * comillas) y que dos lecturas sin cambios devuelvan el MISMO valor.
     *
     * <p>Lo que NO se afirma, porque no es cierto: que sea opaco. El helper compartido lo arma
     * con la última actualización del recurso entre comillas, así que es reconstruible desde el
     * cuerpo. Lo que se pide al cliente es que lo reenvíe tal cual, no que lo adivine: las
     * comillas son parte del valor y armarlo a mano termina en un 412 inexplicable.
     */
    @Test
    void getService_returnsAStableQuotedEtag() {
        long id = createService("Piura", "Lima");

        String etag = etagOf(id);
        assertNotNull(etag);
        assertTrue(etag.startsWith("\"") && etag.endsWith("\""),
            "el ETag viaja entre comillas: " + etag);
        assertEquals(etag, etagOf(id), "dos lecturas sin cambios tienen que dar el mismo ETag");
    }

    /**
     * LA propiedad de la que depende todo el bloqueo optimista: el ETag está atado a la VERSIÓN
     * del recurso, así que cambia cuando el recurso cambia. Sin este caso, un ETag constante
     * pasaría los demás tests (viene, tiene comillas, es estable) y el {@code If-Match} de la
     * edición y de las transiciones calzaría SIEMPRE: el bloqueo quedaría muerto en silencio.
     *
     * <p>La versión se mueve por SQL porque todavía no hay ningún endpoint que edite un viaje.
     */
    @Test
    void getService_whenTheVersionChanges_returnsADifferentEtag() {
        long id = createService("Piura", "Lima");
        String before = etagOf(id);

        forceUpdatedAt(id, "2027-05-05T10:00:00Z");

        assertNotEquals(before, etagOf(id),
            "el ETag tiene que seguir a la versión del recurso, no ser un valor fijo");
    }

    // ---------- Precios por rol ----------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"admin", "sales", "general_manager", "operations_manager"})
    void getService_asRoleWithPrices_includesPriceAndCurrency(String role) {
        long id = createService("Piura", "Lima");
        String token = TestAuth.fabricateAccessToken("usuario", role);

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/services/" + id)
        .then()
            .statusCode(200)
            .body("price", equalTo(3200f))
            .body("currencyCode", equalTo("PEN"));
    }

    /** Los campos tienen que estar AUSENTES del JSON, no en null: es lo que declara el contrato. */
    @Test
    void getService_asDispatcher_omitsPriceAndCurrency() {
        long id = createService("Piura", "Lima");
        String token = TestAuth.fabricateAccessToken("despacho", "dispatcher");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/services/" + id)
        .then()
            .statusCode(200)
            .body("$", not(hasKey("price")))
            .body("$", not(hasKey("currencyCode")))
            // el resto del detalle SÍ lo ve: opera los viajes
            .body("code", notNullValue())
            .body("client.name", notNullValue())
            .body("events", hasSize(1));
    }

    /** El veto manda sobre la lista positiva: dos roles no suman permiso. */
    @Test
    void getService_asDispatcherWithAnotherPricedRole_stillOmitsPrices() {
        long id = createService("Piura", "Lima");
        String token = TestAuth.fabricateAccessTokenWithRoles("mixto", Set.of("dispatcher", "sales"));

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/services/" + id)
        .then()
            .statusCode(200)
            .body("$", not(hasKey("price")))
            .body("$", not(hasKey("currencyCode")));
    }

    // ---------- No encontrado y validación ------------------------------------------

    /**
     * Un viaje ELIMINADO sí se ve por su detalle. Queda fuera del LISTADO (es un registro que
     * nunca debió existir y no tiene por qué ensuciar la vista operativa), pero el registro y su
     * bitácora siguen siendo auditables: quien tenga el enlace directo tiene que poder ver qué
     * pasó y quién lo eliminó, que es justamente para lo que se guarda en vez de borrarse.
     */
    @Test
    void getService_whenDeleted_isStillVisibleByItsOwnLink() {
        long id = createService("Piura", "Lima");
        forceStatus(id, "DELETED");

        given()
            .header("Authorization", "Bearer " + adminToken)
        .when()
            .get("/services/" + id)
        .then()
            .statusCode(200)
            .body("status", equalTo("DELETED"))
            .body("events", hasSize(1));
    }

    @Test
    void getService_withUnknownId_returns404() {
        given()
            .header("Authorization", "Bearer " + adminToken)
        .when()
            .get("/services/999999999")
        .then()
            .statusCode(404)
            .contentType("application/problem+json")
            .body("code", equalTo("OPS-005"))
            .body("detail", notNullValue());
    }

    /**
     * Un id que no es un número responde 400 CON CUERPO, no el 404 vacío del framework: ese 404
     * se confunde con el de "ese viaje no existe" y no dice por qué.
     */
    @ParameterizedTest
    @ValueSource(strings = {"abc", "9999999999999999999999"})
    void getService_withMalformedId_returns400(String id) {
        given()
            .header("Authorization", "Bearer " + adminToken)
        .when()
            .get("/services/" + id)
        .then()
            .statusCode(400)
            .contentType("application/problem+json")
            .body("code", equalTo("COM-001"))
            .body("detail", notNullValue());
    }

    // ---------- Autorización ---------------------------------------------------------

    @Test
    void getService_withoutToken_returns401() {
        given()
        .when()
            .get("/services/1")
        .then()
            .statusCode(401);
    }

    @Test
    void getService_asWarehouseKeeper_returns403() {
        long id = createService("Piura", "Lima");
        String token = TestAuth.fabricateAccessToken("almacenero", "warehouse_keeper");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/services/" + id)
        .then()
            .statusCode(403);
    }

    // ---------- Helpers ----------------------------------------------------------------

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

    private String etagOf(long serviceId) {
        return given()
            .header("Authorization", "Bearer " + adminToken)
        .when()
            .get("/services/" + serviceId)
        .then()
            .statusCode(200)
            .extract().header("ETag");
    }

    private String nameOfCargoType() {
        return (String) entityManager.createNativeQuery(
                "SELECT name FROM public.cargo_types WHERE id = ?1")
            .setParameter(1, cargoTypeId).getSingleResult();
    }

    private void forceUpdatedAt(long serviceId, String instant) {
        QuarkusTransaction.requiringNew().run(() -> entityManager.createNativeQuery(
                "UPDATE operaciones.services SET updated_at = CAST(?1 AS timestamptz) WHERE id = ?2")
            .setParameter(1, instant).setParameter(2, serviceId).executeUpdate());
    }

    private void forceStatus(long serviceId, String status) {
        QuarkusTransaction.requiringNew().run(() -> entityManager.createNativeQuery(
                "UPDATE operaciones.services SET status = ?1 WHERE id = ?2")
            .setParameter(1, status).setParameter(2, serviceId).executeUpdate());
    }

    private int anyOtherUserId() {
        return ((Number) entityManager.createNativeQuery(
                "SELECT id FROM public.users WHERE username <> 'admin' AND is_active ORDER BY id LIMIT 1")
            .getSingleResult()).intValue();
    }

    private void reassignEventAuthor(long eventId, int userId) {
        QuarkusTransaction.requiringNew().run(() -> entityManager.createNativeQuery(
                "UPDATE operaciones.service_events SET created_by = ?1 WHERE id = ?2")
            .setParameter(1, userId).setParameter(2, eventId).executeUpdate());
    }

    private String nameOfClient() {
        return (String) entityManager.createNativeQuery(
                "SELECT name FROM public.clients WHERE id = ?1")
            .setParameter(1, clientId).getSingleResult();
    }

    private long onlyEventId(long serviceId) {
        return ((Number) entityManager.createNativeQuery(
                "SELECT id FROM operaciones.service_events WHERE service_id = ?1")
            .setParameter(1, serviceId).getSingleResult()).longValue();
    }

    /**
     * Inserta una línea de bitácora por SQL: los eventos los producen las transiciones de estado
     * y la edición, endpoints que todavía no existen, y estos tests necesitan una bitácora con
     * más de una línea.
     */
    private long insertEvent(long serviceId, String eventType, String note, String instant) {
        return QuarkusTransaction.requiringNew().call(() ->
            ((Number) entityManager.createNativeQuery(
                    "INSERT INTO operaciones.service_events"
                        + " (service_id, event_type, note, created_by, created_at)"
                        + " SELECT ?1, ?2, ?3, created_by, CAST(?4 AS timestamptz)"
                        + " FROM operaciones.services WHERE id = ?1 RETURNING id")
                .setParameter(1, serviceId).setParameter(2, eventType)
                .setParameter(3, note).setParameter(4, instant)
                .getSingleResult()).longValue());
    }

    private void forceEventCreatedAt(long eventId, String instant) {
        QuarkusTransaction.requiringNew().run(() -> entityManager.createNativeQuery(
                "UPDATE operaciones.service_events SET created_at = CAST(?1 AS timestamptz) WHERE id = ?2")
            .setParameter(1, instant).setParameter(2, eventId).executeUpdate());
    }
}
