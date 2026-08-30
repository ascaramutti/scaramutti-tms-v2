package com.scaramutti.tms.operations;

import com.scaramutti.tms.operations.model.ServiceStatus;
import com.scaramutti.tms.shared.util.DateUtils;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integración de {@code GET /services/stats} — la tira de indicadores del tablero.
 *
 * <p><b>Todas las aserciones son DELTAS, no valores absolutos.</b> El endpoint no acepta un solo
 * parámetro: los contadores son globales y los totales son el padrón entero de {@code drivers} y
 * {@code tractors}, que son tablas COMPARTIDAS con el sistema anterior. No hay por dónde acotar a
 * los datos de la corrida, así que cada caso lee el baseline, siembra, y compara contra
 * {@code base + N}. Un {@code greaterThan(0)} pasaría siempre sin medir nada.
 *
 * <p><b>Regla de la suite, sin excepción: todo caso cuyo esperado sea delta CERO siembra además un
 * gemelo que SÍ cuenta.</b> Si el UPDATE de la siembra afectara cero filas, el contador tampoco se
 * movería y el caso pasaría por la razón contraria. El gemelo hace que el mismo número pruebe dos
 * cosas: que lo excluido no entró y que la siembra realmente escribió.
 *
 * <p>⚠️ El delta exige que nadie más escriba durante el caso. La suite corre secuencial (surefire
 * sin {@code parallel}); si algún día se habilita paralelismo, esta clase se vuelve intermitente.
 *
 * <p>⚠️ Todo caso que compare dos lecturas tomadas en instantes distintos —los del ciclo contra los
 * bordes del {@code setUp}, y el que compara el cuerpo entre dos roles— se invierte si la corrida
 * cruza justo la medianoche del miércoles de Lima: es una ventana de menos de un segundo, una vez
 * por semana. La cobertura del ciclo que NO depende del reloj está en
 * {@code OperationsWeekCycleTest}, con instantes literales.
 */
@QuarkusTest
class ServiceStatsResourceTest {

    @Inject HermeticTestData fixtures;
    @Inject OperationsTestData operationsFixtures;
    @Inject WarehouseTestData warehouseFixtures;
    @Inject EntityManager entityManager;

    private int clientId;
    private int cargoTypeId;
    private int currencyId;
    private String adminToken;
    private int routeSeq;

    /** Bordes del ciclo vigente, en instantes: son los que la siembra tiene que respetar. */
    private OffsetDateTime cycleOpen;
    private OffsetDateTime cycleCloseExclusive;

