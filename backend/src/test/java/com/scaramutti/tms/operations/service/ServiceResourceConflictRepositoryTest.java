package com.scaramutti.tms.operations.service;

import com.scaramutti.tms.operations.model.ServiceResourceKind;
import com.scaramutti.tms.operations.model.ServiceStatus;
import com.scaramutti.tms.shared.repository.ServiceAssignmentRepository;
import com.scaramutti.tms.shared.repository.ServiceAssignmentRepository.ServiceAdditionalResourceRow;
import com.scaramutti.tms.shared.repository.ServiceResourceConflictRepository;
import com.scaramutti.tms.shared.repository.ServiceResourceConflictRepository.ServiceResourceConflictRow;
import com.scaramutti.tms.support.HermeticTestData;
import com.scaramutti.tms.support.OperationsTestData;
import com.scaramutti.tms.support.TestAuth;
import com.scaramutti.tms.support.WarehouseTestData;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contrato del REPOSITORIO de conflictos: los conflictos son con OTROS viajes, nunca con el propio.
 *
 * <p>Esta clase existe por un sobreviviente de las mutaciones. La condición que excluye el viaje
 * propio ({@code excludedServiceId}) no la alcanza NINGÚN endpoint: al asignar y al reabrir, el
 * viaje que se pasa todavía no retiene recursos, y al sumar refuerzos —el único que pasa un viaje
 * en ruta, o sea uno que sí retiene— el caso exacto en que el propio viaje aparecería lo rechaza
 * antes el 409 duro del duplicado. Quitando la condición, la suite entera queda verde.
 *
 * <p>Que no lo alcance el endpoint no significa que no se pueda medir: es una regla del
 * repositorio, y acá se lo llama directo. Sin esto, la única alternativa honesta era dejar la
 * condición documentada como no cubierta, que es fingir cobertura con otro nombre.
 *
 * <p>Los dos casos de la EXCLUSIÓN llevan su control positivo: la misma consulta, el mismo
 * recurso, preguntando desde otro viaje. Sin él, un test que solo espera "vacío" queda verde
 * también cuando la consulta se rompe y no devuelve nada nunca, que es la forma más común de que
 * este tipo de aserción mienta.
 *
 * <p>El segundo bloque cubre el repositorio de REFUERZOS: el orden del listado y las DOS mitades
 * de su guarda de precondición, que por endpoint también son inalcanzables.
 */
@QuarkusTest
class ServiceResourceConflictRepositoryTest {

    @Inject HermeticTestData fixtures;
    @Inject OperationsTestData operationsFixtures;
    @Inject WarehouseTestData warehouseFixtures;
    @Inject ServiceResourceConflictRepository serviceResourceConflictRepository;
    @Inject ServiceAssignmentRepository serviceAssignmentRepository;
    @Inject EntityManager entityManager;

    private int clientId;
    private int cargoTypeId;
    private int currencyId;
    private String adminToken;
    private int routeSeq;

