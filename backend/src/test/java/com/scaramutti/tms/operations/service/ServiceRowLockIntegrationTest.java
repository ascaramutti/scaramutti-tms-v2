package com.scaramutti.tms.operations.service;

import com.scaramutti.tms.shared.entity.Service;
import com.scaramutti.tms.shared.exception.ApiException;
import com.scaramutti.tms.support.HermeticTestData;
import com.scaramutti.tms.support.OperationsTestData;
import com.scaramutti.tms.support.TestAuth;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * El componente del lock, probado DE FORMA DETERMINISTA y no por carrera.
 *
 * <p>Existe por un agujero concreto: los casos con hilos que ejercitan el lock desde el endpoint
 * pueden pasar en verde CON el lock roto. Sin el {@code FOR NO KEY UPDATE}, el caso del 409
 * choca igual al volcar la escritura y produce el mismo código; y en la carrera de dos ediciones, el
 * perdedor puede agotar la espera, deshacer sus propias filas y dejar el conteo en uno — o sea, un
 * ganador y una fila de auditoría, exactamente lo que el test afirma, con el lost update adentro.
 *
 * <p>Acá no hay carrera: se toma el lock y se comprueba desde OTRA conexión con
 * {@code FOR UPDATE NOWAIT}, que falla en el acto en vez de esperar. Con eso, quitar el lock hace
 * fallar el caso SIEMPRE.
 */
@QuarkusTest
class ServiceRowLockIntegrationTest {

    @Inject HermeticTestData fixtures;
    @Inject OperationsTestData operationsFixtures;
    @Inject ServiceRowLock serviceRowLock;
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

    /**
     * LA propiedad del componente: mientras lo tiene tomado, nadie más puede tomar esa fila.
     * {@code NOWAIT} convierte "esperar" en "fallar ya", así que el caso es determinista.
     */
    @Test
    void findByIdForUpdate_holdsAnExclusiveLockOnTheRow() {
        long id = createService();

        QuarkusTransaction.requiringNew().run(() -> {
            serviceRowLock.findByIdForUpdate(id);

            PersistenceException e = assertThrows(PersistenceException.class,
                () -> QuarkusTransaction.requiringNew().call(() -> entityManager.createNativeQuery(
                        "SELECT id FROM operaciones.services WHERE id = ?1 FOR UPDATE NOWAIT")
                    .setParameter(1, id).getSingleResult()));

            // Se afirma el ESTADO que reporta PostgreSQL, no el tipo Java: `NoResultException`
            // también es `PersistenceException`, así que con el tipo solo, una fila que no existe
            // —o una tabla renombrada— haría pasar el test CON el lock desactivado. Es el mismo
            // modo de falla que esta clase existe para cerrar.
            assertEquals("55P03", sqlStateOf(e),
                "la otra transacción no chocó contra el lock, falló por otra cosa: " + e);
        });
    }

    /** Y al terminar la transacción la suelta: el bloqueo es de la transacción, no del proceso. */
    @Test
    void findByIdForUpdate_releasesTheLockWhenTheTransactionEnds() {
        long id = createService();
        QuarkusTransaction.requiringNew().run(() -> serviceRowLock.findByIdForUpdate(id));

        Object locked = QuarkusTransaction.requiringNew().call(() -> entityManager.createNativeQuery(
                "SELECT id FROM operaciones.services WHERE id = ?1 FOR UPDATE NOWAIT")
            .setParameter(1, id).getSingleResult());

        assertEquals(id, ((Number) locked).longValue());
    }

    /**
     * El tope de espera se aplica CON SU VALOR Y SU UNIDAD, en la conexión que va a esperar.
     * Sin esto, escribir la unidad mal ({@code 'ms'} en vez de {@code 's'}) deja la suite entera en
     * verde y en producción devuelve 409 ante cualquier contención trivial; con {@code 'h'}, se
     * come el pool. Es la mitad POSITIVA del par: la negativa está abajo.
     */
    @Test
    void findByIdForUpdate_appliesTheConfiguredTimeoutWithItsUnit() {
        long id = createService();

        String applied = QuarkusTransaction.requiringNew().call(() -> {
            serviceRowLock.findByIdForUpdate(id);
            return (String) entityManager.createNativeQuery("SHOW lock_timeout").getSingleResult();
        });

        assertEquals(serviceRowLock.requireUsableLockTimeout() + "s", applied,
            "el tope llegó a PostgreSQL con otro valor o con otra unidad");
    }

    /**
     * Y no se queda pegado a la conexión cuando la transacción termina. La mitad NEGATIVA: medida
     * sola no afirma nada (podría caer en otra conexión del pool), pero junto con el caso de
     * arriba —que prueba que el tope SÍ se aplicó— el par sí cierra la afirmación.
     */
    @Test
    void findByIdForUpdate_doesNotLeakTheTimeoutIntoThePool() {
        long id = createService();
        QuarkusTransaction.requiringNew().run(() -> serviceRowLock.findByIdForUpdate(id));

        String afterwards = QuarkusTransaction.requiringNew().call(() ->
            (String) entityManager.createNativeQuery("SHOW lock_timeout").getSingleResult());

        assertEquals("0", afterwards,
            "el tope quedó en la conexión del pool, que comparten los otros módulos");
    }

