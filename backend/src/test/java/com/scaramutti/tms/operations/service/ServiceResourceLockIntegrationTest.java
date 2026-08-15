package com.scaramutti.tms.operations.service;

import com.scaramutti.tms.operations.model.ServiceResourceKind;
import com.scaramutti.tms.shared.repository.ServiceResourceConflictRepository;
import com.scaramutti.tms.support.HermeticTestData;
import com.scaramutti.tms.support.OperationsTestData;
import com.scaramutti.tms.support.TestAuth;
import com.scaramutti.tms.support.WarehouseTestData;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * El lock por RECURSO, que es lo que impide que dos asignaciones simultáneas del mismo conductor
 * se cuelen las dos sin verse.
 *
 * <p>Esta clase existe porque la garantía no se puede afirmar con una carrera de dos hilos: una
 * carrera sincroniza el disparo, no la lectura, así que el perdedor puede llegar tarde, ver al
 * ganador ya confirmado y contestar 409 sin que ningún lock haya intervenido. El test quedaría
 * verde con el lock borrado. Acá, en cambio, el lock se RETIENE desde otra transacción y se mide
 * qué contesta el endpoint mientras tanto: si alguien saca la llamada al lock, el endpoint deja
 * de esperar y contesta 200 donde se le pide 409.
 *
 * <p>El {@code @Timeout} es la red de último recurso. Hoy invertir el orden (tomar el lock del
 * recurso antes del de la fila, que es el que aplica el tope de espera) ya no cuelga: lo corta la
 * guarda de precondiciones, que revienta si el tope no está puesto. Pero esa guarda vive en el
 * mismo archivo que el lock, así que un cambio que se lleve las dos por delante dejaría la espera
 * sin techo otra vez, y una suite colgada tapa la aserción rota.
 */
@QuarkusTest
class ServiceResourceLockIntegrationTest {

    @Inject HermeticTestData fixtures;
    @Inject OperationsTestData operationsFixtures;
    @Inject WarehouseTestData warehouseFixtures;
    @Inject ServiceResourceConflictRepository serviceResourceConflictRepository;
    @Inject jakarta.persistence.EntityManager entityManager;

    private int clientId;
    private int cargoTypeId;
    private int currencyId;
    private String adminToken;
    private int driverId;
    private int tractorId;
    private int trailerId;