    @BeforeEach
    void setUp() {
        clientId = fixtures.seedClient();
        cargoTypeId = fixtures.seedCargoType();
        currencyId = fixtures.currencyId("PEN");
        adminToken = TestAuth.adminToken();
        routeSeq = 0;

        LocalDate cycleStart = LocalDate.now(DateUtils.LIMA)
            .with(TemporalAdjusters.previousOrSame(DayOfWeek.WEDNESDAY));
        cycleOpen = cycleStart.atStartOfDay(DateUtils.LIMA).toOffsetDateTime();
        cycleCloseExclusive = cycleStart.plusDays(7).atStartOfDay(DateUtils.LIMA).toOffsetDateTime();
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

    // ---------- El ciclo semanal -------------------------------------------------

    /**
     * EL caso canónico: martes 23:00 de Lima está DENTRO del ciclo que cierra.
     *
     * <p>En UTC ese instante ya es miércoles, así que una implementación que arme la ventana con el
     * huso del servidor lo deja afuera. Es el defecto real del tablero del sistema anterior.
     *
     * <p>⚠️ Este caso solo DISCRIMINA ese defecto donde la zona de la JVM no es la de Lima: en CI,
     * que corre en UTC, cae; en una máquina configurada en {@code America/Lima}, las dos formas de
     * calcular dan lo mismo y pasaría con el defecto puesto. La cobertura que no depende de dónde
     * corra está en {@code OperationsWeekCycleTest}, que usa instantes literales.
     */
    @Test
    void completedAtTuesdayElevenPmLima_countsInThisCycle() {
        int base = stat("completedThisWeek");

        seedCompleted(cycleCloseExclusive.minusHours(1));

        assertEquals(base + 1, stat("completedThisWeek"));
    }

    /** El instante que ABRE el ciclo cuenta: el borde inferior es inclusivo. */
    @Test
    void completedAtTheOpeningInstant_isIncluded() {
        int base = stat("completedThisWeek");

        seedCompleted(cycleOpen);

        assertEquals(base + 1, stat("completedThisWeek"));
    }

    /**
     * El ÚLTIMO instante del martes cuenta. El sistema anterior cierra la ventana en
     * "23:59:59.999" y la columna guarda microsegundos: ese tope pierde los últimos 999.
     */
    @Test
    void completedAtTheVeryLastMicrosecondOfTheCycle_isIncluded() {
        int base = stat("completedThisWeek");

        seedCompleted(cycleCloseExclusive.minusNanos(1_000));

        assertEquals(base + 1, stat("completedThisWeek"));
    }

    /** Y la medianoche del miércoles siguiente NO: el borde superior es exclusivo. */
    @Test
    void completedAtTheNextCycleOpening_isExcluded() {
        int base = stat("completedThisWeek");

        seedCompleted(cycleCloseExclusive);
        seedCompleted(cycleOpen.plusDays(1));      // gemelo que SÍ cuenta

        assertEquals(base + 1, stat("completedThisWeek"));
    }

    /** El instante anterior a la apertura pertenece al ciclo pasado. */
    @Test
    void completedJustBeforeTheOpening_isExcluded() {
        int base = stat("completedThisWeek");

        seedCompleted(cycleOpen.minusNanos(1_000));
        seedCompleted(cycleOpen.plusDays(2));      // gemelo

        assertEquals(base + 1, stat("completedThisWeek"));
    }

    /**
     * Un completado SIN fecha de fin no cae en ninguna semana. Es un artefacto del sistema
     * anterior, y no se compensa con otra fecha: taparlo escondería el dato que hay que sanear.
     */
    @Test
    void completedWithoutEndDate_isNotCounted() {
        int base = stat("completedThisWeek");

        long serviceId = seedCompleted(cycleOpen.plusDays(1));
        operationsFixtures.forceServiceDates(serviceId, cycleOpen.minusDays(10), null);
        seedCompleted(cycleOpen.plusDays(2));      // gemelo

        assertEquals(base + 1, stat("completedThisWeek"));
    }

    // ---------- Estados que no cuentan -------------------------------------------

    /**
     * Cada contador cuenta SU estado y ninguno cuenta el de otro.
     *
     * <p>Los deltas son ASIMÉTRICOS a propósito: dos viajes esperando asignación y uno de cada uno
     * de los otros tres. Con los cuatro esperando +1, permutar las constantes de la consulta —darle
     * a {@code pendingAssignment} el nombre de {@code PENDING_START}, por ejemplo— deja los cuatro
     * deltas intactos y la suite entera en verde. Se midió: era el único de los cuatro contadores
     * que ningún caso podía distinguir, porque los otros tres tienen casos que los aíslan.
     */
    @Test
    void eachCounterCountsOnlyItsOwnStatus() {
        JsonPath base = stats();

        seedInStatus(ServiceStatus.PENDING_ASSIGNMENT, null, null);
        seedInStatus(ServiceStatus.PENDING_ASSIGNMENT, null, null);
        seedInStatus(ServiceStatus.PENDING_START, seedDriver(), seedTractor());
        seedInStatus(ServiceStatus.IN_PROGRESS, seedDriver(), seedTractor());
        seedCompleted(cycleOpen.plusDays(1));

        JsonPath after = stats();
        assertEquals(base.getInt("pendingAssignment") + 2, after.getInt("pendingAssignment"));
        assertEquals(base.getInt("pendingStart") + 1, after.getInt("pendingStart"));
        assertEquals(base.getInt("inProgress") + 1, after.getInt("inProgress"));
        assertEquals(base.getInt("completedThisWeek") + 1, after.getInt("completedThisWeek"));
        // De los cinco viajes, tres tienen conductor y tracto (el que espera arrancar, el que rueda
        // y el completado) y solo UNO está en ruta. El completado es el estado con recursos más
        // frecuente de la base, y sin estas dos líneas ampliar el filtro de "en ruta" a
        // `status IN (IN_PROGRESS, COMPLETED)` no rompe nada.
        assertEquals(base.getInt("driversOnRoad.active") + 1, after.getInt("driversOnRoad.active"));
        assertEquals(base.getInt("unitsOnRoad.active") + 1, after.getInt("unitsOnRoad.active"));
    }

    /** El caso filoso: filtrar por fecha y olvidar el estado sumaría los cancelados. */
    @ParameterizedTest
    @EnumSource(value = ServiceStatus.class, names = {"CANCELLED", "DELETED"})
    void terminalTripsWithAnEndDateInsideTheCycle_doNotCountAsCompleted(ServiceStatus status) {
        int base = stat("completedThisWeek");

        long terminal = seedCompleted(cycleOpen.plusDays(1));
        operationsFixtures.forceServiceStatus(terminal, status.name());
        seedCompleted(cycleOpen.plusDays(1));      // gemelo, con la MISMA fecha

        assertEquals(base + 1, stat("completedThisWeek"));
    }

    /** "En ruta" es el ESTADO, no "tiene recursos": un cancelado con conductor no ocupa a nadie. */
    @Test
    void terminalTripsWithResources_putNobodyOnTheRoad() {
        JsonPath base = stats();

        seedInStatus(ServiceStatus.CANCELLED, seedDriver(), seedTractor());
        seedInStatus(ServiceStatus.DELETED, seedDriver(), seedTractor());
        // El que ESPERA ASIGNACIÓN va con recursos a propósito: es una fila que v2 no produce pero
        // el cutover sí puede traer, y es el único estado que no tenía su caso de "tiene recursos y
        // no está en la calle". Sin él, ampliar el filtro de "en ruta" a ese estado no rompe nada.
        seedInStatus(ServiceStatus.PENDING_ASSIGNMENT, seedDriver(), seedTractor());
        seedInStatus(ServiceStatus.IN_PROGRESS, seedDriver(), seedTractor());   // gemelo

        JsonPath after = stats();
        assertEquals(base.getInt("driversOnRoad.active") + 1, after.getInt("driversOnRoad.active"));
        assertEquals(base.getInt("unitsOnRoad.active") + 1, after.getInt("unitsOnRoad.active"));
        // Y cada viaje suma SOLO a su propio contador. Los dos +1 son los gemelos que hacen que el
        // cero de abajo signifique algo: si la siembra no hubiera escrito, fallarían primero.
        assertEquals(base.getInt("inProgress") + 1, after.getInt("inProgress"));
        assertEquals(base.getInt("pendingAssignment") + 1, after.getInt("pendingAssignment"));
        assertEquals(base.getInt("pendingStart"), after.getInt("pendingStart"));
    }

    /** Un viaje con recursos asignados pero sin arrancar todavía no pone a nadie en la calle. */
    @Test
    void pendingStartTrip_countsAsPendingStartButNotOnRoad() {
        JsonPath base = stats();

        seedInStatus(ServiceStatus.PENDING_START, seedDriver(), seedTractor());
        seedInStatus(ServiceStatus.IN_PROGRESS, seedDriver(), seedTractor());   // gemelo

        JsonPath after = stats();
        assertEquals(base.getInt("pendingStart") + 1, after.getInt("pendingStart"));
        assertEquals(base.getInt("driversOnRoad.active") + 1, after.getInt("driversOnRoad.active"));
        assertEquals(base.getInt("unitsOnRoad.active") + 1, after.getInt("unitsOnRoad.active"));
    }

    /**
     * Los tres contadores por estado son GLOBALES: no tienen ventana de tiempo.
     *
     * <p>"Pendiente de asignación" son TODOS los que esperan, tengan la antigüedad que tengan. Solo
     * el de completados mira la semana, y por eso es el único que recibe los bordes del ciclo.
     *
     * <p>Sin este caso la regla no la mide nada, y el error es de los que se cometen con buena
     * intención: el endpoint entero está redactado alrededor del ciclo —el cuerpo lo publica, el
     * repositorio y el contrato lo explican, y el reporte semanal va a reusarlo— así que
     * "unifiquemos, todo el tablero es de la semana" suena a mejora. Todo viaje que la clase siembra
     * nace HOY, así que acotar estos tres contadores no movería un solo delta: en producción caerían
     * de decenas a los pocos cargados desde el miércoles, y el tablero seguiría pareciendo sano.
     */
    @Test
    void tripsRegisteredBeforeThisCycle_stillCountInTheStatusCounters() {
        JsonPath base = stats();

        long waiting = seedInStatus(ServiceStatus.PENDING_ASSIGNMENT, null, null);
        long ready = seedInStatus(ServiceStatus.PENDING_START, seedDriver(), seedTractor());
        long onRoad = seedInStatus(ServiceStatus.IN_PROGRESS, seedDriver(), seedTractor());
        OffsetDateTime longBeforeThisCycle = cycleOpen.minusDays(40);
        for (long serviceId : new long[] {waiting, ready, onRoad}) {
            operationsFixtures.forceServiceAge(serviceId, longBeforeThisCycle);
            // La fecha vieja es la PREMISA del caso, no su resultado: si el UPDATE no escribiera,
            // los tres viajes seguirían siendo de hoy y esto mediría lo mismo que los demás casos.
            assertEquals(longBeforeThisCycle.toInstant(),
                DateUtils.toOffsetDateTime(columnValueOf(serviceId, "created_at")).toInstant(),
                "el viaje no quedó viejo: el caso mediría otra cosa");
        }

        JsonPath after = stats();
        assertEquals(base.getInt("pendingAssignment") + 1, after.getInt("pendingAssignment"));
        assertEquals(base.getInt("pendingStart") + 1, after.getInt("pendingStart"));
        assertEquals(base.getInt("inProgress") + 1, after.getInt("inProgress"));
    }

    // ---------- driversOnRoad ----------------------------------------------------

    /**
     * <b>La decisión que este endpoint tiene que sostener</b> (D-OPS-16): el conductor de REFUERZO
     * no está "en ruta" a los efectos del indicador. Excluirlo es NO escribir un JOIN, y una
     * decisión que se materializa como una ausencia es indistinguible de un olvido — más todavía
     * porque la consulta de conflictos hace lo contrario a propósito. Este caso es lo único que
     * separa las dos cosas.
     */
    @Test
    void reinforcementDriver_isNotOnRoad() {
        JsonPath base = stats();

        long serviceId = seedInStatus(ServiceStatus.IN_PROGRESS, seedDriver(), seedTractor());
        // El armado no necesita aserción: la siembra hace `INSERT … RETURNING id` con
        // `getSingleResult()`, que revienta si no insertó ninguna fila.
        operationsFixtures.seedAdditionalAssignment(
            serviceId, seedDriver(), null, null, "ZTEST relevo reglamentario");
        // DOS refuerzos sobre el MISMO viaje, y son dos por una razón aritmética: con uno solo, un
        // JOIN de más contra la tabla de refuerzos devuelve UNA fila igual que sin el JOIN, así que
        // el viaje seguiría contando 1 y el defecto sería invisible.
        operationsFixtures.seedAdditionalAssignment(
            serviceId, seedDriver(), null, null, "ZTEST segundo relevo");

        JsonPath after = stats();
        assertEquals(base.getInt("driversOnRoad.active") + 1, after.getInt("driversOnRoad.active"));
        // El viaje es UNO, tenga los refuerzos que tenga. Fija que los contadores de viajes cuentan
        // VIAJES y no filas del producto: hoy los dos JOIN van contra la PK del otro lado y no
        // multiplican, pero eso no lo afirmaba nada, y unir la tabla de refuerzos —el JOIN que el
        // repositorio advierte que alguien va a creer que falta— inflaría los cuatro contadores.
        // Los numeradores de recursos no pueden verlo: son COUNT(DISTINCT), inmunes al fan-out.
        // ⚠️ Alcance: el viaje tiene TRES tablas hijas y esto mide una. Cada viaje de la clase tiene
        // exactamente un evento y una cantidad fija de auditoría, así que unir esas dos multiplica
        // por uno y ningún delta lo vería. Se acota acá en vez de afirmarlo de más: ninguna de las
        // dos tiene un motivo por el que alguien las uniría para contar viajes, que es justamente lo
        // que hace verosímil al JOIN de refuerzos.
        assertEquals(base.getInt("inProgress") + 1, after.getInt("inProgress"));
    }

    /**
     * Y separa "no cuento refuerzos" de "cuento el principal y de paso el refuerzo se coló": acá el
     * viaje NO tiene principal, así que un JOIN de más se vería como un +1 imposible.
     */
    @Test
    void reinforcementDriverOnATripWithoutPrincipal_isNotOnRoad() {
        int base = stat("driversOnRoad.active");

        long orphan = seedInStatus(ServiceStatus.IN_PROGRESS, null, null);
        operationsFixtures.seedAdditionalAssignment(
            orphan, seedDriver(), null, null, "ZTEST relevo sin principal");
        seedInStatus(ServiceStatus.IN_PROGRESS, seedDriver(), seedTractor());   // gemelo

        assertEquals(base + 1, stat("driversOnRoad.active"));
    }

    /** El mismo conductor en dos viajes es UNA persona en la calle. Mata un COUNT sin DISTINCT. */
    @Test
    void theSameDriverOnTwoTrips_countsOnce() {
        int base = stat("driversOnRoad.active");
        int driverId = seedDriver();

        seedInStatus(ServiceStatus.IN_PROGRESS, driverId, seedTractor());
        seedInStatus(ServiceStatus.IN_PROGRESS, driverId, seedTractor());

        assertEquals(base + 1, stat("driversOnRoad.active"));
    }

    /** El padrón cuenta el ALTA del conductor, no su disponibilidad del catálogo. */
    @Test
    void theDriverTotal_countsActiveOnesRegardlessOfAvailability() {
        int base = stat("driversOnRoad.total");

        seedDriver();
        operationsFixtures.seedDriver("ZTEST NoDisp", "Conductor", null, null,
            WarehouseTestData.STATUS_NOT_AVAILABLE, true);
        operationsFixtures.seedDriver("ZTEST Baja", "Conductor", null, null,
            WarehouseTestData.STATUS_AVAILABLE, false);

        assertEquals(base + 2, stat("driversOnRoad.total"));
    }

    /**
     * La DESVIACIÓN consciente del sistema anterior: un conductor dado de baja mientras su viaje
     * sigue en ruta no cuenta arriba ni abajo. Allá contaba solo arriba, y por eso podía mostrar
     * "6 de 5" — una fracción imposible se lee como sistema roto.
     */
    @Test
    void aDriverOnRoadButDeactivated_countsInNeitherNumeratorNorTotal() {
        JsonPath base = stats();

        int inactiveDriverId = operationsFixtures.seedDriver("ZTEST DeBaja", "EnRuta", null, null,
            WarehouseTestData.STATUS_AVAILABLE, false);
        seedInStatus(ServiceStatus.IN_PROGRESS, inactiveDriverId, seedTractor());
        // El gemelo va NO DISPONIBLE en el catálogo, que es como está de verdad un conductor que
        // anda en la calle. El numerador mira el ALTA y no la disponibilidad: sin esta siembra,
        // agregarle un `AND status = 'available'` —razonando "en ruta debería estar disponible"—
        // no rompe un solo caso, y en producción el strip mostraría cero.
        int onRoadDriverId = operationsFixtures.seedDriver("ZTEST EnRuta", "NoDisp", null, null,
            WarehouseTestData.STATUS_NOT_AVAILABLE, true);
        seedInStatus(ServiceStatus.IN_PROGRESS, onRoadDriverId, seedTractor());

        JsonPath after = stats();
        assertEquals(base.getInt("driversOnRoad.active") + 1, after.getInt("driversOnRoad.active"));
        assertEquals(base.getInt("driversOnRoad.total") + 1, after.getInt("driversOnRoad.total"));
        // El viaje sí cuenta como viaje: la regla del alta recorta los RECURSOS en la calle, no los
        // viajes. Un viaje en ruta lo está aunque su conductor haya quedado dado de baja.
        assertEquals(base.getInt("inProgress") + 2, after.getInt("inProgress"));
    }

    /**
     * El gemelo exacto del caso del conductor, para la otra mitad del indicador.
     *
     * <p>No es simetria decorativa: se midio que sin este caso se puede borrar el {@code is_active}
     * del numerador de TRACTOS y la suite entera queda verde. La regla estaba probada para
     * conductores y no para unidades, que es justo la mitad donde nadie la estaba mirando.
     */
    @Test
    void aTractorOnRoadButDeactivated_countsInNeitherNumeratorNorTotal() {
        JsonPath base = stats();

        int inactiveTractorId = operationsFixtures.seedTractor(false, WarehouseTestData.STATUS_AVAILABLE);
        seedInStatus(ServiceStatus.IN_PROGRESS, seedDriver(), inactiveTractorId);
        // El gemelo va EN MANTENIMIENTO, por el mismo motivo que el del caso de los conductores.
        int onRoadTractorId = operationsFixtures.seedTractor(true, WarehouseTestData.STATUS_MAINTENANCE);
        seedInStatus(ServiceStatus.IN_PROGRESS, seedDriver(), onRoadTractorId);

        JsonPath after = stats();
        assertEquals(base.getInt("unitsOnRoad.active") + 1, after.getInt("unitsOnRoad.active"));
        assertEquals(base.getInt("unitsOnRoad.total") + 1, after.getInt("unitsOnRoad.total"));
        // Igual que con los conductores: el viaje del tracto de baja sigue siendo un viaje en ruta.
        assertEquals(base.getInt("inProgress") + 2, after.getInt("inProgress"));
    }

    /**
     * El conductor cuyo TRABAJADOR está de baja cuenta en las dos mitades, igual que cualquier otro.
     *
     * <p>Son dos banderas distintas y solo una manda: el padrón mira {@code drivers.is_active} y no
     * {@code workers.is_active}, exactamente como el buscador que decide a quién se puede asignar.
     * La población es real —cuando alguien se va, el sistema anterior lo da de baja como trabajador
     * sin garantía de bajar también su fila de conductor— y la mejora tentadora ("no contemos
     * ex-empleados") rompería dos cosas: el padrón mostraría menos gente que la lista de
     * asignables, y si ese conductor está en ruta se perdería también arriba.
     *
     * <p>Sin este caso esa mejora no rompe NADA: hasta acá toda la suite sembraba el trabajador de
     * alta, así que la bandera se cancelaba en la resta del delta.
     */
    @Test
    void aDriverWhoseWorkerIsDeactivated_countsInBothHalves() {
        JsonPath base = stats();

        int exEmployeeDriverId = operationsFixtures.seedDriverWithInactiveWorker("ZTEST ExEmpleado", "EnRuta");
        seedInStatus(ServiceStatus.IN_PROGRESS, exEmployeeDriverId, seedTractor());

        JsonPath after = stats();
        assertEquals(base.getInt("driversOnRoad.total") + 1, after.getInt("driversOnRoad.total"));
        assertEquals(base.getInt("driversOnRoad.active") + 1, after.getInt("driversOnRoad.active"));
    }

    // ---------- unitsOnRoad: las tres exclusiones de flota ------------------------

    /** La CARRETA del viaje en ruta no es una "unidad en ruta": el indicador es de tractos. */
    @Test
    void theTrailerOfATripOnRoad_isNotAUnitOnRoad() {
        int base = stat("unitsOnRoad.active");

        long serviceId = seedInStatus(ServiceStatus.IN_PROGRESS, seedDriver(), seedTractor());
        attachTrailer(serviceId, operationsFixtures.seedTrailer());

        assertEquals(base + 1, stat("unitsOnRoad.active"));
    }

    /**
     * Y el caso que de verdad discrimina: la carreta de un viaje en ruta SIN tracto.
     *
     * <p>El de arriba no alcanza. Una consulta que cayera en la carreta solo cuando falta el tracto
     * —{@code COALESCE(tractor_id, trailer_id)}, que es la forma en que esto se rompe por accidente
     * al querer "no perder viajes"— pasa aquel caso sin despeinarse, porque ahí el tracto existe y
     * gana. Se midió: sin este caso, esa mutación SOBREVIVE.
     */
    @Test
    void theTrailerOfATripOnRoadWithoutTractor_isNotAUnitOnRoad() {
        int base = stat("unitsOnRoad.active");

        long serviceId = seedInStatus(ServiceStatus.IN_PROGRESS, seedDriver(), null);
        attachTrailer(serviceId, operationsFixtures.seedTrailer());
        seedInStatus(ServiceStatus.IN_PROGRESS, seedDriver(), seedTractor());   // gemelo

        assertEquals(base + 1, stat("unitsOnRoad.active"));
    }

    @Test
    void reinforcementTractor_isNotOnRoad() {
        int base = stat("unitsOnRoad.active");

        long serviceId = seedInStatus(ServiceStatus.IN_PROGRESS, seedDriver(), seedTractor());
        operationsFixtures.seedAdditionalAssignment(
            serviceId, null, seedTractor(), null, "ZTEST tracto de apoyo");

        assertEquals(base + 1, stat("unitsOnRoad.active"));
    }

    /**
     * Y el gemelo del caso de conductores: el tracto de apoyo en un viaje SIN tracto principal.
     *
     * <p>El de arriba no alcanza, por el mismo motivo aritmético que en la carreta: una consulta que
     * cayera en el refuerzo solo cuando falta el principal —{@code COALESCE(s.tractor_id,
     * a.tractor_id)}, escrita para "no perder el viaje que no tiene tracto propio"— lo pasa sin
     * despeinarse, porque ahí el principal existe y gana. Se midió: sin este caso esa forma
     * sobrevive la suite entera.
     */
    @Test
    void reinforcementTractorOnATripWithoutPrincipal_isNotAUnitOnRoad() {
        int base = stat("unitsOnRoad.active");

        long orphan = seedInStatus(ServiceStatus.IN_PROGRESS, seedDriver(), null);
        operationsFixtures.seedAdditionalAssignment(
            orphan, null, seedTractor(), null, "ZTEST tracto de apoyo sin principal");
        seedInStatus(ServiceStatus.IN_PROGRESS, seedDriver(), seedTractor());   // gemelo

        assertEquals(base + 1, stat("unitsOnRoad.active"));
    }

    @Test
    void reinforcementTrailer_isNotOnRoad() {
        int base = stat("unitsOnRoad.active");

        long serviceId = seedInStatus(ServiceStatus.IN_PROGRESS, seedDriver(), seedTractor());
        operationsFixtures.seedAdditionalAssignment(
            serviceId, null, null, operationsFixtures.seedTrailer(), "ZTEST carreta de apoyo");

        assertEquals(base + 1, stat("unitsOnRoad.active"));
    }

    /** El padrón son TRACTOS: ni carretas ni escoltas, aunque el campo se llame "units". */
    @Test
    void theUnitTotal_countsOnlyTractors() {
        int base = stat("unitsOnRoad.total");

        // TRES tractos de alta, y una sola carreta y una sola escolta. Con un tracto solo, el delta
        // esperado y la cantidad de carretas sembradas coincidirían en 1, así que contar
        // `public.trailers` en vez de `public.tractors` daría el mismo número y el caso pasaría sin
        // distinguir las tablas. La escolta está por lo mismo: el contrato promete que no participa,
        // y sumar `public.escort_vehicles` al padrón no movería ni uno de los deltas de esta clase
        // —ningún otro caso siembra una— así que sin esta línea la promesa no la mide nada.
        seedTractor();
        seedTractor();
        // Y uno EN MANTENIMIENTO, que también cuenta: el padrón mira el alta, no la disponibilidad.
        // El contrato lo promete con este ejemplo exacto, y hasta acá estaba probado solo del lado
        // de los conductores.
        operationsFixtures.seedTractor(true, WarehouseTestData.STATUS_MAINTENANCE);
        operationsFixtures.seedTrailer();
        operationsFixtures.seedEscort();
        operationsFixtures.seedTractor(false, WarehouseTestData.STATUS_AVAILABLE);

        assertEquals(base + 3, stat("unitsOnRoad.total"));
    }

    @Test
    void theSameTractorOnTwoTrips_countsOnce() {
        int base = stat("unitsOnRoad.active");
        int tractorId = seedTractor();

        seedInStatus(ServiceStatus.IN_PROGRESS, seedDriver(), tractorId);
        seedInStatus(ServiceStatus.IN_PROGRESS, seedDriver(), tractorId);

        assertEquals(base + 1, stat("unitsOnRoad.active"));
    }

    /** Un viaje en ruta SIN recursos (dato del sistema anterior) cuenta como viaje y nada más. */
    @Test
    void aTripOnRoadWithoutResources_addsToTheTripCounterOnly() {
        JsonPath base = stats();

        seedInStatus(ServiceStatus.IN_PROGRESS, null, null);
        seedInStatus(ServiceStatus.IN_PROGRESS, seedDriver(), seedTractor());   // gemelo

        JsonPath after = stats();
        assertEquals(base.getInt("inProgress") + 2, after.getInt("inProgress"));
        assertEquals(base.getInt("driversOnRoad.active") + 1, after.getInt("driversOnRoad.active"));
        assertEquals(base.getInt("unitsOnRoad.active") + 1, after.getInt("unitsOnRoad.active"));
    }

    // ---------- Forma de la respuesta --------------------------------------------

    /**
     * El cuerpo trae EXACTAMENTE los campos del contrato, ni uno más ni uno menos.
     *
     * <p>Se afirma el conjunto de claves y no que cada número sea {@code >= 0}: los ocho salen de un
     * {@code COUNT} mapeado a {@code int}, así que esa comparación es verdadera por construcción y
     * no puede fallar nunca. Esta versión sí falla si alguien agrega, renombra o quita un campo, que
     * es lo único que este caso puede vigilar de verdad.
     */
    @Test
    void getStats_returnsExactlyTheContractFields() {
        JsonPath stats = stats();

        assertEquals(
            Set.of("pendingAssignment", "pendingStart", "inProgress", "completedThisWeek",
                   "driversOnRoad", "unitsOnRoad", "weekCycle"),
            stats.getMap("$").keySet());
        assertEquals(Set.of("active", "total"), stats.getMap("driversOnRoad").keySet());
        assertEquals(Set.of("active", "total"), stats.getMap("unitsOnRoad").keySet());
        assertEquals(Set.of("start", "end"), stats.getMap("weekCycle").keySet());
    }

    /** El contrato declara fechas, no marcas de tiempo. */
    @Test
    void weekCycleIsAPairOfPlainDates() {
        given()
            .header("Authorization", "Bearer " + adminToken)
        .when()
            .get("/services/stats")
        .then()
            .statusCode(200)
            .body("weekCycle.start", matchesPattern("\\d{4}-\\d{2}-\\d{2}"))
            .body("weekCycle.end", matchesPattern("\\d{4}-\\d{2}-\\d{2}"));
    }

    /**
     * El asesino determinista de las tres formas de equivocarse, corra el día que corra: un corte
     * lunes→domingo falla el día de la semana; una ventana móvil de siete días devuelve "hoy-7" y
     * "hoy"; y bordes en UTC hacen fallar la contención de hoy durante las últimas cinco horas del
     * martes de Lima.
     */
    @Test
    void weekCycleRunsWednesdayToTuesdayAndContainsToday() {
        JsonPath stats = stats();
        LocalDate start = LocalDate.parse(stats.getString("weekCycle.start"));
        LocalDate end = LocalDate.parse(stats.getString("weekCycle.end"));
        LocalDate today = LocalDate.now(DateUtils.LIMA);

        assertEquals(DayOfWeek.WEDNESDAY, start.getDayOfWeek());
        assertEquals(DayOfWeek.TUESDAY, end.getDayOfWeek());
        assertEquals(start.plusDays(6), end);
        assertTrue(!start.isAfter(today) && !end.isBefore(today),
            "el ciclo publicado no contiene el día de hoy en Lima");
    }

    /**
     * Las dos cabeceras de cache, que están por motivos distintos.
     *
     * <p>El {@code no-store} evita que la respuesta sobreviva a la SESIÓN en un proxy o en un perfil
     * de browser compartido. El {@code Vary} evitaría que un cache le sirva a un rol la respuesta
     * armada para otro, cosa que hoy no puede pasar porque el cuerpo no depende de quién pregunta
     * (eso lo prueba {@code getStats_withAnyAllowedRole_returnsTheSameBodyAsAdmin}); se manda igual,
     * como en los siete endpoints hermanos, para que siga habiendo una red el día en que alguien
     * relaje el {@code no-store} para poder cachear.
     *
     * <p>Se afirman las DOS. Con solo la primera, ese día el único test rojo es el del
     * {@code no-store} —que quien lo relaja actualiza a propósito— y el {@code Vary} se puede borrar
     * sin que nada se ponga en rojo, dejando a las dos specs mintiendo.
     */
    @Test
    void getStats_isNotStoredByCachesAndVariesByCaller() {
        given()
            .header("Authorization", "Bearer " + adminToken)
        .when()
            .get("/services/stats")
        .then()
            .statusCode(200)
            .header("Cache-Control", "no-store")
            .header("Vary", "Authorization");
    }

    // ---------- Roles -------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"admin", "sales", "general_manager", "operations_manager", "dispatcher"})
    void getStats_withAnyAllowedRole_returns200(String role) {
        given()
            .header("Authorization", "Bearer " + TestAuth.fabricateAccessToken("z" + role, role))
        .when()
            .get("/services/stats")
        .then()
            .statusCode(200);
    }

    @ParameterizedTest
    @ValueSource(strings = {"warehouse_keeper", "finance_manager"})
    void getStats_withARoleOutsideTheModule_returns403(String role) {
        given()
            .header("Authorization", "Bearer " + TestAuth.fabricateAccessToken("z" + role, role))
        .when()
            .get("/services/stats")
        .then()
            .statusCode(403)
            .body("code", equalTo("COM-003"));
    }

    @Test
    void getStats_withoutToken_returns401() {
        given().when().get("/services/stats").then().statusCode(401);
    }

    /**
     * El cuerpo es IDÉNTICO para los CINCO roles: no lleva un solo importe, así que la regla que le
     * esconde los precios al despacho no recorta nada. Se fija para que nadie copie esa maquinaria
     * acá.
     *
     * <p>Va sobre los cinco y no solo sobre el despacho porque es lo que respalda que el
     * {@code Vary: Authorization} sea hoy REDUNDANTE (que se manda igual, por si mañana deja de
     * serlo). Afirmar de dos roles que el cuerpo no depende de quién pregunta, y sacar de ahí una
     * conclusión sobre cinco, deja el hueco por el medio.
     */
    @ParameterizedTest
    @ValueSource(strings = {"sales", "general_manager", "operations_manager", "dispatcher"})
    void getStats_withAnyAllowedRole_returnsTheSameBodyAsAdmin(String role) {
        seedInStatus(ServiceStatus.IN_PROGRESS, seedDriver(), seedTractor());
        seedCompleted(cycleOpen.plusDays(1));

        String asAdmin = statsAs(adminToken).prettify();
        String asRole = statsAs(TestAuth.fabricateAccessToken("z" + role, role)).prettify();

        assertEquals(asAdmin, asRole);
    }

    /**
     * El detalle recibe su id como TEXTO, así que su plantilla matchea el literal {@code stats} sin
     * ningún filtro de tipo, y es la precedencia de JAX-RS la única que separa las dos rutas.
     *
     * <p>Este caso es DOCUMENTACIÓN, no una red: si esa precedencia fallara, fallarían con él la
     * docena larga de casos que piden el strip. Se deja porque nombra el riesgo, que de otro modo
     * solo vive en un comentario del recurso.
     */
    @Test
    void getStats_isNotSwallowedByTheDetailRoute() {
        given()
            .header("Authorization", "Bearer " + adminToken)
        .when()
            .get("/services/stats")
        .then()
            .statusCode(200)
            // El cuerpo es el del STRIP y no el de un viaje. Un `notNullValue()` sobre un campo
            // suelto no distinguiría las dos formas, que es lo único que este caso podría medir.
            .body("weekCycle.start", notNullValue())
            .body("clientId", nullValue());
    }

    // ---------- Helpers -----------------------------------------------------------

    private JsonPath stats() {
        return statsAs(adminToken);
    }

    private JsonPath statsAs(String token) {
        return given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/services/stats")
        .then()
            .statusCode(200)
            .extract().jsonPath();
    }

    private int stat(String field) {
        return stats().getInt(field);
    }

    private int seedDriver() {
        return operationsFixtures.seedDriver("ZTEST Stats", "Conductor");
    }

    private int seedTractor() {
        return operationsFixtures.seedTractor();
    }

    /**
     * Un viaje en el estado pedido, con sus recursos.
     *
     * <p>El armado se VERIFICA: los {@code force*} del fixture devuelven void y no delatan un
     * UPDATE de cero filas, y en una suite de deltas eso produciría un verde por la razón
     * contraria — el contador no se mueve porque no se sembró nada.
     */
    private long seedInStatus(ServiceStatus status, Integer driverId, Integer tractorId) {
        long serviceId = createService();
        if (status != ServiceStatus.PENDING_ASSIGNMENT) {
            operationsFixtures.forceServiceStatus(serviceId, status.name());
        }
        // Los recursos se siembran en CUALQUIER estado, incluido el que espera asignación: es una
        // fila que v2 no produce pero el cutover sí puede traer, y sin ella ampliar el filtro de
        // "en ruta" a ese estado no movería ningún delta.
        operationsFixtures.forceServiceResources(serviceId, driverId, tractorId, null);
        if (status == ServiceStatus.IN_PROGRESS) {
            operationsFixtures.forceServiceDates(serviceId, cycleOpen.minusDays(1), null);
        }
        assertSeeded(serviceId, status, driverId, tractorId);
        return serviceId;
    }

    /**
     * Un viaje COMPLETADO con la fecha de fin exacta que el caso necesita, y con sus recursos.
     *
     * <p>Los recursos se verifican como todo lo demás. No es simetría: este viaje es el que sostiene
     * que un completado NO cuente como "en ruta", y esa afirmación es un delta CERO, así que un
     * UPDATE que afectara cero filas la dejaría verde por la razón contraria.
     */
    private long seedCompleted(OffsetDateTime endDateTime) {
        long serviceId = createService();
        int driverId = seedDriver();
        int tractorId = seedTractor();
        operationsFixtures.forceServiceStatus(serviceId, ServiceStatus.COMPLETED.name());
        operationsFixtures.forceServiceResources(serviceId, driverId, tractorId, null);
        operationsFixtures.forceServiceDates(serviceId, endDateTime.minusHours(6), endDateTime);
        assertSeeded(serviceId, ServiceStatus.COMPLETED, driverId, tractorId);
        assertEquals(endDateTime.toInstant(), endOf(serviceId).toInstant(),
            "la fecha de fin no quedó como el caso la pidió");
        return serviceId;
    }

    private void assertSeeded(long serviceId, ServiceStatus status, Integer driverId, Integer tractorId) {
        assertEquals(status.name(), columnOf(serviceId, "status"),
            "el viaje no quedó en el estado que el caso necesita");
        // El caso NULL se verifica igual que el otro: hay casos que piden a propósito un viaje SIN
        // recursos —son los que sostienen que las uniones sean EXTERNAS— y sin esto la única
        // siembra de la clase que no se comprueba sería justamente esa.
        assertResourceColumn(serviceId, "driver_id", driverId);
        assertResourceColumn(serviceId, "tractor_id", tractorId);
    }

    private void assertResourceColumn(long serviceId, String column, Integer expectedId) {
        assertEquals(expectedId == null ? null : String.valueOf(expectedId),
            columnOf(serviceId, column), "el viaje no quedó con el " + column + " que el caso pidió");
    }

    private String columnOf(long serviceId, String column) {
        Object value = columnValueOf(serviceId, column + "::text");
        return value == null ? null : value.toString();
    }

    private Object columnValueOf(long serviceId, String column) {
        return entityManager.createNativeQuery(
                "SELECT " + column + " FROM operaciones.services WHERE id = ?1")
            .setParameter(1, serviceId).getSingleResult();
    }

    private OffsetDateTime endOf(long serviceId) {
        return DateUtils.toOffsetDateTime(entityManager.createNativeQuery(
                "SELECT end_date_time FROM operaciones.services WHERE id = ?1")
            .setParameter(1, serviceId).getSingleResult());
    }

    /**
     * Le engancha una carreta al viaje, CONSERVANDO los recursos que ya tiene, y verifica que la
     * fila haya quedado escrita.
     *
     * <p>La verificación es el punto. El {@code force} del fixture devuelve void y un UPDATE de cero
     * filas no se distingue de uno que escribió; y en los dos casos de carreta el esperado es que el
     * contador NO se mueva, así que una siembra que no escribe da el mismo verde que la regla
     * funcionando. Es la razón por la que ninguno de los dos puede usar el force pelado.
     */
    private void attachTrailer(long serviceId, int trailerId) {
        operationsFixtures.forceServiceResources(
            serviceId, driverOf(serviceId), tractorOf(serviceId), trailerId);
        assertEquals(String.valueOf(trailerId), columnOf(serviceId, "trailer_id"),
            "la carreta no se sembró: el caso mediría otra cosa");
    }

    /** Devuelven {@code null} cuando la columna lo es: hay casos que siembran el viaje SIN tracto. */
    private Integer driverOf(long serviceId) {
        return resourceIdOf(serviceId, "driver_id");
    }

    private Integer tractorOf(long serviceId) {
        return resourceIdOf(serviceId, "tractor_id");
    }

    private Integer resourceIdOf(long serviceId, String column) {
        String value = columnOf(serviceId, column);
        return value == null ? null : Integer.valueOf(value);
    }

    private long createService() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("clientId", clientId);
        body.put("tripScope", "PROVINCIA");
        body.put("tentativeDate", LocalDate.now(DateUtils.LIMA).plusDays(3).toString());
        // Ruta propia por viaje: el alta rechaza como doble-click dos altas del mismo cliente y la
        // misma ruta dentro de la ventana, y cada caso arma dos o tres seguidos.
        body.put("origin", "Piura stats " + (++routeSeq));
        body.put("destination", "Lima stats " + routeSeq);
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
