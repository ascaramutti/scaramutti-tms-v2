package com.scaramutti.tms.operations;

import com.scaramutti.tms.support.HermeticTestData;
import com.scaramutti.tms.support.OperationsTestData;
import com.scaramutti.tms.support.TestAuth;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import io.restassured.response.ValidatableResponse;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.scaramutti.tms.shared.util.DateUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integración de {@code PUT /services/{id}}.
 *
 * <p>Lo que se fija acá, más allá de la forma: que el bloqueo optimista sea REAL (el mismo ETag
 * no sirve dos veces), que un reenvío idéntico del formulario no escriba nada (ni siquiera con
 * los mismos números escritos distinto), que las fechas reales se corrijan pero no se fijen, y
 * que los tres campos inmutables no se puedan mover por más que viajen en el cuerpo.
 */
@QuarkusTest
class ServiceUpdateResourceTest {

    private static final String JUSTIFICATION = "El cliente cambió el punto de entrega";

    @Inject HermeticTestData fixtures;
    @Inject OperationsTestData operationsFixtures;
    @Inject EntityManager entityManager;
    @Inject DataSource dataSource;

    private int clientId;
    private int cargoTypeId;
    private int currencyId;
    private int otherCurrencyId;
    /** Se siembra SOLO en los dos casos que la necesitan; ver {@link #disposableCurrency()}. */
    private Integer disposableCurrencyId;
    private String adminToken;

    @BeforeEach
    void setUp() {
        clientId = fixtures.seedClient();
        cargoTypeId = fixtures.seedCargoType();
        currencyId = fixtures.currencyId("PEN");
        otherCurrencyId = fixtures.currencyId("USD");
        adminToken = TestAuth.adminToken();
    }

    @AfterEach
    void cleanup() {
        operationsFixtures.deleteTestServices();
        // La sintética se borra DESPUÉS de los servicios que la referencian, y solo si algún caso
        // la sembró. Los casos que dan de baja una moneda usan esa y nunca PEN o USD:
        // `public.currencies` lo comparte el sistema anterior corriendo, y apagarle una moneda
        // —aunque sea por un instante— le rompe el wizard de cotizaciones.
        if (disposableCurrencyId != null) {
            fixtures.cleanupDisposableCurrency();
            disposableCurrencyId = null;
        }
        fixtures.cleanup();
    }

    /** Siembra la moneda descartable al primer uso, para que exista el menor tiempo posible. */
    private int disposableCurrency() {
        if (disposableCurrencyId == null) {
            disposableCurrencyId = fixtures.disposableCurrencyId();
        }
        return disposableCurrencyId;
    }

    // ---------- Camino feliz ----------------------------------------------------

    /**
     * Cada campo editable, con su propio valor y comprobado uno por uno: una respuesta de detalle
     * es justamente donde un campo mal asignado pasa desapercibido si solo se afirma que cambió
     * "algo". Se cruzan a propósito origen y destino respecto del alta, que es el error de
     * asignación que un test laxo nunca ve.
     */
    @Test
    void update_changesEveryEditableFieldAndReturnsTheDetail() {
        long id = createService();
        LocalDate newDate = LocalDate.now().plusDays(20);
        String tentativeDateBefore = detailOf(id).getString("tentativeDate");

        Map<String, Object> payload = payloadOf(id);
        payload.put("tentativeDate", newDate.toString());
        payload.put("origin", "Lima");
        payload.put("destination", "Piura");
        payload.put("weightKg", 15500.5);
        payload.put("lengthM", 12.5);
        payload.put("widthM", 2.6);
        payload.put("heightM", 3.1);
        payload.put("price", 4100.75);
        payload.put("currencyId", otherCurrencyId);
        payload.put("observations", "Ingreso por la puerta 3");

        given()
            .header("Authorization", "Bearer " + adminToken)
            .header("If-Match", etagOf(id))
            .contentType(ContentType.JSON)
            .body(payload)
        .when()
            .put("/services/" + id)
        .then()
            .statusCode(200)
            .body("id", equalTo((int) id))
            .body("tentativeDate", equalTo(newDate.toString()))
            .body("origin", equalTo("Lima"))
            .body("destination", equalTo("Piura"))
            .body("weightKg", equalTo(15500.5f))
            .body("lengthM", equalTo(12.5f))
            .body("widthM", equalTo(2.6f))
            .body("heightM", equalTo(3.1f))
            .body("price", equalTo(4100.75f))
            .body("currencyCode", equalTo("USD"))
            .body("observations", equalTo("Ingreso por la puerta 3"))
            // el estado no lo mueve la edición: eso es la transición
            .body("status", equalTo("PENDING_ASSIGNMENT"));

        // La lista COMPLETA, no "hubo cambios". El diff no solo arma el rastro: como el camino
        // sin cambios devuelve antes de aplicar nada, un campo que se caiga de ahí deja de
        // guardarse EN SILENCIO, con 200 y el valor viejo. Afirmar la lista entera es lo único
        // que hace fallar esa mutación campo por campo.
        assertEquals(
            List.of("currency", "destination", "height", "length", "observations",
                "origin", "price", "tentativeDate", "weight", "width"),
            auditFieldNames(id));
        // Y sus etiquetas, que son lo único que la interfaz va a mostrar cuando la auditoría se
        // exponga: sin afirmarlas, cruzar dos (poner "Origen" en el destino) o borrarlas todas
        // deja la suite entera en verde.
        assertEquals(
            List.of("Moneda", "Destino", "Alto (m)", "Largo (m)", "Observaciones",
                "Origen", "Precio", "Fecha tentativa", "Peso (kg)", "Ancho (m)"),
            auditFieldLabels(id));
        // Y los VALORES de los dos lados. Sin esto, invertir viejo↔nuevo en cualquiera de los
        // diez `addChange` deja la auditoría mintiendo al revés y la suite entera en verde.
        assertEquals(
            List.of("PEN", "Lima", "(sin valor)", "(sin valor)", "(sin valor)",
                "Piura", "3200", tentativeDateBefore, "12000", "(sin valor)"),
            auditOldValues(id).stream().map(v -> v == null ? "(sin valor)" : v).toList());
        assertEquals(
            List.of("USD", "Piura", "3.1", "12.5", "Ingreso por la puerta 3",
                "Lima", "4100.75", newDate.toString(), "15500.5", "2.6"),
            auditNewValues(id));
    }

    /**
     * Los nombres de la auditoría son los de la ENTIDAD, no los de la API: `weight` y no
     * `weightKg`, `currency` y no `currencyId`. Vale fijarlo porque los tres campos que más se
     * miran en el resto de la suite (`origin`, `price`, `startDateTime`) son justo los que
     * coinciden en ambos lados, así que la diferencia no se notaría.
     */
    @Test
    void update_auditNamesTheEntityFieldsNotTheApiOnes() {
        long id = createService();
        Map<String, Object> payload = payloadOf(id);
        payload.put("weightKg", 15500);
        payload.put("currencyId", otherCurrencyId);

        update(id, payload).statusCode(200);

        assertEquals(List.of("currency", "weight"), auditFieldNames(id));
    }

    /** Lo editado es lo que después se lee: la respuesta del PUT no es una foto optimista. */
    @Test
    void update_persistsWhatItAnswers() {
        long id = createService();
        Map<String, Object> payload = payloadOf(id);
        payload.put("origin", "Chimbote");
        payload.put("price", 9100);
        update(id, payload).statusCode(200);

        given()
            .header("Authorization", "Bearer " + adminToken)
        .when()
            .get("/services/" + id)
        .then()
            .statusCode(200)
            .body("origin", equalTo("Chimbote"))
            .body("price", equalTo(9100f));
    }

    /** Los textos libres se recortan, igual que en el alta. */
    @Test
    void update_trimsFreeText() {
        long id = createService();
        Map<String, Object> payload = payloadOf(id);
        payload.put("origin", "   Tumbes   ");
        payload.put("observations", "   ");

        update(id, payload)
            .statusCode(200)
            .body("origin", equalTo("Tumbes"))
            .body("observations", nullValue());
    }

    @Test
    void update_isNotCacheable() {
        long id = createService();

        update(id, changedPayload(id))
            .statusCode(200)
            .header("Cache-Control", equalTo("no-store"))
            // El `Vary` es lo que sostiene la garantía el día que alguien saque el `no-store` por
            // rendimiento: el ETag no distingue el rol, así que sin él la misma versión
            // identificaría el cuerpo con importes y el que no los tiene.
            .header("Vary", equalTo("Authorization"));
    }

    /**
     * El token se fabrica pero apunta a un usuario REAL: editar firma la bitácora y la
     * auditoría con quien edita, así que un subject que no existe en la tabla no llega ni a
     * ejercer el permiso.
     */
    @ParameterizedTest
    @ValueSource(strings = {"admin", "sales", "general_manager", "operations_manager"})
    void update_asAnyEditingRole_isAllowed(String role) {
        long id = createService();
        int userId = fixtures.userId("admin");
        String token = TestAuth.fabricateTokenForUser(userId, "admin", role);

        given()
            .header("Authorization", "Bearer " + token)
            .header("If-Match", etagOf(id))
            .contentType(ContentType.JSON)
            .body(changedPayload(id))
        .when()
            .put("/services/" + id)
        .then()
            .statusCode(200);
    }