    @BeforeEach
    void setUp() {
        clientId = fixtures.seedClient();
        cargoTypeId = fixtures.seedCargoType();
        currencyId = fixtures.currencyId("PEN");
        adminToken = TestAuth.adminToken();
        driverId = operationsFixtures.seedDriver("ZTEST Lock", "Conductor");
        tractorId = operationsFixtures.seedTractor();
        trailerId = operationsFixtures.seedTrailer();
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

    // ---------- El lock, medido directamente ------------------------------------

    /**
     * Que el lock sea EXCLUSIVO se pregunta con la variante que no espera: así la respuesta es
     * inmediata y determinista, en vez de depender de cuánto tarde el otro lado.
     */
    @Test
    void acquire_holdsAnExclusiveLockOnTheResource() throws Exception {
        String key = ServiceResourceLockKeys.of(ServiceResourceKind.DRIVER, driverId);

        withResourceLockHeld(key, () -> QuarkusTransaction.requiringNew().run(() ->
            assertFalse(serviceResourceConflictRepository.tryAcquireLock(key),
                "otra transacción no debería poder tomar el mismo lock")));
    }

    /** Y que se suelte al terminar la transacción: si no, el primer viaje del día bloquearía el resto. */
    @Test
    void acquire_releasesTheLockWhenTheTransactionEnds() throws Exception {
        String key = ServiceResourceLockKeys.of(ServiceResourceKind.DRIVER, driverId);

        withResourceLockHeld(key, () -> { });

        QuarkusTransaction.requiringNew().run(() ->
            assertTrue(serviceResourceConflictRepository.tryAcquireLock(key),
                "cerrada la transacción que lo tenía, el lock queda libre"));
    }

    /** Dos recursos distintos con el mismo id NO comparten lock. */
    @Test
    void acquire_doesNotCollideAcrossResourceKinds() throws Exception {
        String tractorKey = ServiceResourceLockKeys.of(ServiceResourceKind.TRACTOR, 7);
        String driverKey = ServiceResourceLockKeys.of(ServiceResourceKind.DRIVER, 7);

        withResourceLockHeld(tractorKey, () -> QuarkusTransaction.requiringNew().run(() ->
            assertTrue(serviceResourceConflictRepository.tryAcquireLock(driverKey),
                "el conductor 7 y el tracto 7 no tienen nada que ver")));
    }

    /**
     * La guarda que exige el tope de espera ya puesto. Sin ella, un llamador que se saltee el
     * lock de la fila espera por el advisory PARA SIEMPRE, inmovilizando una conexion del pool
     * que se comparte con los otros modulos: el olvido se pagaria con la aplicacion clavada en
     * produccion en vez de con un error ruidoso.
     *
     * <p>Una transaccion nueva no tiene tope puesto, asi que llamar directo alcanza para verlo.
     */
    @Test
    void acquireResourceLocks_withoutTheLockTimeoutInPlace_failsLoudly() {
        IllegalStateException failure = assertThrows(IllegalStateException.class, () ->
            QuarkusTransaction.requiringNew().run(() ->
                serviceResourceConflictRepository.acquireResourceLocks(driverId, tractorId, null)));

        assertTrue(failure.getMessage().contains("tope de espera"), failure.getMessage());
    }

    /**
     * La otra mitad de la guarda: el nivel de aislamiento. Con REPEATABLE READ la consulta de
     * conflictos correria sobre la foto del inicio de la transaccion, no veria al ganador ya
     * confirmado, y las dos asignaciones terminarian en 200 sin que ninguna deje la linea que
     * dice que se piso un conflicto. PostgreSQL no avisaria: no hay choque de escritura sobre
     * una fila comun. Sin este caso, borrar el chequeo no hace ruido.
     */
    @Test
    void acquireResourceLocks_underRepeatableRead_failsLoudly() {
        IllegalStateException failure = assertThrows(IllegalStateException.class, () ->
            QuarkusTransaction.requiringNew().run(() -> {
                entityManager.createNativeQuery(
                    "SET TRANSACTION ISOLATION LEVEL REPEATABLE READ").executeUpdate();
                entityManager.createNativeQuery("SET LOCAL lock_timeout = '5s'").executeUpdate();
                serviceResourceConflictRepository.acquireResourceLocks(driverId, tractorId, null);
            }));

        assertTrue(failure.getMessage().contains("READ COMMITTED"), failure.getMessage());
    }

    // ---------- El endpoint, con el recurso retenido ----------------------------

    /**
     * EL caso decisivo: con el lock del recurso en manos de otra transacción, el endpoint espera,
     * se le agota el tope y contesta el 409 transitorio. Sin la llamada al lock contestaría 200.
     */
    @ParameterizedTest
    @ValueSource(strings = {"DRIVER", "TRACTOR", "TRAILER"})
    @Timeout(60)
    void assign_whenTheResourceLockIsHeldByAnotherTransaction_returns409(String kind) throws Exception {
        long id = createService();
        String key = ServiceResourceLockKeys.of(
            ServiceResourceKind.valueOf(kind), resourceIdOf(kind));

        withResourceLockHeld(key, () ->
            given()
                .header("Authorization", "Bearer " + adminToken)
                .contentType(ContentType.JSON)
                .body(assignmentPayload())
            .when()
                .post("/services/" + id + "/assignment")
            .then()
                .statusCode(409)
                .contentType("application/problem+json")
                .body("code", equalTo("OPS-008")));

        // soltado el lock, la MISMA asignación pasa: eso es lo que lo vuelve transitorio
        given()
            .header("Authorization", "Bearer " + adminToken)
            .contentType(ContentType.JSON)
            .body(assignmentPayload())
        .when()
            .post("/services/" + id + "/assignment")
        .then()
            .statusCode(200)
            .body("status", equalTo("PENDING_START"));
    }

    /**
     * El lock de un recurso que el cuerpo NO pide no frena nada. Es el gemelo del caso de arriba:
     * sin él, lockear de más (por ejemplo, toda la tabla) también lo pondría en verde.
     */
    @Test
    @Timeout(60)
    void assign_whenAnUnrelatedResourceIsLocked_isNotBlocked() throws Exception {
        long id = createService();
        int otherDriverId = operationsFixtures.seedDriver("ZTEST Ajeno", "Suelto");
        String key = ServiceResourceLockKeys.of(ServiceResourceKind.DRIVER, otherDriverId);

        withResourceLockHeld(key, () ->
            given()
                .header("Authorization", "Bearer " + adminToken)
                .contentType(ContentType.JSON)
                .body(assignmentPayload())
            .when()
                .post("/services/" + id + "/assignment")
            .then()
                .statusCode(200));
    }

    /**
     * La REAPERTURA es el segundo camino que toma estos locks, y entró sin la red que sí tiene la
     * asignación. El envoltorio que traduce el conflicto vive AFUERA del lock de la fila
     * (`runTranslatingLockConflicts` en `ChangeServiceStatusService`), y el único test de lock que
     * hay para las transiciones choca contra la fila, cuyo conflicto lo traduce OTRO envoltorio,
     * el de adentro de `findByIdForUpdate`. O sea: sin este caso, quitar el de afuera deja la suite
     * entera en verde y una reapertura que pierda la carrera por el conductor sale 500 donde el
     * contrato declara 409 `OPS-008`.
     */
    @Test
    @Timeout(60)
    void changeStatus_whenTheResourceLockIsHeldByAnotherTransaction_returns409() throws Exception {
        long id = createService();
        given().header("Authorization", "Bearer " + adminToken).contentType(ContentType.JSON)
            .body(assignmentPayload())
        .when().post("/services/" + id + "/assignment").then().statusCode(200);
        String etag = cancel(id);
        String key = ServiceResourceLockKeys.of(ServiceResourceKind.DRIVER, driverId);

        withResourceLockHeld(key, () ->
            given().header("Authorization", "Bearer " + adminToken).contentType(ContentType.JSON)
                .header("If-Match", etag)
                .body(Map.of("target", "REOPENED", "note", "El cliente retomó el embarque"))
            .when().post("/services/" + id + "/status")
            .then().statusCode(409)
                .contentType("application/problem+json")
                .body("code", equalTo("OPS-008")));

        // soltado el lock, la MISMA reapertura pasa: eso es lo que la vuelve transitoria
        given().header("Authorization", "Bearer " + adminToken).contentType(ContentType.JSON)
            .header("If-Match", etag)
            .body(Map.of("target", "REOPENED", "note", "El cliente retomó el embarque"))
        .when().post("/services/" + id + "/status")
        .then().statusCode(200).body("status", equalTo("PENDING_START"));
    }

    // ---------- Helpers ---------------------------------------------------------

    /** Cancela el viaje y devuelve el ETag que quedó, que es el que la reapertura va a exigir. */
    private String cancel(long id) {
        String etag = given().header("Authorization", "Bearer " + adminToken)
            .when().get("/services/" + id).then().statusCode(200).extract().header("ETag");
        given().header("Authorization", "Bearer " + adminToken).contentType(ContentType.JSON)
            .header("If-Match", etag)
            .body(Map.of("target", "CANCELLED", "note", "El cliente reprogramó el embarque"))
        .when().post("/services/" + id + "/status").then().statusCode(200);
        return given().header("Authorization", "Bearer " + adminToken)
            .when().get("/services/" + id).then().statusCode(200).extract().header("ETag");
    }

    private int resourceIdOf(String kind) {
        return switch (kind) {
            case "DRIVER" -> driverId;
            case "TRACTOR" -> tractorId;
            default -> trailerId;
        };
    }

    /**
     * Retiene el lock de un recurso desde otra transacción mientras corre el bloque. Mismo
     * andamiaje que el de la fila en la edición, con las dos barreras que garantizan que el lock
     * ya está tomado cuando el bloque arranca y que se suelta antes de que el test termine.
     */
    private void withResourceLockHeld(String lockKey, Runnable body) throws Exception {
        CyclicBarrier locked = new CyclicBarrier(2);
        CyclicBarrier finished = new CyclicBarrier(2);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> holder = pool.submit(() -> {
                QuarkusTransaction.requiringNew().run(() -> {
                    // el tope lo pone produccion al lockear la fila del viaje; acá se retiene el
                    // lock sin pasar por ese camino, así que se pone a mano para cumplir la misma
                    // precondición que exige el repositorio
                    entityManager.createNativeQuery("SET LOCAL lock_timeout = '10s'").executeUpdate();
                    serviceResourceConflictRepository.acquireLock(lockKey);
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

    private Map<String, Object> assignmentPayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("driverId", driverId);
        payload.put("tractorId", tractorId);
        payload.put("trailerId", trailerId);
        return payload;
    }

    private long createService() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("clientId", clientId);
        payload.put("tripScope", "PROVINCIA");
        payload.put("tentativeDate", LocalDate.now().plusDays(3).toString());
        payload.put("origin", "Piura");
        payload.put("destination", "Lima");
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
}