    @BeforeEach
    void setUp() {
        clientId = fixtures.seedClient();
        cargoTypeId = fixtures.seedCargoType();
        currencyId = fixtures.currencyId("PEN");
        adminToken = TestAuth.adminToken();
        routeSeq = 0;
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

    /**
     * El viaje que retiene el recurso como PRINCIPAL no se reporta a sí mismo.
     *
     * <p>Es el caso que el endpoint de refuerzos rozaría si el duplicado no se rechazara primero:
     * sin la exclusión, sumar un conductor que el viaje YA tiene se contestaría como conflicto
     * forzable, y forzable es exactamente lo que ese caso no es.
     */
    @Test
    void findActiveConflicts_doesNotReportTheServiceThatAsked_whenItHoldsTheResourceAsPrincipal() {
        int driverId = operationsFixtures.seedDriver("ZTEST Titular", "Propio");
        long holderId = serviceInProgressHolding(driverId, null);

        assertEquals(List.of(), conflictsFor(holderId, driverId));

        // control positivo: el MISMO conductor, preguntando desde otro viaje, sí es conflicto
        long otherId = serviceInProgressHolding(
            operationsFixtures.seedDriver("ZTEST Otro", "Titular"), null);
        List<ServiceResourceConflictRow> fromOutside = conflictsFor(otherId, driverId);
        assertEquals(1, fromOutside.size(), "el control positivo no vio el conflicto: la consulta "
            + "no está midiendo nada");
        assertEquals(ServiceResourceKind.DRIVER, fromOutside.get(0).resource());
        assertEquals(ServiceStatus.IN_PROGRESS, fromOutside.get(0).serviceStatus());
    }

    /** Lo mismo por la otra fuente: el recurso lo retiene el propio viaje como REFUERZO. */
    @Test
    void findActiveConflicts_doesNotReportTheServiceThatAsked_whenItHoldsTheResourceAsReinforcement() {
        int principalDriverId = operationsFixtures.seedDriver("ZTEST Principal", "Titular");
        int reinforcementDriverId = operationsFixtures.seedDriver("ZTEST Relevo", "Refuerzo");
        long holderId = serviceInProgressHolding(principalDriverId, null);
        operationsFixtures.seedAdditionalAssignment(
            holderId, reinforcementDriverId, null, null, "Refuerzo previo sembrado");

        assertEquals(List.of(), conflictsFor(holderId, reinforcementDriverId));

        long otherId = serviceInProgressHolding(
            operationsFixtures.seedDriver("ZTEST Otro", "Titular"), null);
        List<ServiceResourceConflictRow> fromOutside = conflictsFor(otherId, reinforcementDriverId);
        assertEquals(1, fromOutside.size(), "el control positivo no vio el conflicto: la rama de "
            + "los refuerzos no está midiendo nada");
        assertEquals(ServiceResourceKind.DRIVER, fromOutside.get(0).resource());
    }

    /** Y el viaje propio sigue afuera aunque OTRO viaje también retenga el recurso. */
    @Test
    void findActiveConflicts_reportsOnlyTheOtherHolders_whenBothHoldTheResource() {
        int driverId = operationsFixtures.seedDriver("ZTEST Compartido", "Titular");
        long holderId = serviceInProgressHolding(driverId, null);
        long otherHolderId = serviceInProgressHolding(driverId, null);

        List<ServiceResourceConflictRow> conflicts = conflictsFor(holderId, driverId);

        assertEquals(1, conflicts.size(), conflicts.toString());
        assertEquals(codeOf(otherHolderId), conflicts.get(0).serviceCode());
    }

    // ---------- El repositorio de refuerzos --------------------------------------

    /**
     * El listado sale por {@code assigned_at}, NO por el orden de insercion.
     *
     * <p>Se siembra el segundo refuerzo con una fecha ANTERIOR al primero: asi, con el
     * {@code ORDER BY} borrado, PostgreSQL devolveria el orden fisico —que en una tabla recien
     * escrita es el de insercion— y el caso se pone rojo. Sembrando en orden no se podia
     * distinguir una cosa de la otra.
     */
    @Test
    void listByServiceId_ordersByTheMomentOfTheReinforcement_notByInsertion() {
        long serviceId = serviceInProgressHolding(
            operationsFixtures.seedDriver("ZTEST Titular", "Propio"), null);
        int firstDriverId = operationsFixtures.seedDriver("ZTEST Segundo", "Relevo");
        int secondDriverId = operationsFixtures.seedDriver("ZTEST Primero", "Relevo");

        operationsFixtures.seedAdditionalAssignment(serviceId, firstDriverId, null, null,
            "Sembrado primero, ocurrio despues", OffsetDateTime.parse("2026-07-02T10:00:00Z"));
        operationsFixtures.seedAdditionalAssignment(serviceId, secondDriverId, null, null,
            "Sembrado despues, ocurrio primero", OffsetDateTime.parse("2026-07-01T10:00:00Z"));

        List<Integer> drivers = serviceAssignmentRepository.listByServiceId(serviceId).stream()
            .map(ServiceAdditionalResourceRow::driverId).toList();

        assertEquals(List.of(secondDriverId, firstDriverId), drivers,
            "el listado tiene que salir por el momento del refuerzo, no por el orden de insercion");
    }

    /**
     * La guarda de la consulta de duplicados: sin el tope de espera puesto —o sea, sin que quien
     * llama haya tomado antes la fila del viaje— corta ruidoso en vez de contestar "sin duplicado".
     *
     * <p>Por HTTP es inalcanzable (el endpoint siempre lockea primero), asi que sin este caso la
     * guarda entera se puede borrar y la suite queda verde. Es el mismo motivo por el que su
     * hermana de {@code ServiceResourceConflictRepository} tiene los suyos.
     */
    @Test
    void findResourcesAlreadyInService_withoutTheRowLock_failsLoudly() {
        // El conductor es el que la consulta va a mirar de verdad, no un id fijo: asi el caso
        // sobrevive a un refactor que mueva la guarda despues de armar la consulta.
        int driverId = operationsFixtures.seedDriver("ZTEST SinLock", "Propio");
        long serviceId = serviceInProgressHolding(driverId, null);

        IllegalStateException failure = assertThrows(IllegalStateException.class, () ->
            QuarkusTransaction.requiringNew().run(() ->
                serviceAssignmentRepository.findResourcesAlreadyInService(
                    serviceId, driverId, null, null)));

        assertTrue(failure.getMessage().contains("lock de la fila"), failure.getMessage());
    }

    /**
     * La OTRA mitad de la guarda, que habia quedado sin test: el nivel de aislamiento.
     *
     * <p>Se puede borrar el {@code if} entero y la suite queda verde sin este caso, pese a que el
     * javadoc de la guarda promete que "la hermana lo verifica por el mismo motivo" — y la hermana
     * si lo verifica.
     */
    @Test
    void findResourcesAlreadyInService_underRepeatableRead_failsLoudly() {
        int driverId = operationsFixtures.seedDriver("ZTEST Aislamiento", "Propio");
        long serviceId = serviceInProgressHolding(driverId, null);

        IllegalStateException failure = assertThrows(IllegalStateException.class, () ->
            QuarkusTransaction.requiringNew().run(() -> {
                entityManager.createNativeQuery(
                    "SET TRANSACTION ISOLATION LEVEL REPEATABLE READ").executeUpdate();
                entityManager.createNativeQuery("SET LOCAL lock_timeout = '5s'").executeUpdate();
                serviceAssignmentRepository.findResourcesAlreadyInService(
                    serviceId, driverId, null, null);
            }));

        assertTrue(failure.getMessage().contains("READ COMMITTED"), failure.getMessage());
    }

    /** Y el control positivo: con el tope puesto, la consulta contesta lo que tiene que contestar. */
    @Test
    void findResourcesAlreadyInService_withTheRowLock_reportsThePrincipal() {
        int driverId = operationsFixtures.seedDriver("ZTEST Titular", "Propio");
        long serviceId = serviceInProgressHolding(driverId, null);

        Set<ServiceResourceKind> duplicated = QuarkusTransaction.requiringNew().call(() -> {
            entityManager.createNativeQuery("SET LOCAL lock_timeout = '5s'").executeUpdate();
            return serviceAssignmentRepository.findResourcesAlreadyInService(
                serviceId, driverId, null, null);
        });

        assertEquals(Set.of(ServiceResourceKind.DRIVER), duplicated);
    }

    /**
     * El desempate por {@code id} cuando la fecha EMPATA.
     *
     * <p>No alcanza con sembrar dos filas con la misma fecha: el {@code id} es autoincremental, así
     * que su orden COINCIDE con el orden físico de la tabla, y sin el desempate el motor devuelve
     * lo mismo. Ese fue el defecto de la primera versión de este caso —y el javadoc afirmaba lo
     * contrario—. Acá se fuerza que el orden físico sea el INVERSO del de {@code id}: un UPDATE
     * sobre la fila que se sembró PRIMERO hace que MVCC escriba una versión nueva al final del
     * montón. Con {@code ORDER BY assigned_at} solo, la consulta devolvería la segunda primero.
     */
    @Test
    void listByServiceId_breaksTiesByInsertionOrder_whenTheMomentIsTheSame() {
        long serviceId = serviceInProgressHolding(
            operationsFixtures.seedDriver("ZTEST Titular", "Propio"), null);
        int firstDriverId = operationsFixtures.seedDriver("ZTEST Primero", "Relevo");
        int secondDriverId = operationsFixtures.seedDriver("ZTEST Segundo", "Relevo");
        OffsetDateTime sameMoment = OffsetDateTime.parse("2026-07-01T10:00:00Z");

        long firstId = operationsFixtures.seedAdditionalAssignment(
            serviceId, firstDriverId, null, null, "Primero", sameMoment);
        operationsFixtures.seedAdditionalAssignment(
            serviceId, secondDriverId, null, null, "Segundo", sameMoment);
        operationsFixtures.rewriteAssignmentInPlace(firstId);

        List<Integer> drivers = serviceAssignmentRepository.listByServiceId(serviceId).stream()
            .map(ServiceAdditionalResourceRow::driverId).toList();

        assertEquals(List.of(firstDriverId, secondDriverId), drivers,
            "con la misma fecha el orden lo tiene que dar el id, no el orden físico de la tabla");
    }

    // ---------- Helpers ---------------------------------------------------------

    /**
     * Llama al repositorio directo, con el tope de espera puesto A MANO: en producción lo aplica el
     * lock de la fila del viaje, y la consulta exige que ya esté (si no, la espera por el lock de
     * un recurso no terminaría nunca).
     *
     * <p>El valor NO es el de producción (1s) y no tiene por qué serlo: acá es andamiaje para
     * satisfacer la precondición, no la medición. Lo que se mide con el valor real es el
     * comportamiento del ENDPOINT, y esa suite corre sin override.
     */
    private List<ServiceResourceConflictRow> conflictsFor(long serviceId, Integer driverId) {
        return QuarkusTransaction.requiringNew().call(() -> {
            entityManager.createNativeQuery("SET LOCAL lock_timeout = '5s'").executeUpdate();
            return serviceResourceConflictRepository.findActiveConflicts(
                serviceId, driverId, null, null);
        });
    }

    private long serviceInProgressHolding(Integer driverId, Integer trailerId) {
        long serviceId = createService();
        operationsFixtures.forceServiceStatus(serviceId, ServiceStatus.IN_PROGRESS.name());
        operationsFixtures.forceServiceResources(
            serviceId, driverId, operationsFixtures.seedTractor(), trailerId);
        operationsFixtures.forceServiceDates(
            serviceId, OffsetDateTime.parse("2026-07-01T10:00:00Z"), null);
        return serviceId;
    }

    private long createService() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("clientId", clientId);
        body.put("tripScope", "PROVINCIA");
        body.put("tentativeDate", LocalDate.now().plusDays(3).toString());
        // ruta propia por viaje: el alta rechaza como doble-click dos altas del mismo cliente y la
        // misma ruta dentro de la ventana, y cada caso de acá arma dos o tres seguidos
        body.put("origin", "Piura " + (++routeSeq));
        body.put("destination", "Lima " + routeSeq);
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

    private String codeOf(long serviceId) {
        return given()
            .header("Authorization", "Bearer " + adminToken)
        .when()
            .get("/services/" + serviceId)
        .then()
            .statusCode(200)
            .extract().jsonPath().getString("code");
    }
}