    /**
     * Las fechas reales viajan en el detalle. Sin esto la corrección que agrega este endpoint es
     * inusable de punta a punta: el formulario no puede mostrarlas ni precargarlas, no hay forma
     * de saber si el servicio ya las tiene (que es la precondición del PUT) y la única
     * confirmación de la corrección sería parsear el texto de la bitácora.
     */
    @Test
    void update_correctedRealDatesAreVisibleInTheDetail() {
        long id = createService();
        operationsFixtures.forceServiceStatus(id, "COMPLETED");
        forceRealDates(id, "2026-03-01T10:00:00Z", "2026-03-02T10:00:00Z");

        given()
            .header("Authorization", "Bearer " + adminToken)
        .when()
            .get("/services/" + id)
        .then()
            .statusCode(200)
            .body("startDateTime", equalTo("2026-03-01T10:00:00Z"))
            .body("endDateTime", equalTo("2026-03-02T10:00:00Z"));

        Map<String, Object> payload = payloadOf(id);
        payload.put("endDateTime", "2026-03-02T18:45:00Z");

        update(id, payload)
            .statusCode(200)
            .body("startDateTime", equalTo("2026-03-01T10:00:00Z"))
            .body("endDateTime", equalTo("2026-03-02T18:45:00Z"));
    }

    /**
     * Un servicio sin fechas reales las devuelve en null, no las omite: el cliente necesita
     * distinguir "todavía no arrancó" de "no me lo mandaron".
     */
    @Test
    void update_serviceWithoutRealDates_returnsThemAsNullInTheDetail() {
        long id = createService();

        given()
            .header("Authorization", "Bearer " + adminToken)
        .when()
            .get("/services/" + id)
        .then()
            .statusCode(200)
            .body("startDateTime", nullValue())
            .body("endDateTime", nullValue());
    }

    /**
     * Una observación guardada como CADENA VACÍA (lo que puede dejar la carga de datos del sistema
     * anterior, donde el alta de v2 pondría null) no es un cambio. Sin normalizar los dos lados, el
     * primer reenvío idéntico del formulario se leería como una edición de "" a null: rompería el
     * 200-sin-escribir y dejaría una línea de bitácora con un hueco donde va el valor viejo.
     */
    @Test
    void update_withLegacyEmptyStringObservations_isStillANoOp() {
        long id = createService();
        forceEmptyObservations(id);
        String etag = etagOf(id);

        given()
            .header("Authorization", "Bearer " + adminToken)
            .header("If-Match", etag)
            .contentType(ContentType.JSON)
            .body(payloadOf(id))
        .when()
            .put("/services/" + id)
        .then()
            .statusCode(200)
            .header("ETag", equalTo(etag));

        assertEquals(0, countAuditLogs(id, "FIELD_EDIT"));
        assertEquals(1, countEvents(id));
    }

    /**
     * Un byte NUL incrustado en un texto libre. PostgreSQL no lo admite dentro de un texto, así
     * que sin rechazarlo llega hasta el motor y sale un 500 donde el contrato promete un 400.
     * Sobrevive a todo lo demás: no lo saca el recorte, no lo tapa el mínimo de largo y para Java
     * es un carácter más. Es el mismo agujero que el listado ya cerró en su término de búsqueda,
     * por el otro camino de entrada.
     */
    @ParameterizedTest
    @ValueSource(strings = {"origin", "destination", "observations", "justification"})
    void update_withANulCharacterInFreeText_returns400NotAServerError(String field) {
        long id = createService();
        Map<String, Object> payload = payloadOf(id);
        payload.put(field, "CURLTEST \u0000 con nul");
        // que además sea un cambio real, para que llegue a escribirse
        payload.put("price", 4100);

        update(id, payload)
            .statusCode(400)
            .contentType("application/problem+json")
            .body("code", equalTo("COM-001"))
            .body("detail", containsString("no se pueden guardar"));
    }

    /** Un salto de línea SÍ es legítimo: la columna lo guarda y las observaciones lo usan. */
    @Test
    void update_withNewlinesInObservations_isAccepted() {
        long id = createService();
        Map<String, Object> payload = payloadOf(id);
        payload.put("observations", "Primera línea\nSegunda línea");

        update(id, payload)
            .statusCode(200)
            .body("observations", equalTo("Primera línea\nSegunda línea"));
    }

    // ---------- Inmutables ------------------------------------------------------

    /**
     * Cliente, ámbito y tipo de carga son inmutables. No alcanza con que no estén en el DTO: se
     * mandan igual, con valores VÁLIDOS de otras filas, y se comprueba que el viaje no se movió.
     * Un DTO al que alguien le agregue el campo de más rompe este test, que es de lo que se trata.
     */
    @Test
    void update_ignoresTheImmutableFieldsEvenIfTheyTravelInTheBody() {
        long id = createService();
        int anotherClientId = fixtures.seedClient();
        int anotherCargoTypeId = fixtures.seedCargoType();

        Map<String, Object> payload = changedPayload(id);
        payload.put("clientId", anotherClientId);
        payload.put("cargoTypeId", anotherCargoTypeId);
        payload.put("tripScope", "LOCAL");

        update(id, payload)
            .statusCode(200)
            .body("client.id", equalTo(clientId))
            .body("cargoType.id", equalTo(cargoTypeId))
            .body("tripScope", equalTo("PROVINCIA"));
    }

    // ---------- Versión y bloqueo optimista -------------------------------------

    /**
     * El ETag que devuelve el PUT es la version NUEVA, y es la que sirve para el próximo. Sin
     * esto el cliente tendría que releer el detalle después de cada edición para poder seguir
     * editando.
     */
    @Test
    void update_returnsTheNewVersionAsEtag() {
        long id = createService();
        String before = etagOf(id);

        String returned = given()
            .header("Authorization", "Bearer " + adminToken)
            .header("If-Match", before)
            .contentType(ContentType.JSON)
            .body(changedPayload(id))
        .when()
            .put("/services/" + id)
        .then()
            .statusCode(200)
            .extract().header("ETag");

        assertNotEquals(before, returned, "editar tiene que mover la versión del recurso");
        assertEquals(returned, etagOf(id), "el ETag del PUT tiene que ser el que después lee el GET");
    }

    /**
     * LA propiedad del bloqueo optimista: el mismo ETag no sirve dos veces. Sin este caso, un
     * {@code If-Match} que nunca se comparara pasaría igual todos los demás tests del camino
     * feliz, y dos usuarios editando a la vez se pisarían sin que nadie se entere.
     */
    @Test
    void update_withTheSameEtagTwice_returns412OnTheSecond() {
        long id = createService();
        String etag = etagOf(id);

        given()
            .header("Authorization", "Bearer " + adminToken)
            .header("If-Match", etag)
            .contentType(ContentType.JSON)
            .body(changedPayload(id))
        .when()
            .put("/services/" + id)
        .then()
            .statusCode(200);

        Map<String, Object> second = payloadOf(id);
        second.put("destination", "Otro destino");

        given()
            .header("Authorization", "Bearer " + adminToken)
            .header("If-Match", etag)
            .contentType(ContentType.JSON)
            .body(second)
        .when()
            .put("/services/" + id)
        .then()
            .statusCode(412)
            .contentType("application/problem+json")
            .body("code", equalTo("COM-004"));
    }