    /**
     * El lock RELEE la fila. Si el servicio ya estaba cargado en la transacción, tomar el lock sin
     * recargar devolvería la instancia vieja: la versión que se compara contra el {@code If-Match}
     * sería la de antes, un ETag caduco pasaría, y se escribiría encima de una edición ajena. Es el
     * lost update que el componente existe para cerrar, entrando por una lectura previa.
     */
    @Test
    void findByIdForUpdate_refreshesAnEntityAlreadyInThePersistenceContext() {
        long id = createService();

        QuarkusTransaction.requiringNew().run(() -> {
            Service stale = entityManager.find(Service.class, id);
            var versionBefore = stale.updatedAt;

            // otra transacción mueve la fila mientras esta la tiene cargada
            QuarkusTransaction.requiringNew().run(() -> entityManager.createNativeQuery(
                    "UPDATE operaciones.services SET updated_at = updated_at + interval '1 day'"
                        + " WHERE id = ?1").setParameter(1, id).executeUpdate());

            Service locked = serviceRowLock.findByIdForUpdate(id);

            assertNotEquals(versionBefore, locked.updatedAt,
                "el lock devolvió la instancia cacheada: la versión quedó vieja y el If-Match "
                    + "aceptaría un ETag caduco");
        });
    }

    /**
     * El lock frena a otro EDITOR pero no a quien solo agrega una fila hija (una entrada de
     * bitácora, una asignación): para eso es {@code FOR NO KEY UPDATE} y no {@code FOR UPDATE}.
     *
     * <p>Sin este caso, cambiar el modo de lock deja la suite entera en verde, y el precio se paga
     * en producción: el que inserta la hija espera con el tope por defecto de SU conexión (o sea,
     * sin tope), inmovilizando un hilo y una conexión del pool COMPARTIDO con los otros módulos.
     * El tope de 2s hace el caso determinista: si el lock frena, esto falla, no cuelga la suite.
     */
    @Test
    void findByIdForUpdate_doesNotBlockInsertingAChildRow() {
        long id = createService();

        QuarkusTransaction.requiringNew().run(() -> {
            serviceRowLock.findByIdForUpdate(id);

            int inserted = QuarkusTransaction.requiringNew().call(() -> {
                entityManager.createNativeQuery("SET LOCAL lock_timeout = '2s'").executeUpdate();
                return entityManager.createNativeQuery(
                        "INSERT INTO operaciones.service_events (service_id, event_type, note,"
                            + " created_by) SELECT id, 'NOTE', 'hija insertada con el padre"
                            + " lockeado', created_by FROM operaciones.services WHERE id = ?1")
                    .setParameter(1, id).executeUpdate();
            });

            assertEquals(1, inserted, "la fila hija no entró con el padre lockeado");
        });
    }

    /**
     * Llamarlo DESPUÉS de tocar la entity tiene que reventar: el lock relee la fila, y releer
     * descarta lo que se le haya cambiado en memoria. Sin esta guarda, ese orden invertido no
     * falla — devuelve 200, versión nueva y un rastro escrito sobre una fila que no se movió.
     */
    @Test
    void findByIdForUpdate_withPendingChanges_failsLoudlyInsteadOfDiscardingThem() {
        long id = createService();

        QuarkusTransaction.requiringNew().run(() -> {
            Service loaded = entityManager.find(Service.class, id);
            String original = loaded.destination;
            loaded.destination = "Trujillo";

            IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> serviceRowLock.findByIdForUpdate(id));

            assertTrue(e.getMessage().contains("ANTES"), "el mensaje no dice qué hacer: " + e);
            // y nombra lo que la transacción tiene cargado: sin esa pista el 500 no se diagnostica
            assertTrue(e.getMessage().contains("Service"), "el mensaje no dice sobre qué: " + e);

            // El cambio pendiente era el insumo del caso, no algo que tenga que aterrizar en la
            // fila: se deshace en memoria para que la entity deje de estar sucia y el commit no
            // escriba nada. Si quedara, el caso mentiría el día que crezca con una aserción sobre
            // la fila.
            loaded.destination = original;
        });
    }

    /**
     * La fila que se desvanece entre la primera lectura y el lock es un 404, no un 500. Pasa de
     * verdad: el script del cutover y la cirugía manual borran en duro, y el borrado del propio
     * módulo todavía no existe.
     */
    @Test
    void findByIdForUpdate_whenTheRowVanishesBeforeTheLock_isNotFound() {
        long id = createService();

        QuarkusTransaction.requiringNew().run(() -> {
            // queda cargada en la transacción, así que la primera lectura la encuentra…
            entityManager.find(Service.class, id);

            // …y recién ahí desaparece de la base
            QuarkusTransaction.requiringNew().run(() -> entityManager.createNativeQuery(
                "DELETE FROM operaciones.services WHERE id = ?1").setParameter(1, id).executeUpdate());

            ApiException e = assertThrows(ApiException.class,
                () -> serviceRowLock.findByIdForUpdate(id));

            assertEquals(404, e.status(), "la fila borrada salió con otro status: " + e.code());
            assertEquals("OPS-005", e.code());
        });
    }

    /** El estado que reporta PostgreSQL, recorriendo las causas hasta el error de base. */
    private static String sqlStateOf(Throwable e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof java.sql.SQLException sqlException) {
                return sqlException.getSQLState();
            }
        }
        return null;
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