    /**
     * La razón de ser del bloqueo, y lo único que la prueba secuencial no puede dar por bueno: dos
     * ediciones SIMULTÁNEAS con el mismo ETag no pueden pasar las dos. Sin el lock de fila, las dos
     * leen la misma versión, las dos la dan por buena y la segunda escribe con la foto que tomó
     * antes de que la primera terminara. Acá eso no sería solo perder un dato: la auditoría y la
     * bitácora de la edición pisada quedan escritas, afirmando un cambio que en la fila ya no está.
     *
     * <p>Se repite el par para que un bloqueo roto caiga en la carrera con alta probabilidad. La
     * barrera sincroniza el disparo, no la lectura de la fila, así que el solapamiento no está
     * garantizado en cada vuelta: lo que de verdad sostiene el caso es el conteo del rastro, que
     * con el bloqueo roto queda en dos cuando la carrera SÍ ocurre.
     */
    @Test
    void update_twoConcurrentEdits_oneWinsAndTheOtherGets412() throws Exception {
        for (int i = 0; i < 5; i++) {
            // Ruta distinta por vuelta: el alta tiene una guarda anti doble-click que rechaza el
            // mismo cliente y la misma ruta del mismo usuario dentro de la ventana configurada.
            long id = createService("Piura " + i, "Lima " + i);
            String etag = etagOf(id);
            Map<String, Object> first = payloadOf(id);
            first.put("price", 4100);
            Map<String, Object> second = payloadOf(id);
            second.put("origin", "Sullana");

            CyclicBarrier barrier = new CyclicBarrier(2);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                Future<Integer> a = pool.submit(edit(barrier, id, etag, first));
                Future<Integer> b = pool.submit(edit(barrier, id, etag, second));
                int statusA = a.get(30, TimeUnit.SECONDS);
                int statusB = b.get(30, TimeUnit.SECONDS);

                // El perdedor puede salir 412 (alcanzó a releer la versión nueva) o 409 si se le
                // agotó la espera por el lock: las dos son "no ganaste", y cuál toca depende de
                // cuánto tarde el ganador. Lo que NO puede pasar, y es lo que fija el caso, es
                // que ganen los dos.
                long winners = Stream.of(statusA, statusB).filter(code -> code == 200).count();
                assertEquals(1, winners,
                    "iteración " + i + ": tenía que ganar exactamente una edición, fueron "
                        + statusA + " y " + statusB);
                assertTrue(Stream.of(statusA, statusB).allMatch(c -> c == 200 || c == 412 || c == 409),
                    "iteración " + i + ": códigos inesperados " + statusA + " y " + statusB);
                assertEquals(1, countAuditLogs(id, "FIELD_EDIT"),
                    "iteración " + i + ": el rastro tiene que reflejar UNA sola edición");
            } finally {
                pool.shutdownNow();
            }
        }
    }

    /**
     * Retiene la fila del servicio desde OTRA transacción y corre el bloque mientras dura.
     *
     * <p>El apagado del pool va en el finally MÁS EXTERNO y solo. Si compartiera finally con la
     * liberación de la barrera, cualquier falla de esas líneas se lo saltearía y quedarían
     * colgados el hilo (no-daemon) y su transacción — con la fila todavía bloqueada, que es justo
     * lo que después haría esperar al borrado del @AfterEach y apilar a los demás tests.
     */
    private void withRowLocked(long id, Runnable body) throws Exception {
        CyclicBarrier locked = new CyclicBarrier(2);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> holder = pool.submit(() -> QuarkusTransaction.requiringNew().run(() -> {
                entityManager.createNativeQuery(
                        "SELECT id FROM operaciones.services WHERE id = ?1 FOR UPDATE")
                    .setParameter(1, id).getSingleResult();
                awaitQuietly(locked);   // avisa que ya la tiene
                awaitQuietly(locked);   // espera a que el bloque haya terminado
            }));
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
            }
            holder.get(30, TimeUnit.SECONDS);
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

    private Callable<Integer> edit(CyclicBarrier barrier, long id, String etag, Map<String, Object> payload) {
        return () -> {
            barrier.await();   // las dos ediciones salen a la vez
            return given()
                .header("Authorization", "Bearer " + adminToken)
                .header("If-Match", etag)
                .contentType(ContentType.JSON)
                .body(payload)
            .when()
                .put("/services/" + id)
            .then()
                .extract().statusCode();
        };
    }

    /**
     * Con la fila tomada por otra operación, la espera por el lock se agota y eso es un 409 con
     * código propio, NO un 500. El tope de espera existe para que un lock trabado no inmovilice
     * el pool de conexiones; sin traducir el error que produce, cambiaba una espera infinita por
     * un error de servidor que el contrato de este endpoint no declara.
     *
     * <p>El lock se retiene desde OTRA transacción y se suelta recién cuando el PUT ya respondió.
     */
    @Test
    void update_whenTheRowIsLockedByAnotherOperation_returns409() throws Exception {
        long id = createService();
        String etag = etagOf(id);

        withRowLocked(id, () -> {
            given()
                .header("Authorization", "Bearer " + adminToken)
                .header("If-Match", etag)
                .contentType(ContentType.JSON)
                .body(changedPayload(id))
            .when()
                .put("/services/" + id)
            .then()
                .statusCode(409)
                .contentType("application/problem+json")
                .body("code", equalTo("OPS-008"))
                .body("detail", containsString("reintente"));

            // rechazado por lock quiere decir rechazado de verdad: ni una fila escrita
            assertEquals(0, countAuditLogs(id, "FIELD_EDIT"));
        });

        // y con el lock ya suelto, la misma edición pasa: el rechazo era transitorio
        update(id, changedPayload(id)).statusCode(200);
    }

    /**
     * Cambiar SOLO la justificación tampoco es un cambio. Es el no-op que un usuario produce de
     * verdad: escribe el porqué, se arrepiente de la corrección y manda igual. La justificación
     * no es un campo del viaje: acompaña a los cambios y sin cambios no acompaña nada.
     */
    @Test
    void update_changingOnlyTheJustification_writesNothing() {
        long id = createService();
        String etag = etagOf(id);
        Map<String, Object> onlyJustification = payloadOf(id);
        onlyJustification.put("justification", "Otro motivo distinto del anterior");

        update(id, onlyJustification).statusCode(200);

        assertEquals(etag, etagOf(id), "la versión se movió sin que cambiara ningún campo");
        assertEquals(0, countAuditLogs(id, "FIELD_EDIT"));
        assertEquals(1, countEvents(id), "se escribió bitácora por una edición que no ocurrió");
    }

    /**
     * Un dato guardado que difiere SOLO en espacios no se reescribe al editar otro campo.
     *
     * <p>La carga del sistema anterior puede dejar `origin` con un espacio al final. Como los
     * textos libres se comparan recortados, ese espacio no es un cambio; si igual se escribiera
     * el valor recortado, la base cambiaría sin fila de auditoría ni línea de bitácora, colado
     * en la edición de otro campo. En un módulo cuya razón de ser es la rendición de cuentas,
     * eso es exactamente lo que no puede pasar.
     */
    /**
     * Y cuando el texto SÍ cambia, la auditoría guarda el valor que estaba en la columna, con su
     * relleno y todo. Es lo único con lo que se puede reconstruir el estado previo, que es para lo
     * que existe la tabla: guardar ahí el valor recortado afirmaría que el dato ya venía limpio,
     * justo del sistema del que viene el relleno.
     */
    @Test
    void update_auditsTheStoredTextAsItWas_notTrimmed() {
        long id = createService();
        operationsFixtures.forceFreeText(id, "origin", "Piura  ");

        Map<String, Object> payload = payloadOf(id);
        payload.put("origin", "Sullana");
        update(id, payload).statusCode(200);

        assertEquals(List.of("Piura  "), auditOldValues(id));
        assertEquals(List.of("Sullana"), auditNewValues(id));
    }

    @ParameterizedTest
    @ValueSource(strings = {"origin", "destination", "observations"})
    void update_whenAStoredTextDiffersOnlyInSpaces_leavesItAloneAndSaysNothing(String column) {
        long id = createService();
        operationsFixtures.forceFreeText(id, column, "Piura  ");

        Map<String, Object> onlyThePrice = payloadOf(id);
        onlyThePrice.put("price", 9999);
        update(id, onlyThePrice).statusCode(200);

        assertEquals("Piura  ", storedFreeText(id, column), "se limpió el espacio sin dejar rastro");
        assertEquals(List.of("price"), auditFieldNames(id));
    }

    /**
     * Y el lock que importa es el de la LECTURA, no el del volcado. Con un cuerpo sin cambios
     * reales, la edición devuelve antes de escribir una sola fila: si el 409 apareciera igual
     * gracias al choque del UPDATE final, este caso daría 200. O sea que borrar el
     * `SELECT … FOR NO KEY UPDATE` deja el caso de arriba en verde y a este en rojo.
     */
    @Test
    void update_withoutChangesWhenTheRowIsLocked_returns409() throws Exception {
        long id = createService();
        Map<String, Object> unchanged = payloadOf(id);

        withRowLocked(id, () ->
            update(id, unchanged)
                .statusCode(409)
                .body("code", equalTo("OPS-008")));
    }

    /**
     * El conflicto de lock también se traduce cuando aparece AL ESCRIBIR, no solo al leer. La
     * bitácora es una fila hija: si su tabla está tomada, el INSERT espera y se rinde con el
     * mismo estado de PostgreSQL. Sin el envoltorio alrededor del bloque que escribe, eso sale
     * como 500; con él, es el mismo 409 transitorio que declara el contrato.
     */
    @Test
    void update_whenTheLogTableIsLocked_returns409() throws Exception {
        long id = createService();

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (Statement statement = connection.createStatement()) {
                    // EXCLUSIVE choca contra el INSERT y deja pasar las lecturas: la edición avanza
                    // hasta el momento de escribir la bitácora, que es donde tiene que rendirse.
                    statement.execute("LOCK TABLE operaciones.service_events IN EXCLUSIVE MODE");
                }

                update(id, changedPayload(id))
                    .statusCode(409)
                    .contentType("application/problem+json")
                    .body("code", equalTo("OPS-008"));
            } finally {
                // Suelta la tabla PASE LO QUE PASE. Si la aserción falla —que es justo el modo de
                // falla que este caso existe para detectar— sin este finally la conexión vuelve al
                // pool con el LOCK TABLE tomado, y el borrado del @AfterEach espera por él sin
                // tope: la suite cuelga y el síntoma deja de ser la aserción rota.
                connection.rollback();
                connection.setAutoCommit(true);
            }
        }

        // el rechazo no dejó nada a medio escribir
        assertEquals(0, countAuditLogs(id, "FIELD_EDIT"));
        // y con la tabla libre, la misma edición pasa
        update(id, changedPayload(id)).statusCode(200);
    }

    /**
     * La versión se verifica ANTES que el contenido del cuerpo. Sin fijarlo, mover `Etag.verify`
     * debajo de las validaciones sobrevive toda la suite y cambia el error que ve el usuario: le
     * diría "la moneda no existe" cuando su problema real es que alguien editó primero.
     */
    @Test
    void update_withStaleIfMatchAndAnInvalidBody_answersTheVersionFirst() {
        long id = createService();
        Map<String, Object> payload = payloadOf(id);
        payload.put("currencyId", 999_999);

        given()
            .header("Authorization", "Bearer " + adminToken)
            .header("If-Match", "\"2020-01-01T00:00:00Z\"")
            .contentType(ContentType.JSON)
            .body(payload)
        .when()
            .put("/services/" + id)
        .then()
            .statusCode(412)
            .body("code", equalTo("COM-004"));
    }

    /**
     * El tope de ENTEROS, no solo el de decimales, en los dos campos que tienen uno distinto: el
     * peso admite 8 cifras enteras y el precio 10, que es lo que aguantan sus columnas. Sin el
     * caso del precio, el contrato publica un tope que ningún test sostiene.
     */
    @Test
    void update_withTooManyIntegerDigits_returns400() {
        long id = createService();

        Map<String, Object> heavy = payloadOf(id);
        heavy.put("weightKg", new BigDecimal("999999999"));
        update(id, heavy).statusCode(400)
            .body("code", equalTo("COM-001")).body("errors.field", hasItem("weightKg"));

        Map<String, Object> expensive = payloadOf(id);
        expensive.put("price", new BigDecimal("99999999999"));
        update(id, expensive).statusCode(400)
            .body("code", equalTo("COM-001")).body("errors.field", hasItem("price"));
    }

    @Test
    void update_withoutIfMatch_returns412() {
        long id = createService();

        given()
            .header("Authorization", "Bearer " + adminToken)
            .contentType(ContentType.JSON)
            .body(changedPayload(id))
        .when()
            .put("/services/" + id)
        .then()
            .statusCode(412)
            .contentType("application/problem+json")
            .body("code", equalTo("COM-004"));
    }

    @Test
    void update_withStaleIfMatch_returns412() {
        long id = createService();

        given()
            .header("Authorization", "Bearer " + adminToken)
            .header("If-Match", "\"2020-01-01T00:00:00Z\"")
            .contentType(ContentType.JSON)
            .body(changedPayload(id))
        .when()
            .put("/services/" + id)
        .then()
            .statusCode(412)
            .body("code", equalTo("COM-004"));
    }

    /**
     * Un {@code If-Match} viejo NO tapa la razón real: el viaje cancelado ya no se edita, y eso
     * se contesta antes que el conflicto de versión. Si el orden se invirtiera, el usuario
     * recargaría el detalle para "resolver" un 412 y volvería a chocar contra lo mismo.
     */
    @Test
    void update_onACancelledServiceWithStaleEtag_answersTheImmutabilityFirst() {
        long id = createService();
        operationsFixtures.forceServiceStatus(id, "CANCELLED");

        given()
            .header("Authorization", "Bearer " + adminToken)
            .header("If-Match", "\"2020-01-01T00:00:00Z\"")
            .contentType(ContentType.JSON)
            .body(changedPayload(id))
        .when()
            .put("/services/" + id)
        .then()
            .statusCode(409)
            .body("code", equalTo("OPS-004"));
    }

    // ---------- Sin cambios (no-op) ---------------------------------------------

    /**
     * Reenviar el mismo formulario no es una edición: responde 200 y no escribe NADA. Se
     * comprueba lo que de verdad importa (la versión no se movió, la bitácora no creció, la
     * auditoría no registró nada), no solo el código de estado.
     */
    @Test
    void update_withoutRealChanges_returns200AndWritesNothing() {
        long id = createService();
        String etag = etagOf(id);

        String returned = given()
            .header("Authorization", "Bearer " + adminToken)
            .header("If-Match", etag)
            .contentType(ContentType.JSON)
            .body(payloadOf(id))
        .when()
            .put("/services/" + id)
        .then()
            .statusCode(200)
            .body("origin", equalTo("Piura"))
            .extract().header("ETag");

        assertEquals(etag, returned, "sin cambios la versión no se mueve");
        assertEquals(1, countEvents(id), "sin cambios no se escribe bitácora");
        assertEquals(0, countAuditLogs(id, "FIELD_EDIT"), "sin cambios no se escribe auditoría");
    }

    /**
     * El mismo importe escrito con otra escala sigue siendo el mismo importe. Comparar los
     * objetos en vez de su valor haría que {@code 3200} y {@code 3200.00} se leyeran como una
     * edición: cada reenvío del formulario mordería una versión nueva y una línea de bitácora
     * inventada.
     */
    @Test
    void update_withTheSameAmountsWrittenWithAnotherScale_isStillANoOp() {
        long id = createService();
        String etag = etagOf(id);

        // El detalle los devuelve con la escala de la columna (dos decimales); acá se reenvían
        // como enteros pelados, que es como los serializa un formulario que no formatea.
        Map<String, Object> payload = payloadOf(id);
        payload.put("price", 3200);
        payload.put("weightKg", 12000);

        given()
            .header("Authorization", "Bearer " + adminToken)
            .header("If-Match", etag)
            .contentType(ContentType.JSON)
            .body(payload)
        .when()
            .put("/services/" + id)
        .then()
            .statusCode(200)
            .header("ETag", equalTo(etag));

        assertEquals(0, countAuditLogs(id, "FIELD_EDIT"));
    }

    /**
     * La columna guarda MICROSEGUNDOS. Una marca con más precisión es, para la base, la misma
     * marca: sin truncarla antes de comparar se leería como una corrección, se escribiría una
     * línea de bitácora inventada y la versión del recurso se movería sola. Es el mismo bug que
     * obligó a truncar el {@code updatedAt} del que sale el ETag.
     */
    @Test
    void update_withSubMicrosecondPrecision_isStillANoOp() {
        long id = createService();
        operationsFixtures.forceServiceStatus(id, "IN_PROGRESS");
        forceRealDates(id, "2026-03-01T15:00:00.123456Z", null);
        String etag = etagOf(id);

        Map<String, Object> payload = payloadOf(id);
        payload.put("startDateTime", "2026-03-01T15:00:00.1234569Z");

        given()
            .header("Authorization", "Bearer " + adminToken)
            .header("If-Match", etag)
            .contentType(ContentType.JSON)
            .body(payload)
        .when()
            .put("/services/" + id)
        .then()
            .statusCode(200)
            .header("ETag", equalTo(etag));

        assertEquals(0, countAuditLogs(id, "FIELD_EDIT"));
        assertEquals(OffsetDateTime.parse("2026-03-01T15:00:00.123456Z"), storedStart(id));
    }

    /**
     * La misma marca de tiempo escrita en otro huso es el mismo instante, y la edición no puede
     * leerla como un cambio. (Hoy la normalización del huso ya la hace el lector de JSON; el
     * caso se fija igual porque es la garantía que ve el cliente, sin importar quién la cumpla.)
     */
    @Test
    void update_withTheSameInstantInAnotherOffset_isStillANoOp() {
        long id = createService();
        operationsFixtures.forceServiceStatus(id, "IN_PROGRESS");
        forceRealDates(id, "2026-03-01T15:00:00Z", null);
        String etag = etagOf(id);

        Map<String, Object> payload = payloadOf(id);
        payload.put("startDateTime", "2026-03-01T10:00:00-05:00");

        given()
            .header("Authorization", "Bearer " + adminToken)
            .header("If-Match", etag)
            .contentType(ContentType.JSON)
            .body(payload)
        .when()
            .put("/services/" + id)
        .then()
            .statusCode(200)
            .header("ETag", equalTo(etag));

        assertEquals(0, countAuditLogs(id, "FIELD_EDIT"));
    }

    // ---------- Bitácora y auditoría --------------------------------------------

    /**
     * Una edición deja UNA entrada de bitácora que enumera los cambios y cierra con la
     * justificación. La bitácora es la línea de tiempo de las ACCIONES: una línea por campo
     * repetiría la misma justificación tantas veces como campos se toquen.
     */
    @Test
    void update_writesOneLogEntryEnumeratingTheChanges() {
        long id = createService();
        Map<String, Object> payload = payloadOf(id);
        payload.put("price", 4100);
        payload.put("destination", "Camaná");

        update(id, payload).statusCode(200);

        JsonPath detail = detailOf(id);
        assertEquals(2, detail.getList("events").size(), "el alta más la edición");
        assertEquals("FIELD_EDIT", detail.getString("events[1].eventType"));
        // La nota COMPLETA, no tres `contains`: con contains sobreviven quitar los saltos de
        // línea (todo pegado en una sola), duplicar líneas o reordenarlas. El orden es el del
        // diff, no el del cuerpo: el precio va antes que el destino.
        assertEquals(
            "Precio: (no se muestra)\n"
                + "Destino: Lima → Camaná\n"
                + "Justificación: " + JUSTIFICATION,
            detail.getString("events[1].note"));
    }

    /**
     * LA regla que este endpoint podía romper por la puerta de al lado: al despacho nunca le llega
     * lo que se cobra. La bitácora viaja DENTRO del detalle, y al detalle el despacho sí entra, así
     * que un importe escrito en el texto de la nota se lo estaría entregando igual, con la regla de
     * precios intacta y sin que nada fallara. Se mide sobre el TEXTO de la bitácora, que es el canal por el que se filtraría.
     */
    @Test
    void update_doesNotLeakTheAmountsThroughTheLog() {
        long id = createService();
        Map<String, Object> payload = payloadOf(id);
        payload.put("price", 4100);
        payload.put("currencyId", otherCurrencyId);
        update(id, payload).statusCode(200);

        // Se mide sobre el TEXTO de la bitácora que recibe el despacho, unido en un solo string:
        // así el caso mira exactamente el canal por el que se filtraría, y no el resto del JSON
        // (donde "PEN" aparece de casualidad dentro de "PENDING_ASSIGNMENT").
        String log = String.join("\n", given()
            .header("Authorization", "Bearer " + TestAuth.fabricateAccessToken("despacho", "dispatcher"))
        .when()
            .get("/services/" + id)
        .then()
            .statusCode(200)
            .extract().jsonPath().getList("events.note", String.class));

        assertFalse(log.contains("4100"), "el importe nuevo viaja en la bitácora: " + log);
        assertFalse(log.contains("3200"), "el importe viejo viaja en la bitácora: " + log);
        assertFalse(log.contains("USD"), "la moneda nueva viaja en la bitácora: " + log);
        assertFalse(log.contains("PEN"), "la moneda vieja viaja en la bitácora: " + log);
        // y sin embargo la bitácora se ve, con las dos líneas nombrando qué se tocó
        assertTrue(log.contains("Precio: (no se muestra)"), log);
        assertTrue(log.contains("Moneda: (no se muestra)"), log);
    }

    /**
     * Los importes SÍ quedan en la auditoría: es el registro que permite reconstruir la historia,
     * y hoy no lo expone ningún endpoint. Sin este caso, esconderlos de la bitácora podría haberse
     * implementado perdiéndolos del todo y nada lo notaría.
     */
    @Test
    void update_keepsTheAmountsInTheAuditTrail() {
        long id = createService();
        Map<String, Object> payload = payloadOf(id);
        payload.put("price", 4100);
        update(id, payload).statusCode(200);

        assertEquals(List.of("price"), auditFieldNames(id));
        assertEquals(List.of("3200"), auditOldValues(id));
        assertEquals(List.of("4100"), auditNewValues(id));
    }

    /**
     * La bitácora muestra las marcas de tiempo en hora de Perú. En UTC, la misma pantalla diría dos
     * horas distintas para el mismo dato: el campo del detalle lo renderiza en hora local y la
     * bitácora estaría cinco horas adelante.
     */
    @Test
    void update_showsTheRealDatesInLimaTimeInTheLog() {
        long id = createService();
        operationsFixtures.forceServiceStatus(id, "IN_PROGRESS");
        forceRealDates(id, "2026-03-01T15:00:00Z", null);

        Map<String, Object> payload = payloadOf(id);
        payload.put("startDateTime", "2026-03-01T20:30:00Z");
        update(id, payload).statusCode(200);

        String note = detailOf(id).getString("events[1].note");
        assertTrue(note.contains("Inicio real: 01/03/2026 10:00:00 → 01/03/2026 15:30:00"),
            "la bitácora habla en hora de Perú: " + note);
        // la auditoría, en cambio, guarda el instante sin ambigüedad
        assertEquals(List.of("2026-03-01T15:00Z"), auditOldValues(id));
        assertEquals(List.of("2026-03-01T20:30Z"), auditNewValues(id));
    }

    /**
     * A diferencia de las fechas reales, los campos opcionales normales SÍ se vacían mandando
     * null: es la única forma de sacar una dimensión mal cargada o una observación que ya no
     * corresponde. Se comprueba en la base, no solo en la respuesta.
     */
    @Test
    void update_withNullOptionalFields_clearsThem() {
        long id = createService();
        Map<String, Object> withValues = payloadOf(id);
        withValues.put("lengthM", 12.5);
        withValues.put("observations", "Coordinar con 24 h");
        update(id, withValues).statusCode(200);

        Map<String, Object> cleared = payloadOf(id);
        cleared.put("lengthM", null);
        cleared.put("observations", null);

        update(id, cleared)
            .statusCode(200)
            .body("lengthM", nullValue())
            .body("observations", nullValue());

        // la bitácora nombra el valor que quedó vacío en vez de escribir "null"
        String note = detailOf(id).getString("events[2].note");
        assertTrue(note.contains("Largo (m): 12.5 → (vacío)"), "el vaciado se nombra: " + note);
        assertTrue(note.contains("Observaciones: Coordinar con 24 h → (vacío)"), note);
    }

    /**
     * Nadie puede plantar líneas FALSAS en la bitácora. La nota tiene una línea por campo, y los
     * textos libres admiten saltos de línea a propósito: sin aplastarlos en lo que se muestra, un
     * `"ok\nPrecio: 3200 → 300"` en las observaciones se lee en el detalle con el formato exacto de
     * las líneas que escribe el servidor, y el rastro que existe para rendir cuentas queda
     * fabricado. Nadie puede editarlo ni borrarlo después.
     */
    @Test
    void update_withNewlinesInFreeText_cannotForgeLogLines() {
        long id = createService();
        Map<String, Object> payload = payloadOf(id);
        payload.put("observations", "ok\nPrecio: 3200 → 300");

        update(id, payload).statusCode(200);

        String note = detailOf(id).getString("events[1].note");
        assertEquals(
            "Observaciones: (vacío) → ok ⏎ Precio: 3200 → 300\n"
                + "Justificación: " + JUSTIFICATION,
            note);
        // el texto EXACTO no se perdió: sigue en el campo y en la auditoría
        assertEquals("ok\nPrecio: 3200 → 300", detailOf(id).getString("observations"));
        assertEquals(List.of("ok\nPrecio: 3200 → 300"), auditNewValues(id));
    }

    /** Un campo que estaba vacío se nombra: "Alto (m): → 3.1" no se entiende. */
    @Test
    void update_namesTheEmptyValueInTheLog() {
        long id = createService();
        Map<String, Object> payload = payloadOf(id);
        payload.put("heightM", 3.1);

        update(id, payload).statusCode(200);

        assertTrue(detailOf(id).getString("events[1].note").contains("Alto (m): (vacío) → 3.1"),
            "un valor ausente se nombra en la bitácora");
    }

    /**
     * La auditoría es el registro campo a campo: UNA fila por campo cambiado, con su valor viejo
     * y el nuevo. Es lo que va a permitir reconstruir la historia del viaje cuando la interfaz la
     * muestre.
     */
    @Test
    void update_writesOneAuditRowPerChangedField() {
        long id = createService();
        Map<String, Object> payload = payloadOf(id);
        payload.put("price", 4100);
        payload.put("origin", "Sullana");

        update(id, payload).statusCode(200);

        assertEquals(2, countAuditLogs(id, "FIELD_EDIT"), "una fila por campo cambiado, ni una más");
        assertEquals(List.of("origin", "price"), auditFieldNames(id));
        assertEquals(List.of("Piura", "3200"), auditOldValues(id));
        assertEquals(List.of("Sullana", "4100"), auditNewValues(id));
        assertEquals(JUSTIFICATION, auditDescription(id), "la justificación queda en cada fila");
    }

    /** El campo que NO se tocó no deja rastro, aunque haya viajado en el mismo cuerpo. */
    @Test
    void update_doesNotAuditTheFieldsThatDidNotChange() {
        long id = createService();
        Map<String, Object> payload = payloadOf(id);
        payload.put("price", 4100);

        update(id, payload).statusCode(200);

        assertEquals(List.of("price"), auditFieldNames(id));
    }

    /** Editar deja el rastro de QUIÉN: la fila la firma el usuario autenticado, no el creador. */
    @Test
    void update_signsTheChangeWithTheEditor() {
        long id = createService();
        int salesUserId = fixtures.userId("lcampos");
        String token = TestAuth.fabricateTokenForUser(salesUserId, "lcampos", "sales");

        Map<String, Object> payload = payloadOf(id);
        payload.put("price", 4100);

        given()
            .header("Authorization", "Bearer " + token)
            .header("If-Match", etagOf(id))
            .contentType(ContentType.JSON)
            .body(payload)
        .when()
            .put("/services/" + id)
        .then()
            .statusCode(200)
            // el creador NO cambia: lo editó otro
            .body("createdBy.username", equalTo("admin"))
            .body("events[1].createdBy.username", equalTo("lcampos"));

        assertEquals(salesUserId, auditChangedBy(id), "la auditoría la firma quien editó");
        assertEquals(salesUserId, updatedByOf(id), "el servicio queda firmado por quien editó");
    }

    // ---------- Fechas reales ----------------------------------------------------

    /**
     * Una fecha real se CORRIGE, no se fija: mandarla en un viaje que todavía no la tiene es un
     * error de quien llama. Fijarla es la transición de estado, que es la que además mueve el
     * viaje al estado que corresponde.
     */
    @Test
    void update_withStartDateOnAServiceThatDoesNotHaveIt_returns400() {
        long id = createService();
        Map<String, Object> payload = payloadOf(id);
        payload.put("startDateTime", "2026-03-01T10:00:00Z");

        update(id, payload)
            .statusCode(400)
            .contentType("application/problem+json")
            .body("code", equalTo("COM-001"))
            .body("detail", containsString("inicio"));
    }

    @Test
    void update_withEndDateOnAServiceThatDoesNotHaveIt_returns400() {
        long id = createService();
        operationsFixtures.forceServiceStatus(id, "IN_PROGRESS");
        forceRealDates(id, "2026-03-01T10:00:00Z", null);

        Map<String, Object> payload = payloadOf(id);
        payload.put("endDateTime", "2026-03-02T10:00:00Z");

        update(id, payload)
            .statusCode(400)
            .body("code", equalTo("COM-001"))
            .body("detail", containsString("fin"));
    }

    @Test
    void update_correctsTheStartOfAServiceInProgress() {
        long id = createService();
        operationsFixtures.forceServiceStatus(id, "IN_PROGRESS");
        forceRealDates(id, "2026-03-01T10:00:00Z", null);

        Map<String, Object> payload = payloadOf(id);
        payload.put("startDateTime", "2026-03-01T08:30:00Z");

        update(id, payload).statusCode(200);

        assertEquals(OffsetDateTime.parse("2026-03-01T08:30:00Z"), storedStart(id));
        assertEquals(List.of("startDateTime"), auditFieldNames(id));
    }

    @Test
    void update_correctsTheEndOfACompletedService() {
        long id = createService();
        operationsFixtures.forceServiceStatus(id, "COMPLETED");
        forceRealDates(id, "2026-03-01T10:00:00Z", "2026-03-02T10:00:00Z");

        Map<String, Object> payload = payloadOf(id);
        payload.put("endDateTime", "2026-03-02T18:45:00Z");

        update(id, payload).statusCode(200);

        assertEquals(OffsetDateTime.parse("2026-03-02T18:45:00Z"), storedEnd(id));
        // El rastro, no solo el valor guardado: sin esto, auditar el fin con el valor del INICIO
        // (o con la etiqueta del inicio) deja la fila guardada bien y el registro mintiendo.
        assertEquals(List.of("endDateTime"), auditFieldNames(id));
        assertEquals(List.of("Fin real"), auditFieldLabels(id));
        assertEquals(List.of("2026-03-02T10:00Z"), auditOldValues(id));
        assertEquals(List.of("2026-03-02T18:45Z"), auditNewValues(id));
        assertTrue(detailOf(id).getString("events[1].note")
                .contains("Fin real: 02/03/2026 05:00:00 → 02/03/2026 13:45:00"),
            "la bitácora habla en hora de Perú también para el fin");
    }

    @Test
    void update_withEndBeforeStart_returns400() {
        long id = createService();
        operationsFixtures.forceServiceStatus(id, "COMPLETED");
        forceRealDates(id, "2026-03-01T10:00:00Z", "2026-03-02T10:00:00Z");

        Map<String, Object> payload = payloadOf(id);
        payload.put("endDateTime", "2026-02-28T10:00:00Z");

        update(id, payload)
            .statusCode(400)
            .body("code", equalTo("COM-001"))
            .body("detail", containsString("anterior"));
    }

    /**
     * La regla se mide sobre el RESULTADO de la edición, no sobre lo que vino en el cuerpo:
     * corregir solo el inicio y empujarlo más allá del fin ya guardado deja la misma
     * inconsistencia, y mirando únicamente los campos enviados pasaría sin que nadie la vea.
     */
    @Test
    void update_movingOnlyTheStartPastTheStoredEnd_returns400() {
        long id = createService();
        operationsFixtures.forceServiceStatus(id, "COMPLETED");
        forceRealDates(id, "2026-03-01T10:00:00Z", "2026-03-02T10:00:00Z");

        Map<String, Object> payload = payloadOf(id);
        payload.put("startDateTime", "2026-03-05T10:00:00Z");

        update(id, payload)
            .statusCode(400)
            .body("code", equalTo("COM-001"));
    }

    /**
     * El borde que ACEPTA de la regla: fin IGUAL al inicio entra. Sin este caso, escribir la
     * comparación un grado más estricta (rechazar también la igualdad) pasaría todos los tests de
     * rechazo y bloquearía un viaje legítimamente instantáneo.
     */
    @Test
    void update_withEndEqualToStart_isAccepted() {
        long id = createService();
        operationsFixtures.forceServiceStatus(id, "COMPLETED");
        forceRealDates(id, "2026-03-01T10:00:00Z", "2026-03-02T10:00:00Z");

        Map<String, Object> payload = payloadOf(id);
        payload.put("endDateTime", "2026-03-01T10:00:00Z");

        update(id, payload).statusCode(200);

        assertEquals(OffsetDateTime.parse("2026-03-01T10:00:00Z"), storedEnd(id));
    }

    /**
     * Un viaje con fin pero SIN inicio: la base admite esa combinación (las dos columnas son
     * nulables y el CHECK cruzado de la tabla tolera los nulos) y los datos que vienen del sistema anterior la traen.
     * Corregir el fin ahí no puede reventar por comparar contra un inicio que no existe.
     */
    @Test
    void update_correctsTheEndOfAServiceWithoutStart_isAccepted() {
        long id = createService();
        operationsFixtures.forceServiceStatus(id, "COMPLETED");
        forceRealDates(id, null, "2026-03-02T10:00:00Z");

        Map<String, Object> payload = payloadOf(id);
        payload.put("endDateTime", "2026-03-02T18:45:00Z");

        update(id, payload).statusCode(200);

        assertEquals(OffsetDateTime.parse("2026-03-02T18:45:00Z"), storedEnd(id));
    }

    /**
     * Una marca pegada al tope del tipo con desfase negativo DESBORDA al pasarla a UTC. Lo que se
     * fija acá es que NO salga un 500: por HTTP el rechazo llega del lector de JSON, que normaliza
     * el huso antes que nadie y se topa con el desborde primero. Por eso no se afirma el cuerpo
     * del problema (ese 400 no lleva el formato de error de la API, cosa preexistente y común a
     * todo cuerpo malformado). La guarda propia cubre el mismo valor un escalón más abajo, donde
     * sí se puede medir: {@code ServiceResourceMapperTest}.
     */
    @Test
    void update_withARealDateThatOverflowsWhenNormalized_returns400NotAServerError() {
        // Sin estado previo a propósito: el request no llega al service, así que sembrar fechas
        // reales sería atrezzo que hace creer que el caso las ejercita.
        long id = createService();

        Map<String, Object> payload = payloadOf(id);
        payload.put("startDateTime", "+999999999-12-31T23:59:59-18:00");

        update(id, payload).statusCode(400);
    }

    /** Un null explícito no borra una fecha real: vale lo mismo que no mandarla. */
    @Test
    void update_withNullRealDates_leavesThemUntouched() {
        long id = createService();
        operationsFixtures.forceServiceStatus(id, "COMPLETED");
        forceRealDates(id, "2026-03-01T10:00:00Z", "2026-03-02T10:00:00Z");

        Map<String, Object> payload = payloadOf(id);
        payload.put("startDateTime", null);
        payload.put("endDateTime", null);

        update(id, payload).statusCode(200);

        assertEquals(OffsetDateTime.parse("2026-03-01T10:00:00Z"), storedStart(id));
        assertEquals(OffsetDateTime.parse("2026-03-02T10:00:00Z"), storedEnd(id));
        assertEquals(0, countAuditLogs(id, "FIELD_EDIT"), "no mandar una fecha no es editarla");
    }

    // ---------- Estado -----------------------------------------------------------

    /**
     * Los CUATRO estados que SÍ admiten edición, el completado incluido (corregir los datos de un
     * viaje cerrado es el caso normal). El gemelo del test de rechazo: sin este, agregar cualquiera
     * de ellos a la lista de inmutables dejaría la suite entera en verde — y `PENDING_START`, que
     * es el estado más habitual para corregir un viaje antes de que salga, dejaría de editarse.
     */
    @ParameterizedTest
    @ValueSource(strings = {"PENDING_ASSIGNMENT", "PENDING_START", "IN_PROGRESS", "COMPLETED"})
    void update_whenNotTerminal_isAllowed(String status) {
        long id = createService();
        operationsFixtures.forceServiceStatus(id, status);

        update(id, changedPayload(id))
            .statusCode(200)
            .body("status", equalTo(status))
            .body("destination", equalTo("Destino corregido"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"CANCELLED", "DELETED"})
    void update_whenTerminal_returns409(String status) {
        long id = createService();
        operationsFixtures.forceServiceStatus(id, status);

        update(id, changedPayload(id))
            .statusCode(409)
            .contentType("application/problem+json")
            .body("code", equalTo("OPS-004"))
            .body("detail", containsString("cancelado"));
    }

    /** Rechazado por estado quiere decir rechazado de verdad: no se escribió una sola fila. */
    @Test
    void update_whenTerminal_writesNothing() {
        long id = createService();
        String etagBefore = etagOf(id);
        operationsFixtures.forceServiceStatus(id, "CANCELLED");

        update(id, changedPayload(id)).statusCode(409);

        assertEquals(1, countEvents(id));
        assertEquals(0, countAuditLogs(id, "FIELD_EDIT"));
        assertEquals(etagBefore, etagOf(id));
    }

    // ---------- Validación del cuerpo --------------------------------------------

    /** Sin cuerpo el rechazo tiene que ser el 400 de la casa, no un 500 por desreferenciar null. */
    @Test
    void update_withoutBody_returns400() {
        long id = createService();

        given()
            .header("Authorization", "Bearer " + adminToken)
            .header("If-Match", etagOf(id))
            .contentType(ContentType.JSON)
        .when()
            .put("/services/" + id)
        .then()
            .statusCode(400)
            .contentType("application/problem+json")
            .body("code", equalTo("COM-001"));
    }

    @Test
    void update_withoutRequiredFields_returns400WithFieldErrors() {
        long id = createService();
        Map<String, Object> payload = payloadOf(id);
        payload.remove("origin");
        payload.remove("price");

        update(id, payload)
            .statusCode(400)
            .contentType("application/problem+json")
            .body("code", equalTo("COM-001"))
            .body("errors.size()", equalTo(2))
            .body("errors.field", containsInAnyOrder("origin", "price"));
    }

    /**
     * Cada campo obligatorio, uno por uno. El caso de arriba prueba que el 400 enumera VARIOS
     * campos; este prueba que ninguno se cuela: sin su anotacion, los seis llegan hasta la base y
     * revientan contra un NOT NULL o un findById(null), o sea 500 donde el contrato promete 400.
     */
    @ParameterizedTest
    @ValueSource(strings = {"tentativeDate", "origin", "destination", "weightKg", "price", "currencyId", "justification"})
    void update_withoutOneRequiredField_returns400NamingIt(String field) {
        long id = createService();
        Map<String, Object> payload = payloadOf(id);
        payload.remove(field);

        update(id, payload)
            .statusCode(400)
            .contentType("application/problem+json")
            .body("code", equalTo("COM-001"))
            .body("errors.field", hasItem(field));
    }

    /**
     * Las tres dimensiones son opcionales, pero si vienen tienen que ser POSITIVAS: el cero choca
     * contra el CHECK de la tabla, que es un 500. Los tests de dimensiones de mas arriba solo
     * mandan valores validos, asi que borrar el minimo exclusivo no los rompe.
     */
    @ParameterizedTest
    @ValueSource(strings = {"lengthM", "widthM", "heightM"})
    void update_withADimensionOfZero_returns400NamingIt(String field) {
        long id = createService();
        Map<String, Object> payload = payloadOf(id);
        payload.put(field, 0);

        update(id, payload)
            .statusCode(400)
            .contentType("application/problem+json")
            .body("code", equalTo("COM-001"))
            .body("errors.field", hasItem(field));
    }

    /** Los topes de los textos libres, que el contrato publica y hasta acá nadie ejercitaba. */
    @Test
    void update_withTextsOverTheirMaximumLength_returns400() {
        long id = createService();

        Map<String, Object> longOrigin = payloadOf(id);
        longOrigin.put("origin", "x".repeat(256));
        update(id, longOrigin).statusCode(400)
            .body("code", equalTo("COM-001")).body("errors.field", contains("origin"));

        Map<String, Object> longDestination = payloadOf(id);
        longDestination.put("destination", "x".repeat(256));
        update(id, longDestination).statusCode(400)
            .body("code", equalTo("COM-001")).body("errors.field", contains("destination"));

        Map<String, Object> longObservations = payloadOf(id);
        longObservations.put("observations", "x".repeat(501));
        update(id, longObservations).statusCode(400)
            .body("code", equalTo("COM-001")).body("errors.field", contains("observations"));

        Map<String, Object> longJustification = payloadOf(id);
        longJustification.put("justification", "x".repeat(501));
        update(id, longJustification).statusCode(400)
            .body("code", equalTo("COM-001")).body("errors.field", contains("justification"));
    }

    /** Y sus bordes que SÍ entran, para que el tope no se cierre un carácter antes. */
    @Test
    void update_withTextsExactlyAtTheirMaximumLength_isAccepted() {
        long id = createService();

        Map<String, Object> payload = payloadOf(id);
        payload.put("origin", "x".repeat(255));
        payload.put("observations", "y".repeat(500));
        payload.put("justification", "z".repeat(500));

        update(id, payload)
            .statusCode(200)
            .body("origin", equalTo("x".repeat(255)))
            .body("observations", equalTo("y".repeat(500)));
    }

    /** Más decimales de los que admite la columna: se rechaza antes de llegar al motor. */
    @Test
    void update_withTooManyDecimals_returns400() {
        long id = createService();
        Map<String, Object> payload = payloadOf(id);
        payload.put("weightKg", new BigDecimal("12000.005"));

        update(id, payload)
            .statusCode(400)
            .body("code", equalTo("COM-001"));
    }

    @Test
    void update_withoutJustification_returns400() {
        long id = createService();
        Map<String, Object> payload = payloadOf(id);
        payload.remove("justification");

        update(id, payload)
            .statusCode(400)
            .body("code", equalTo("COM-001"));
    }

    /** Una justificación de puros espacios no es una justificación. */
    @Test
    void update_withJustificationOfOnlySpaces_returns400() {
        long id = createService();
        Map<String, Object> payload = payloadOf(id);
        payload.put("justification", "              ");

        update(id, payload)
            .statusCode(400)
            .body("code", equalTo("COM-001"));
    }

    /**
     * El caso que el mínimo declarativo NO alcanza: un texto corto rellenado con espacios hasta
     * llegar al largo pedido. Pasa el "no está en blanco" y pasa el mínimo, porque los dos miden
     * el texto CRUDO; medido ya recortado son cinco caracteres, y la bitácora quedaría con una
     * justificación que no justifica nada.
     */
    @Test
    void update_withJustificationPaddedWithSpacesToReachTheMinimum_returns400() {
        long id = createService();
        Map<String, Object> payload = payloadOf(id);
        payload.put("justification", "corta     ");

        update(id, payload)
            .statusCode(400)
            .body("code", equalTo("COM-001"))
            .body("detail", containsString("al menos 10 caracteres"));
    }

    @Test
    void update_withShortJustification_returns400() {
        long id = createService();
        Map<String, Object> payload = payloadOf(id);
        payload.put("justification", "corta");

        update(id, payload)
            .statusCode(400)
            .body("code", equalTo("COM-001"));
    }

    /**
     * Los importes van como NÚMEROS, no como texto: mandados en comillas el rechazo podría venir
     * de que el cuerpo no parsea y no de la regla, con el mismo 400 tapando la diferencia.
     */
    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void update_withNonPositivePrice_returns400(int price) {
        long id = createService();
        Map<String, Object> payload = payloadOf(id);
        payload.put("price", price);

        update(id, payload)
            .statusCode(400)
            .body("code", equalTo("COM-001"));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void update_withNonPositiveWeight_returns400(int weight) {
        long id = createService();
        Map<String, Object> payload = payloadOf(id);
        payload.put("weightKg", weight);

        update(id, payload)
            .statusCode(400)
            .body("code", equalTo("COM-001"));
    }

    @Test
    void update_withBlankOrigin_returns400() {
        long id = createService();
        Map<String, Object> payload = payloadOf(id);
        payload.put("origin", "   ");

        update(id, payload)
            .statusCode(400)
            .body("code", equalTo("COM-001"));
    }

    /**
     * El año de nueve cifras que admite el formato ISO no entra en la columna de fecha: sin la
     * ventana de negocio, el valor llega hasta el motor y sale un 500 donde el contrato promete
     * un 400.
     */
    @Test
    void update_withOutOfRangeTentativeDate_returns400() {
        long id = createService();
        Map<String, Object> payload = payloadOf(id);
        payload.put("tentativeDate", "+999999999-12-31");

        update(id, payload)
            .statusCode(400)
            .contentType("application/problem+json")
            .body("code", equalTo("COM-001"));
    }

    /** Lo mismo para las marcas de tiempo: la columna de instante tampoco llega tan lejos. */
    @Test
    void update_withOutOfRangeRealDate_returns400() {
        long id = createService();
        operationsFixtures.forceServiceStatus(id, "IN_PROGRESS");
        forceRealDates(id, "2026-03-01T10:00:00Z", null);

        Map<String, Object> payload = payloadOf(id);
        payload.put("startDateTime", "+999999999-12-31T10:00:00Z");

        update(id, payload)
            .statusCode(400)
            .contentType("application/problem+json")
            .body("code", equalTo("COM-001"));
    }

    @Test
    void update_withUnknownCurrency_returns400() {
        long id = createService();
        Map<String, Object> payload = payloadOf(id);
        payload.put("currencyId", 999_999);

        update(id, payload)
            .statusCode(400)
            .body("code", equalTo("COM-001"))
            .body("detail", containsString("moneda"));
    }

    /** Cambiar A una moneda dada de baja se rechaza: el catálogo se cierra hacia adelante. */
    @Test
    void update_switchingToAnInactiveCurrency_returns400() {
        long id = createService();
        Map<String, Object> payload = payloadOf(id);
        payload.put("currencyId", disposableCurrency());
        setCurrencyActive(disposableCurrency(), false);
        try {
            update(id, payload)
                .statusCode(400)
                .body("code", equalTo("COM-001"))
                .body("detail", containsString("moneda"));
        } finally {
            setCurrencyActive(disposableCurrency(), true);
        }
    }

    /**
     * Pero un viaje cuya moneda se dio de baja DESPUÉS se sigue editando, mientras no se la
     * cambie. Exigir la moneda activa en toda edición lo dejaría congelado para siempre: ni
     * siquiera se le podría corregir una errata en el origen, que no tiene nada que ver con lo
     * que se cobra. Retirar una moneda cierra su uso hacia adelante, no congela lo ya emitido.
     */
    @Test
    void update_whenItsOwnCurrencyWasDeactivated_isStillEditable() {
        long id = createServiceWithCurrency(disposableCurrency());
        setCurrencyActive(disposableCurrency(), false);
        try {
            Map<String, Object> payload = payloadOf(id);
            payload.put("currencyId", disposableCurrency());
            payload.put("origin", "Sullana");

            update(id, payload)
                .statusCode(200)
                .body("origin", equalTo("Sullana"))
                .body("currencyCode", equalTo("ZZT"));
        } finally {
            setCurrencyActive(disposableCurrency(), true);
        }
    }

    // ---------- Ruta y autorización ----------------------------------------------

    @Test
    void update_withUnknownId_returns404() {
        given()
            .header("Authorization", "Bearer " + adminToken)
            .header("If-Match", "\"2020-01-01T00:00:00Z\"")
            .contentType(ContentType.JSON)
            .body(anyValidPayload())
        .when()
            .put("/services/999999999")
        .then()
            .statusCode(404)
            .contentType("application/problem+json")
            .body("code", equalTo("OPS-005"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "9999999999999999999999"})
    void update_withMalformedId_returns400(String id) {
        given()
            .header("Authorization", "Bearer " + adminToken)
            .header("If-Match", "\"2020-01-01T00:00:00Z\"")
            .contentType(ContentType.JSON)
            .body(anyValidPayload())
        .when()
            .put("/services/" + id)
        .then()
            .statusCode(400)
            .contentType("application/problem+json")
            .body("code", equalTo("COM-001"));
    }

    @Test
    void update_withoutToken_returns401() {
        given()
            .contentType(ContentType.JSON)
            .body(anyValidPayload())
        .when()
            .put("/services/1")
        .then()
            .statusCode(401);
    }

    /** El despacho no edita viajes, por el mismo motivo por el que no los registra. */
    @ParameterizedTest
    @ValueSource(strings = {"dispatcher", "warehouse_keeper", "finance_manager"})
    void update_asRoleWithoutEditing_returns403(String role) {
        long id = createService();
        String token = TestAuth.fabricateAccessToken("usuario", role);

        given()
            .header("Authorization", "Bearer " + token)
            .header("If-Match", etagOf(id))
            .contentType(ContentType.JSON)
            .body(changedPayload(id))
        .when()
            .put("/services/" + id)
        .then()
            .statusCode(403)
            // el cuerpo tambien: el mapper que lo estructura existe justamente porque sin el
            // Quarkus contesta un 403 pelado y el contrato promete un problem+json
            .contentType("application/problem+json")
            .body("code", equalTo("COM-003"));
    }

    /**
     * Quien no puede VER los importes tampoco los escribe. La regla del negocio esconde el precio
     * al despacho y eso es un VETO, no un permiso que otro rol compense: sin esta guarda, un
     * usuario que sumara despacho y ventas entraria por la lista de roles del endpoint y quedaria
     * obligado a mandar un precio que no puede leer, pisandolo con una adivinanza en cada edicion.
     * Hoy nadie tiene dos roles, pero el detalle y el listado ya cubren ese caso y la escritura no.
     */
    @Test
    void update_asDualRoleBlindToPrices_returns403() {
        long id = createService();
        String token = TestAuth.fabricateAccessTokenWithRoles("usuario", Set.of("dispatcher", "sales"));

        given()
            .header("Authorization", "Bearer " + token)
            .header("If-Match", etagOf(id))
            .contentType(ContentType.JSON)
            .body(changedPayload(id))
        .when()
            .put("/services/" + id)
        .then()
            .statusCode(403)
            .contentType("application/problem+json")
            .body("code", equalTo("COM-003"));
    }

    // ---------- Helpers ------------------------------------------------------------

    private long createService() {
        return createService("Piura", "Lima");
    }

    private long createServiceWithCurrency(int withCurrencyId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("clientId", clientId);
        payload.put("tripScope", "PROVINCIA");
        payload.put("tentativeDate", LocalDate.now().plusDays(3).toString());
        payload.put("origin", "Piura");
        payload.put("destination", "Lima moneda propia");
        payload.put("cargoTypeId", cargoTypeId);
        payload.put("weightKg", 12000);
        payload.put("price", 3200);
        payload.put("currencyId", withCurrencyId);

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

    /**
     * El cuerpo que reproduce EXACTAMENTE el estado actual del viaje, leído del detalle. Los
     * tests de "sin cambios" tienen que partir de acá: armado a mano, cualquier diferencia de
     * tipeo lo convertiría en una edición y el test dejaría de probar lo que dice.
     */
    private Map<String, Object> payloadOf(long serviceId) {
        JsonPath detail = detailOf(serviceId);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tentativeDate", detail.getString("tentativeDate"));
        payload.put("origin", detail.getString("origin"));
        payload.put("destination", detail.getString("destination"));
        payload.put("weightKg", detail.get("weightKg"));
        payload.put("lengthM", detail.get("lengthM"));
        payload.put("widthM", detail.get("widthM"));
        payload.put("heightM", detail.get("heightM"));
        payload.put("price", detail.get("price"));
        payload.put("currencyId", currencyCodeToId(detail.getString("currencyCode")));
        payload.put("observations", detail.getString("observations"));
        payload.put("justification", JUSTIFICATION);
        return payload;
    }

    /** El cuerpo actual con UN campo movido: para los casos donde el cambio da igual cuál sea. */
    private Map<String, Object> changedPayload(long serviceId) {
        Map<String, Object> payload = payloadOf(serviceId);
        payload.put("destination", "Destino corregido");
        return payload;
    }

    /** Cuerpo válido sin leer ningún viaje: para los casos que ni siquiera llegan a buscarlo. */
    private Map<String, Object> anyValidPayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tentativeDate", LocalDate.now().plusDays(3).toString());
        payload.put("origin", "Piura");
        payload.put("destination", "Lima");
        payload.put("weightKg", 12000);
        payload.put("price", 3200);
        payload.put("currencyId", currencyId);
        payload.put("justification", JUSTIFICATION);
        return payload;
    }

    private ValidatableResponse update(long serviceId, Map<String, Object> payload) {
        return given()
            .header("Authorization", "Bearer " + adminToken)
            .header("If-Match", etagOf(serviceId))
            .contentType(ContentType.JSON)
            .body(payload)
        .when()
            .put("/services/" + serviceId)
        .then();
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

    private int currencyCodeToId(String code) {
        return code == null ? currencyId : fixtures.currencyId(code);
    }

    // ---------- Helpers de base -----------------------------------------------------

    /**
     * Fija las fechas reales por SQL: las produce la transición de estado, que todavía no existe,
     * y sin ellas no se puede probar la CORRECCIÓN de una fecha ya puesta.
     */
    private void forceRealDates(long serviceId, String start, String end) {
        QuarkusTransaction.requiringNew().run(() -> entityManager.createNativeQuery(
                "UPDATE operaciones.services SET start_date_time = CAST(?1 AS timestamptz),"
                    + " end_date_time = CAST(?2 AS timestamptz) WHERE id = ?3")
            .setParameter(1, start).setParameter(2, end).setParameter(3, serviceId).executeUpdate());
    }

    /** Deja la observación como cadena vacía, que es lo que puede traer la carga histórica. */
    private void forceEmptyObservations(long serviceId) {
        QuarkusTransaction.requiringNew().run(() -> entityManager.createNativeQuery(
                "UPDATE operaciones.services SET observations = '' WHERE id = ?1")
            .setParameter(1, serviceId).executeUpdate());
    }

    private void setCurrencyActive(int id, boolean active) {
        QuarkusTransaction.requiringNew().run(() -> entityManager.createNativeQuery(
                "UPDATE public.currencies SET is_active = ?1 WHERE id = ?2")
            .setParameter(1, active).setParameter(2, id).executeUpdate());
    }

    private String storedFreeText(long serviceId, String column) {
        return (String) entityManager.createNativeQuery(
                "SELECT " + column + " FROM operaciones.services WHERE id = ?1")
            .setParameter(1, serviceId).getSingleResult();
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

    @SuppressWarnings("unchecked")
    private List<String> auditColumn(long serviceId, String column) {
        return entityManager.createNativeQuery(
                "SELECT " + column + " FROM operaciones.service_audit_logs"
                    + " WHERE service_id = ?1 AND change_type = 'FIELD_EDIT' ORDER BY field_name")
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
                    + " WHERE service_id = ?1 AND change_type = 'FIELD_EDIT'")
            .setParameter(1, serviceId).getSingleResult();
    }

    private int auditChangedBy(long serviceId) {
        return ((Number) entityManager.createNativeQuery(
                "SELECT DISTINCT changed_by FROM operaciones.service_audit_logs"
                    + " WHERE service_id = ?1 AND change_type = 'FIELD_EDIT'")
            .setParameter(1, serviceId).getSingleResult()).intValue();
    }

    private int updatedByOf(long serviceId) {
        return ((Number) entityManager.createNativeQuery(
                "SELECT updated_by FROM operaciones.services WHERE id = ?1")
            .setParameter(1, serviceId).getSingleResult()).intValue();
    }

    /**
     * La marca de tiempo tal como quedó guardada, normalizada a UTC. Se devuelve como instante y
     * no como texto: la representación de Java omite los segundos cuando son cero, así que
     * comparar cadenas haría fallar el test por el formato y no por el valor.
     */
    private OffsetDateTime storedStart(long serviceId) {
        return storedInstant(serviceId, "start_date_time");
    }

    private OffsetDateTime storedEnd(long serviceId) {
        return storedInstant(serviceId, "end_date_time");
    }

    private OffsetDateTime storedInstant(long serviceId, String column) {
        Object value = entityManager.createNativeQuery(
                "SELECT " + column + " FROM operaciones.services WHERE id = ?1")
            .setParameter(1, serviceId).getSingleResult();
        return value == null ? null
            : DateUtils.toOffsetDateTime(value).withOffsetSameInstant(ZoneOffset.UTC);
    }
}
