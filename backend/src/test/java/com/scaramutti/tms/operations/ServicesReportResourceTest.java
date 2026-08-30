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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integración de {@code GET /services/report} — el reporte de facturación de UNA semana.
 *
 * <p>Todos los casos consultan una semana PROPIA, lejos de hoy, en vez de mirar deltas sobre el
 * total. Es la corrección del punto ciego que dejó el endpoint anterior: una suite de deltas no ve
 * nada que se mantenga constante entre el baseline y la lectura, y acá la ventana acotada hace que
 * cada caso afirme el CONTENIDO exacto de su semana y no una diferencia.
 *
 * <p>La semana base se elige DÉCADAS afuera, no unos meses. La copia de producción CRECE: un ancla
 * a pocos meses vista pasa de vacía a poblada sola, y ese día se caen a la vez todas las
 * aserciones de conteo absoluto de este archivo, con un mensaje que culpa al endpoint. Peor si
 * alguno de esos viajes reales viene del cutover sin fecha de inicio: ahí el reporte responde 500.
 * NINGÚN caso consulta la semana en curso real, por el mismo motivo.
 */
@QuarkusTest
class ServicesReportResourceTest {

    /** Lejos de la data real (que termina en 2026-12) y del reloj de la suite. */
    /** Miércoles de 2099: lejos de cualquier dato real y muy adentro de la ventana de negocio. */
    private static final LocalDate ANCHOR = LocalDate.of(2099, 3, 11);

    @Inject HermeticTestData fixtures;
    @Inject OperationsTestData operationsFixtures;
    @Inject WarehouseTestData warehouseFixtures;

    private int clientId;
    private int cargoTypeId;
    private int penId;
    private int usdId;
    private String adminToken;
    private int routeSeq;

    /** Ruta que cada alta mandó, por id de viaje. */
    private final Map<Long, String[]> seededRoutes = new LinkedHashMap<>();
    /** Miércoles del extremo INFERIOR de la ventana de negocio: cerrada para siempre, y sin data. */
    private static final LocalDate WEEK_LONG_CLOSED = LocalDate.of(1900, 1, 3);

    /** Miércoles del extremo SUPERIOR: nunca va a haber cerrado mientras el caso corra. */
    private static final LocalDate WEEK_THAT_NEVER_CLOSES = LocalDate.of(2999, 12, 25);

    private int driverSeq;

    @BeforeEach
    void setUp() {
        clientId = fixtures.seedClient();
        cargoTypeId = fixtures.seedCargoType();
        penId = fixtures.currencyId("PEN");
        usdId = fixtures.currencyId("USD");
        adminToken = TestAuth.adminToken();
        routeSeq = 0;
        seededRoutes.clear();
        driverSeq = 0;
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

    // ---------- Forma de la respuesta -------------------------------------------

    /**
     * Una semana sin viajes devuelve las dos listas VACÍAS, nunca null: distinguir "no hubo" de "no
     * vino" obligaría a todo consumidor a tratar dos casos que son el mismo.
     */
    @Test
    void report_withoutServicesInTheRange_returnsEmptyListsAndNotNull() {
        JsonPath report = report(ANCHOR);

        assertNotNull(report.getList("rows"), "rows no puede venir null");
        assertNotNull(report.getList("totals"), "totals no puede venir null");
        assertEquals(0, report.getList("rows").size());
        assertEquals(0, report.getList("totals").size());
    }

    /** El camino feliz: una fila con todo lo que el contrato promete. */
    @Test
    void report_withOneCompletedService_returnsItsRowWithEveryDeclaredField() {
        // Conductor EXPLÍCITO y no el del helper numerado: atar la expectativa a "Conductor1" la
        // hace depender de cuántos sembró antes el helper, y un caso que se rompe al reordenar la
        // siembra no está midiendo el endpoint. Mismo criterio que el caso de los refuerzos.
        int principalDriverId = operationsFixtures.seedDriver("Melchor", "Principal");
        long serviceId = seedCompleted(
            limaNoon(ANCHOR), penId, new BigDecimal("3200.00"), "PROVINCIA", principalDriverId);

        Map<String, Object> row = onlyRow(report(ANCHOR));

        // Los VALORES, no la presencia. Con assertNotNull, cruzar origen con destino —o el código
        // del viaje con el nombre del cliente— pasaba en verde: la fila viaja como componentes
        // POSICIONALES, con varios String seguidos y sin @Mapping que ate cada uno a su nombre. Es el mismo
        // defecto que el listado tuvo en el PR #141 y que allí encontró la batería de mutación.
        assertEquals(serviceId, ((Number) row.get("serviceId")).longValue());
        assertEquals(codeOf(serviceId), row.get("code"));
        assertEquals(clientNameOf(serviceId), row.get("clientName"));
        // El resto se afirma contra lo que el caso SEMBRÓ, no contra el detalle: usar otro endpoint
        // de oráculo hace que una mutación del detalle ponga en rojo al reporte. El código y el
        // nombre del cliente sí salen del detalle porque el caso no los elige (los genera el alta).
        assertEquals("PROVINCIA", row.get("tripScope"));
        assertEquals(seededOrigin(serviceId), row.get("origin"));
        assertEquals(seededDestination(serviceId), row.get("destination"));
        // La escala se mira en el CUERPO CRUDO. `getString` sobre un nodo numérico lo convierte a
        // decimal y lo re-imprime, así que "3200.00" volvería como "3200.0" y el caso mediría la
        // coerción de la librería en vez de lo que viaja por el cable.
        // Cierra por la derecha con la coma —`currencyCode` viene justo después— para que la
        // aserción no la satisfaga también un "3200.0000".
        assertTrue(rawReport(ANCHOR).contains("\"price\":3200.00,"),
            "el importe tiene que viajar con la escala de la moneda");
        assertEquals("PEN", row.get("currencyCode"));
        assertEquals("Melchor Principal", row.get("principalDriver"));
        assertEquals(0, ((List<?>) row.get("additionalDrivers")).size(), "lista vacía, nunca null");
        // Las dos fechas, y ADEMÁS que no estén cruzadas: un viaje no puede terminar antes de
        // empezar, y con dos assertNotNull ese intercambio no lo veía nadie.
        assertEquals(limaNoon(ANCHOR).toInstant(),
            OffsetDateTime.parse((String) row.get("endDateTime")).toInstant());
        assertTrue(OffsetDateTime.parse((String) row.get("startDateTime"))
                .isBefore(OffsetDateTime.parse((String) row.get("endDateTime"))),
            "el inicio tiene que ser anterior al fin");
    }

    // ---------- Los DOS lados de cada borde de la SEMANA -------------------------

    /**
     * El primer instante del MIÉRCOLES que abre la semana ENTRA: el borde inferior es inclusivo y
     * se mide en hora de Lima.
     */
    @Test
    void report_withAServiceAtTheFirstInstantOfTheWeek_includesIt() {
        seedCompleted(limaDayStart(ANCHOR), penId, new BigDecimal("100.00"));

        assertEquals(1, report(ANCHOR).getList("rows").size());
    }

    /** Un segundo ANTES ya pertenece a la semana anterior y queda AFUERA. */
    @Test
    void report_withAServiceOneSecondBeforeTheWeek_excludesIt() {
        seedCompleted(limaDayStart(ANCHOR).minusSeconds(1), penId, new BigDecimal("100.00"));

        assertEquals(0, report(ANCHOR).getList("rows").size());
    }

    /**
     * El ÚLTIMO microsegundo del MARTES que cierra entra. Es el caso que fija la ventana
     * SEMIABIERTA: escrito el tope como "23:59:59.999", este viaje se perdería por 999
     * microsegundos y el reporte de bonos dejaría de cuadrar sin que nada fallara.
     */
    @Test
    void report_withAServiceAtTheLastMicrosecondOfTheWeek_includesIt() {
        seedCompleted(limaDayStart(ANCHOR.plusDays(7)).minusNanos(1_000), penId,
            new BigDecimal("100.00"));

        assertEquals(1, report(ANCHOR).getList("rows").size());
    }

    /** Y el primer instante del miércoles SIGUIENTE ya es de la otra semana. */
    @Test
    void report_withAServiceAtTheFirstInstantOfTheNextWeek_excludesIt() {
        seedCompleted(limaDayStart(ANCHOR.plusDays(7)), penId, new BigDecimal("100.00"));

        assertEquals(0, report(ANCHOR).getList("rows").size());
    }

    /**
     * La semana entera son SIETE días, uno por uno. Siembra en CADA día y exige los siete: con dos
     * viajes en los extremos el caso no mataba nada que no mataran ya los dos casos de borde de
     * arriba, porque toda ventana que se coma el mediodía del martes se come también su último
     * microsegundo. Con los siete, una ventana que pierda cualquier día del medio se cae acá.
     */
    @Test
    void report_coversTheWholeSevenDayCycle() {
        for (int day = 0; day < 7; day++) {
            seedCompleted(limaNoon(ANCHOR.plusDays(day)), penId, new BigDecimal("100.00"));
        }

        assertEquals(7, report(ANCHOR).getList("rows").size());
    }

    /** La semana pedida vuelve en la respuesta, con el MARTES inclusive y no el miércoles siguiente. */
    @Test
    void report_echoesTheWeekItReports_withTuesdayAsTheInclusiveEnd() {
        JsonPath report = report(ANCHOR);

        assertEquals(ANCHOR.toString(), report.getString("weekCycle.start"));
        assertEquals(ANCHOR.plusDays(6).toString(), report.getString("weekCycle.end"));
    }

    // ---------- El huso ---------------------------------------------------------

    /**
     * EL caso del huso: un viaje cerrado el MARTES a las 20:00 de Lima —el último día de la semana—
     * cae, en UTC, en miércoles, o sea en la semana SIGUIENTE. Tiene que contarse en la semana que
     * cierra, no en la que abre.
     *
     * <p>Una implementación que arme la ventana con el huso del proceso lo manda a la semana
     * siguiente y el reporte de bonos pierde un viaje de esa semana y le suma uno ajeno a la
     * próxima. Es el defecto real del tablero del sistema anterior, y el motivo por el que este
     * módulo copia la semántica de su REPORTE y no la de su tablero.
     *
     * <p>⚠️ Este caso solo DISCRIMINA ese defecto donde la zona de la JVM no es la de Lima. Está
     * MEDIDO: sustituir la zona explícita por la del proceso sobrevive la suite entera con la JVM en
     * {@code America/Lima} y muere en UTC. CI corre en UTC, así que ahí discrimina; en una máquina
     * configurada en Lima, este caso y los de {@code OperationsWeekCycleTest} pasan con el defecto
     * puesto. Cerrarlo pide fijar la zona de los tests en el build, que toca la suite ENTERA y por
     * eso va en su propio cambio, no acá.
     */
    @Test
    void report_withAServiceOnTuesdayEightPmLima_belongsToTheClosingWeekAndNotTheNextOne() {
        // Martes = el sexto día del ciclo que abre el miércoles.
        OffsetDateTime tuesdayEightPmLima = limaDayStart(ANCHOR.plusDays(6)).plusHours(20);
        // Que el instante elegido de verdad cruce de día fuera de Lima: es lo que hace que las dos
        // formas de armar la ventana den distinto. No mide la zona de la JVM (esa la fija el build),
        // mide que el caso siga apuntando a un instante que discrimina si alguien lo mueve.
        assertEquals(ANCHOR.plusDays(7),
            tuesdayEightPmLima.toInstant().atZone(java.time.ZoneOffset.UTC).toLocalDate(),
            "el instante elegido ya no cruza de día fuera de Lima: el caso dejó de discriminar");

        seedCompleted(tuesdayEightPmLima, penId, new BigDecimal("100.00"));

        assertEquals(1, report(ANCHOR).getList("rows").size(),
            "el viaje tiene que caer en la semana que cierra");
        assertEquals(0, report(ANCHOR.plusDays(7)).getList("rows").size(),
            "y NO en la siguiente, que es donde lo pondría el huso del servidor");
    }

    // ---------- Lo que NO entra --------------------------------------------------

    /**
     * Solo los COMPLETADOS. Un cancelado o eliminado CONSERVA su fecha de fin —cancelar no limpia
     * los datos— así que sin el filtro por estado se colaría en la facturación.
     */
    @ParameterizedTest
    @EnumSource(value = ServiceStatus.class, mode = EnumSource.Mode.EXCLUDE, names = "COMPLETED")
    void report_withAServiceInAnyOtherStatus_excludesItEvenIfItHasAnEndDate(ServiceStatus status) {
        long serviceId = seedCompleted(limaNoon(ANCHOR), penId, new BigDecimal("100.00"));
        assertEquals(1, report(ANCHOR).getList("rows").size(), "precondición del caso");

        operationsFixtures.forceServiceStatus(serviceId, status.name());

        assertEquals(0, report(ANCHOR).getList("rows").size());
    }

    // ---------- Totales ----------------------------------------------------------

    /**
     * Los totales suman por moneda SIN mezclar. Sumar soles con dólares daría un número que no
     * significa nada, y elegir un tipo de cambio sería una decisión contable que este endpoint no
     * puede tomar.
     */
    @Test
    void report_withTwoCurrencies_totalsThemSeparately() {
        seedCompleted(limaNoon(ANCHOR), penId, new BigDecimal("100.50"));
        seedCompleted(limaNoon(ANCHOR), penId, new BigDecimal("200.25"));
        seedCompleted(limaNoon(ANCHOR), usdId, new BigDecimal("50.00"));

        JsonPath report = report(ANCHOR);

        assertEquals(2, report.getList("totals").size());
        assertEquals(2, totalServicesOf(report, "PEN"));
        assertEquals(300.75, totalRevenueOf(report, "PEN"), 0.001);
        assertEquals(1, totalServicesOf(report, "USD"));
        assertEquals(50.00, totalRevenueOf(report, "USD"), 0.001);
    }

    /**
     * Los totales tienen que cuadrar con las FILAS que se publican, que es la única garantía que
     * importa en un documento de facturación.
     */
    @Test
    void report_totalsAlwaysMatchTheRowsItPublishes() {
        seedCompleted(limaNoon(ANCHOR), penId, new BigDecimal("100.50"));
        seedCompleted(limaNoon(ANCHOR.plusDays(1)), usdId, new BigDecimal("50.00"));
        seedCompleted(limaNoon(ANCHOR.plusDays(9)), penId, new BigDecimal("999.99"));

        JsonPath report = report(ANCHOR);
        List<Map<String, Object>> rows = report.getList("rows");

        assertEquals(2, rows.size(), "el tercero está fuera de la semana");
        for (String currency : Set.of("PEN", "USD")) {
            double fromRows = rows.stream()
                .filter(row -> currency.equals(row.get("currencyCode")))
                .mapToDouble(row -> ((Number) row.get("price")).doubleValue())
                .sum();
            assertEquals(fromRows, totalRevenueOf(report, currency), 0.001,
                "el total de " + currency + " no suma sus propias filas");
        }
    }

    // ---------- Refuerzos ---------------------------------------------------------

    /** Un viaje sin refuerzos trae la lista VACÍA, nunca null. */
    @Test
    void report_withoutReinforcements_returnsAnEmptyListAndNotNull() {
        seedCompleted(limaNoon(ANCHOR), penId, new BigDecimal("100.00"));

        Object additionalDrivers = onlyRow(report(ANCHOR)).get("additionalDrivers");

        assertNotNull(additionalDrivers);
        assertEquals(0, ((List<?>) additionalDrivers).size());
    }

    /**
     * Los conductores de refuerzo viajan con su NOMBRE y su MOTIVO, en el orden en que se sumaron.
     *
     * <p>El segundo relevo va MIXTO —conductor y tracto en la misma fila—, que es el caso operativo
     * típico: el que llega de relevo suele llegar con otra unidad. Sin una fila así, estrechar el
     * filtro a los refuerzos de conductor PURO (un {@code AND a.tractor_id IS NULL} de más)
     * sobrevive la suite entera, y en producción borra del documento de facturación al conductor de
     * todo relevo que además cambió de tracto.
     */
    @Test
    void report_withReinforcementDrivers_returnsTheirNameAndReasonInOrder() {
        long serviceId = seedCompleted(limaNoon(ANCHOR), penId, new BigDecimal("100.00"));
        // Nombres EXPLICITOS y no el helper numerado: el principal del viaje también consume un
        // número del contador, así que atar la expectativa a "Conductor1" la hace depender de
        // cuántos conductores sembró antes el helper de la siembra. Un caso que se rompe al
        // reordenar la siembra no está midiendo el orden del endpoint.
        int firstDriverId = operationsFixtures.seedDriver("Primero", "Relevo");
        int secondDriverId = operationsFixtures.seedDriver("Segundo", "Relevo");
        operationsFixtures.seedAdditionalAssignment(
            serviceId, firstDriverId, null, null, "Relevo por descanso reglamentario");
        operationsFixtures.seedAdditionalAssignment(
            serviceId, secondDriverId, operationsFixtures.seedTractor(), null,
            "Segundo relevo por la ruta larga");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> additionalDrivers =
            (List<Map<String, Object>>) onlyRow(report(ANCHOR)).get("additionalDrivers");

        assertEquals(2, additionalDrivers.size());
        assertEquals("Relevo por descanso reglamentario", additionalDrivers.get(0).get("reason"));
        assertEquals("Segundo relevo por la ruta larga", additionalDrivers.get(1).get("reason"));
        // El NOMBRE se afirma, no solo su presencia: sin esto, devolver el mismo conductor dos
        // veces —o cruzar los nombres entre las dos filas— pasaría en verde.
        assertEquals("Primero Relevo", additionalDrivers.get(0).get("name"));
        assertEquals("Segundo Relevo", additionalDrivers.get(1).get("name"));
    }

    /**
     * Un refuerzo que NO es conductor (solo tracto) no aparece: el reporte publica conductores, y
     * la fila de refuerzo puede traer los tres recursos mezclados.
     */
    @Test
    void report_withAReinforcementThatIsNotADriver_ignoresIt() {
        long serviceId = seedCompleted(limaNoon(ANCHOR), penId, new BigDecimal("100.00"));
        operationsFixtures.seedAdditionalAssignment(
            serviceId, null, operationsFixtures.seedTractor(), null, "Cambio de tracto por falla");

        assertEquals(0, ((List<?>) onlyRow(report(ANCHOR)).get("additionalDrivers")).size());
    }

    /** Los refuerzos de OTRO viaje no se mezclan: se agrupan por viaje, no se reparten. */
    @Test
    void report_withReinforcementsOnTwoServices_givesEachOneItsOwn() {
        long first = seedCompleted(limaNoon(ANCHOR), penId, new BigDecimal("100.00"));
        long second = seedCompleted(limaNoon(ANCHOR).plusHours(1), penId, new BigDecimal("200.00"));
        long third = seedCompleted(limaNoon(ANCHOR).plusHours(2), penId, new BigDecimal("300.00"));
        operationsFixtures.seedAdditionalAssignment(first,
            operationsFixtures.seedDriver("Relevo", "Primera"), null, null, "Relevo del primero");
        operationsFixtures.seedAdditionalAssignment(third,
            operationsFixtures.seedDriver("Relevo", "Tercera"), null, null, "Relevo del tercero");

        List<Map<String, Object>> rows = report(ANCHOR).getList("rows");

        // Los refuerzos van en la PRIMERA y en la TERCERA fila, con el medio vacío. Con el reforzado
        // siempre en la fila 0, truncar a un id (`.limit(1)`) o repartir solo en la primera vuelta
        // del bucle sobrevivía la suite entera: el reparto quedaba medido en una sola dirección.
        assertEquals("Relevo Primera", onlyReinforcementNameOf(rows, first));
        assertEquals(0, sizeOfAdditionalDrivers(rows, second));
        assertEquals("Relevo Tercera", onlyReinforcementNameOf(rows, third));
    }

    private static String onlyReinforcementNameOf(List<Map<String, Object>> rows, long serviceId) {
        List<?> additionalDrivers = rows.stream()
            .filter(row -> ((Number) row.get("serviceId")).longValue() == serviceId)
            .findFirst()
            .map(row -> (List<?>) row.get("additionalDrivers"))
            .orElseThrow(() -> new AssertionError("no vino la fila del viaje " + serviceId));
        assertEquals(1, additionalDrivers.size(), "el viaje " + serviceId + " tiene un solo refuerzo");
        return (String) ((Map<?, ?>) additionalDrivers.get(0)).get("name");
    }

    /**
     * Los refuerzos se agrupan POR VIAJE y un viaje ajeno no se los presta, ni siquiera cuando queda
     * fuera de la semana. El caso siembra uno adentro con refuerzo y otro a treinta días con los
     * suyos, y exige que el segundo no aporte nada al primero.
     *
     * <p>⚠️ Lo que este caso NO mide, aunque el nombre anterior lo sugería, es la garantía de que
     * los refuerzos correspondan a las filas publicadas bajo READ COMMITTED. La implementación con
     * el defecto —pedir los refuerzos re-aplicando la ventana— pasa este caso IDÉNTICA, porque el
     * refuerzo del viaje de adentro también cae dentro de la ventana. Esa garantía solo se puede
     * medir a nivel del repositorio, y vive en DOS casos de {@code ServiceReportRepositoryTest}:
     * {@code findAdditionalDriversOf_returnsThemEvenForAServiceOutsideAnyDateRange} (la
     * implementación por rango devuelve cero ahí) y
     * {@code findAdditionalDriversOf_returnsOnlyTheAskedServices} (la que ignora los ids devuelve de
     * más). <b>Los dos son insatisfacibles para una implementación por rango, y ninguno de los dos
     * hay que borrarlo por parecer redundante con éste.</b>
     */
    @Test
    void report_reinforcementsOfAnotherServiceDoNotLeakIntoTheReportedOne() {
        long inRange = seedCompleted(limaNoon(ANCHOR), penId, new BigDecimal("100.00"));
        long outOfRange = seedCompleted(limaNoon(ANCHOR.plusDays(30)), penId, new BigDecimal("200.00"));
        operationsFixtures.seedAdditionalAssignment(inRange,
            operationsFixtures.seedDriver("Dentro", "Rango"), null, null, "Relevo del que entra");
        operationsFixtures.seedAdditionalAssignment(outOfRange,
            operationsFixtures.seedDriver("Fuera", "Rango"), null, null, "Relevo del que no entra");

        List<Map<String, Object>> rows = report(ANCHOR).getList("rows");

        assertEquals(1, rows.size());
        assertEquals(1, sizeOfAdditionalDrivers(rows, inRange));
        assertEquals("Dentro Rango", ((Map<?, ?>) ((List<?>) rows.get(0).get("additionalDrivers"))
            .get(0)).get("name"));
    }

    // ---------- El orden, que el contrato PUBLICA -------------------------------

    /**
     * Las filas salen por fecha de fin ASCENDENTE. El contrato lo declara: el desempate por id deja
     * las filas en un orden estable entre dos impresiones de la misma semana.
     *
     * <p>Lo que este caso aísla es ordenar por la fecha de INICIO o por el PRECIO, en cualquier
     * sentido: por eso los precios no son monótonos y hay un viaje cuyo inicio contradice el orden
     * de los fines. NO se lleva el crédito por el {@code DESC} —lo mata el caso del orden de los
     * totales, que siembra las dos monedas en días distintos— ni por borrar el {@code ORDER BY}
     * entero, que lo mata el del empate por id.
     *
     * <p>Se siembran en orden INVERSO al esperado a propósito: si el orden lo diera la inserción y
     * no la cláusula, el caso pasaría igual y no mediría nada.
     */
    @Test
    void report_ordersRowsByEndDateAscending_andNotByAnyOtherColumnThatHappensToAgree() {
        // Los precios van al REVÉS de las fechas y el que termina último EMPIEZA primero, así que
        // este caso solo pasa si se ordena por la fecha de FIN.
        //
        // Sin eso, la siembra escribía siempre inicio = fin − 6h, o sea un delta CONSTANTE: ordenar
        // por inicio daba exactamente el mismo orden y la mutación sobrevivía. Es el punto ciego de
        // las suites de delta en su otra forma — acá lo ciego no es el delta entre dos lecturas sino
        // la constante de la propia siembra.
        // Precios NO monótonos en ningún sentido: invertirlos respecto de las fechas cerraba
        // `price ASC` pero dejaba abierto `price DESC`, que reproducía el orden esperado.
        long tercero = seedCompleted(limaNoon(ANCHOR.plusDays(2)), penId, new BigDecimal("100.00"));
        long primero = seedCompleted(limaNoon(ANCHOR), penId, new BigDecimal("200.00"));
        long segundo = seedCompleted(limaNoon(ANCHOR.plusDays(1)), penId, new BigDecimal("500.00"));
        // El que termina ÚLTIMO arranca ANTES que todos.
        operationsFixtures.forceServiceDates(
            tercero, limaNoon(ANCHOR).minusHours(30), limaNoon(ANCHOR.plusDays(2)));

        List<Map<String, Object>> rows = report(ANCHOR).getList("rows");

        assertEquals(List.of(primero, segundo, tercero), rows.stream()
            .map(row -> ((Number) row.get("serviceId")).longValue()).toList());
    }

    /**
     * Dos refuerzos sumados en el MISMO instante vuelven los dos, y en orden de id.
     *
     * <p>⚠️ Este caso NO distingue el desempate por {@code a.id} de su ausencia, y conviene decirlo
     * en vez de dejar que el nombre prometa de más: borrar {@code , a.id} de la consulta lo deja en
     * verde. Se intentó con la técnica que sí funciona para las FILAS —reescribir la primera con
     * {@code rewriteAssignmentInPlace} para mandarla al final físico— y el orden sigue saliendo por
     * id igual, así que con dos filas el motor las devuelve así con cláusula y sin ella.
     *
     * <p>El desempate se conserva de todos modos: no es cosmético, es lo que hace que dos lecturas
     * de la misma semana den el mismo documento cuando dos relevos comparten instante, y el cutover
     * puede traerlos. Lo que no hay es una prueba que lo aísle; fingir que la hay sería peor.
     */
    @Test
    void report_withTwoReinforcementsAtTheSameInstant_returnsBoth() {
        long serviceId = seedCompleted(limaNoon(ANCHOR), penId, new BigDecimal("100.00"));
        OffsetDateTime sameInstant = limaNoon(ANCHOR).plusHours(1);
        long first = operationsFixtures.seedAdditionalAssignment(serviceId,
            operationsFixtures.seedDriver("Primero", "Empate"), null, null,
            "Relevo del primero por empate", sameInstant);
        operationsFixtures.seedAdditionalAssignment(serviceId,
            operationsFixtures.seedDriver("Segundo", "Empate"), null, null,
            "Relevo del segundo por empate", sameInstant);
        operationsFixtures.rewriteAssignmentInPlace(first);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> additionalDrivers =
            (List<Map<String, Object>>) onlyRow(report(ANCHOR)).get("additionalDrivers");

        assertEquals(List.of("Primero Empate", "Segundo Empate"),
            additionalDrivers.stream().map(driver -> (String) driver.get("name")).toList());
    }

    /**
     * Los totales van en el orden en que cada moneda APARECE en las filas, que también es contrato.
     *
     * <p>Van los DOS sentidos y hace falta que vayan los dos. Un solo caso no alcanza: sembrando USD
     * primero, un {@code HashMap} da justo {@code [USD, PEN]} y el caso pasa igual. No es azar del
     * día, es aritmética fija de estas dos monedas: {@code "USD".hashCode()} cae en el balde 7 y
     * {@code "PEN".hashCode()} en el 8 con la capacidad inicial, así que el mapa desordenado itera
     * exactamente en el orden que el caso esperaba. Sembrando PEN primero, ese mismo mapa devuelve
     * {@code [USD, PEN]} y el espejo se cae. Entre los dos queda cerrado el mapa sin orden por un
     * lado y el ordenamiento alfabético por el otro, que son opuestos: cada caso solo mata uno.
     */
    @ParameterizedTest
    @CsvSource({"USD,PEN", "PEN,USD"})
    void report_ordersTotalsByFirstAppearanceInRows(String firstSeeded, String secondSeeded) {
        seedCompleted(limaNoon(ANCHOR), currencyIdOf(firstSeeded), new BigDecimal("50.00"));
        seedCompleted(limaNoon(ANCHOR.plusDays(1)), currencyIdOf(secondSeeded), new BigDecimal("100.00"));

        List<Map<String, Object>> totals = report(ANCHOR).getList("totals");

        assertEquals(List.of(firstSeeded, secondSeeded),
            totals.stream().map(total -> (String) total.get("currencyCode")).toList());
    }

    private int currencyIdOf(String currencyCode) {
        return "USD".equals(currencyCode) ? usdId : penId;
    }

    /**
     * Los refuerzos salen por el momento en que se sumaron. El caso DICTA ese momento en vez de
     * confiar en el orden de inserción: sembrando en orden, PostgreSQL devuelve las filas en el
     * orden físico y un {@code ORDER BY} borrado pasaría igual (lo advierte el propio fixture).
     */
    @Test
    void report_ordersReinforcementsByWhenTheyWereAdded_notByInsertionOrder() {
        long serviceId = seedCompleted(limaNoon(ANCHOR), penId, new BigDecimal("100.00"));
        // El que se INSERTA primero es el que se sumó DESPUÉS.
        operationsFixtures.seedAdditionalAssignment(serviceId,
            operationsFixtures.seedDriver("Tarde", "Relevo"), null, null,
            "Relevo posterior de la ruta", limaNoon(ANCHOR).plusHours(3));
        operationsFixtures.seedAdditionalAssignment(serviceId,
            operationsFixtures.seedDriver("Temprano", "Relevo"), null, null,
            "Relevo inicial de la ruta", limaNoon(ANCHOR).plusHours(1));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> additionalDrivers =
            (List<Map<String, Object>>) onlyRow(report(ANCHOR)).get("additionalDrivers");

        assertEquals(List.of("Temprano Relevo", "Tarde Relevo"),
            additionalDrivers.stream().map(driver -> (String) driver.get("name")).toList());
    }

    // ---------- La fila que el contrato no puede publicar ------------------------

    /**
     * Un viaje COMPLETADO sin conductor principal NO se publica con un null ni desaparece: el
     * reporte falla, nombrando el viaje.
     *
     * <p>Las dos columnas son nullables en la base y el contrato las declara requeridas. Por la
     * aplicación no hay camino, pero el cutover escribe por fuera.
     *
     * <p>Lo que este caso mata en EXCLUSIVA es borrar la mitad del CONDUCTOR de la condición.
     * Borrar la guarda entera, cambiar su {@code throw} por un {@code return} o su {@code ||} por
     * un {@code &&} los mata también su hermano, el de la fecha de inicio. Se dice acá para que
     * nadie borre a ninguno de los dos creyendo que el otro lo cubre: cada uno tapa una mitad.
     */
    @Test
    void report_withACompletedServiceWithoutPrincipalDriver_failsLoudlyInsteadOfPublishingNull() {
        long serviceId = seedCompleted(limaNoon(ANCHOR), penId, new BigDecimal("100.00"));
        operationsFixtures.forceServiceResources(
            serviceId, null, operationsFixtures.seedTractor(), null);

        assertItFailsNamingTheService(serviceId);
    }

    /** El otro lado de la misma guarda: sin fecha de inicio, tampoco se publica. */
    @Test
    void report_withACompletedServiceWithoutStartDate_failsLoudlyInsteadOfPublishingNull() {
        long serviceId = seedCompleted(limaNoon(ANCHOR), penId, new BigDecimal("100.00"));
        operationsFixtures.forceServiceDates(serviceId, null, limaNoon(ANCHOR));

        assertItFailsNamingTheService(serviceId);
    }

    /**
     * Que el 500 salga NO alcanza: hay más de un camino que produce uno, y el que importa es el de
     * la guarda. Se afirma el código de la casa y que el detalle NOMBRE el viaje, que es lo que
     * convierte el fallo en algo diagnosticable.
     *
     * <p>Sin esta aserción, la mitad de la guarda que mira la fecha de inicio era código MUERTO —la
     * conversión de la columna reventaba dos capas antes— y el caso pasaba igual, con un 500 sin
     * código, sin forma de Problem y sin la línea de log que dice cuál es la fila mal migrada.
     */
    private void assertItFailsNamingTheService(long serviceId) {
        String code = given().header("Authorization", "Bearer " + adminToken)
            .when().get("/services/" + serviceId).then().statusCode(200)
            .extract().jsonPath().getString("code");

        reportRequest(adminToken, ANCHOR.toString())
            .then().statusCode(500)
            .body("code", equalTo("COM-500"))
            .body("detail", containsString(code));
    }

    /**
     * El ámbito sale de SU columna. Con un solo valor sembrado, devolver la constante —o leer otra
     * columna que dé lo mismo— pasaba en verde: el enum tiene dos valores y la siembra usaba uno.
     */
    @Test
    void report_readsTheTripScopeFromItsOwnColumn() {
        seedCompleted(limaNoon(ANCHOR), penId, new BigDecimal("100.00"), "LOCAL");

        assertEquals("LOCAL", onlyRow(report(ANCHOR)).get("tripScope"));
    }

    /**
     * La fecha de INICIO sale de su columna, afirmada por VALOR y no solo por ser anterior al fin.
     *
     * <p>Con la aserción relacional, leer {@code created_at} en su lugar pasaba: el viaje se crea
     * hoy y termina en 2099, así que seguía siendo anterior. El reporte publicaría una fecha de
     * inicio que no es la del viaje.
     */
    @Test
    void report_readsTheStartDateFromItsOwnColumn() {
        seedCompleted(limaNoon(ANCHOR), penId, new BigDecimal("100.00"));

        assertEquals(limaNoon(ANCHOR).minusHours(6).toInstant(),
            OffsetDateTime.parse((String) onlyRow(report(ANCHOR)).get("startDateTime"))
                .toInstant());
    }

    /**
     * La guarda de fila incompleta corre sobre TODAS las filas, no solo la primera.
     *
     * <p>Los dos casos de arriba siembran un viaje cada uno, así que sacar la guarda del bucle y
     * dejarla sobre el primer elemento sobrevivía. El escenario real es justamente ese: una semana con
     * varios viajes donde el del MEDIO vino del cutover sin fecha de inicio.
     */
    @Test
    void report_checksEveryRow_notOnlyTheFirst() {
        seedCompleted(limaNoon(ANCHOR), penId, new BigDecimal("100.00"));
        long second = seedCompleted(limaNoon(ANCHOR).plusHours(2), penId, new BigDecimal("200.00"));
        operationsFixtures.forceServiceDates(second, null, limaNoon(ANCHOR).plusHours(2));

        assertItFailsNamingTheService(second);
    }

    /**
     * Un rol que NO es admin lee un reporte con filas, y con sus importes.
     *
     * <p>Todos los demás casos de contenido corren con la sesión de admin, y el de los cuatro roles
     * permitidos solo mira el 200 sobre una semana vacía. Sin este caso, agregar al service un
     * "si no es admin, el precio va en null" —que es exactamente la forma que el listado y el
     * detalle usan para omitir importes— dejaba a ventas y a gerencia recibiendo el reporte de
     * facturación sin importes, y la suite entera en verde.
     */
    @Test
    void report_asSales_returnsTheRowsWithTheirAmounts() {
        seedCompleted(limaNoon(ANCHOR), penId, new BigDecimal("1234.56"));

        List<Map<String, Object>> rows = reportRequest(
                TestAuth.fabricateAccessToken("lcampos", "sales"), ANCHOR.toString())
            .then().statusCode(200).extract().jsonPath().getList("rows");
        assertEquals(1, rows.size(), "el caso esperaba exactamente una fila");
        Map<String, Object> row = rows.get(0);

        assertEquals(1234.56, ((Number) row.get("price")).doubleValue(), 0.001);
        assertEquals("PEN", row.get("currencyCode"));
    }

    /**
     * Una semana que todavía no cerró se consulta igual: devuelve 200 con {@code closed: false}, y
     * lo que se apaga es la exportación. Es lo que hace el sistema anterior, y la razón es que mirar
     * la semana abierta sirve; lo que no sirve es un archivo de bonos que parece definitivo sin serlo.
     *
     * <p>Las dos semanas son EXTREMOS de la ventana de negocio y no "hoy" ni "hace siete días", para
     * no violar la hermeticidad que declara el javadoc de la clase: la semana en curso real sí tiene
     * data de la copia de producción, y un viaje del traspaso sin fecha de inicio la haría fallar
     * con el 500 de la fila incompleta, que se leería como un defecto del endpoint. Lo que estos dos
     * casos miden es que el indicador EXISTA y respete los dos extremos de la ventana de negocio.
     *
     * <p>⚠️ Lo que NO miden es el cableado del reloj. Reemplazar {@code Instant.now()} por una
     * constante cualquiera entre {@code 1900-01-10} y {@code 2999-12-25} satisface a los dos, y se
     * verificó que esa mutación SOBREVIVE. No es por descuido: contra una constante arbitraria, la
     * única semana que discriminaría es una entre esa constante y ahora, que nadie puede elegir de
     * antemano, y las semanas cercanas a hoy están vedadas porque tienen data de la copia de
     * producción. Cerrarlo de verdad pide inyectar un {@code Clock}, que es un cambio de producción
     * y está anotado como decisión pendiente. El borde del corte sí lo mide
     * {@code OperationsWeekCycleTest}, que puede pasar el instante.
     */
    @Test
    void report_forAWeekThatHasNotClosed_isReadableButNotClosed() {
        JsonPath report = report(WEEK_THAT_NEVER_CLOSES);

        assertEquals(WEEK_THAT_NEVER_CLOSES.toString(), report.getString("weekCycle.start"));
        assertFalse(report.getBoolean("closed"), "una semana que no cerró NO está cerrada");
    }

    /** Una semana pasada sí está cerrada, que es la condición que la exportación va a exigir. */
    @Test
    void report_forAPastWeek_isClosed() {
        assertTrue(report(WEEK_LONG_CLOSED).getBoolean("closed"),
            "una semana pasada tiene que estar cerrada");
    }

    /**
     * Dos viajes que terminan en el MISMO instante salen por id ascendente. Es el único orden que
     * los demás casos no pueden ver, porque todos usan fechas distintas, y el javadoc de la consulta
     * dice que ese desempate no es cosmético: sin él, dos impresiones de la misma semana pueden salir
     * con las filas cambiadas de lugar.
     */
    @Test
    void report_withTwoServicesEndingAtTheSameInstant_ordersThemById() {
        OffsetDateTime sameInstant = limaNoon(ANCHOR);
        long first = seedCompleted(sameInstant, penId, new BigDecimal("100.00"));
        long second = seedCompleted(sameInstant, penId, new BigDecimal("200.00"));
        // Se reescribe la fila del PRIMERO para mandarla al final físico de la tabla: sin esto el
        // orden de inserción coincide con el de id y borrar el desempate pasaría igual.
        operationsFixtures.forceServiceDates(first, sameInstant.minusHours(6), sameInstant);

        assertEquals(List.of(first, second), report(ANCHOR).getList("rows").stream()
            .map(row -> ((Number) ((Map<?, ?>) row).get("serviceId")).longValue()).toList());
    }

    /**
     * El total se serializa con la ESCALA de la moneda. Comparado como decimal con tolerancia, sumar
     * en coma flotante o perder la escala pasaba en verde, y en un documento imprimible de
     * facturación un "301.0" donde va "301.00" es un defecto visible.
     */
    @Test
    void report_serializesTotalsWithTheCurrencyScale() {
        // Operandos NO representables en binario: 100.10 y 200.20 no son diádicos, así que sumarlos
        // en coma flotante da 300.29999999999995 y no 300.30. Con 100.50 + 200.25 —que sí son
        // exactos— la suma en double daba el mismo resultado y la mutación sobrevivía: el caso medía
        // la escala pero no la aritmética, aunque su nombre prometiera las dos.
        //
        // El literal cierra por la DERECHA con la llave: `totalRevenue` es el último campo del
        // objeto, así que sin eso la aserción también pasaría con 300.3000.
        seedCompleted(limaNoon(ANCHOR), penId, new BigDecimal("100.10"));
        seedCompleted(limaNoon(ANCHOR).plusHours(1), penId, new BigDecimal("200.20"));

        assertTrue(rawReport(ANCHOR).contains("\"totalRevenue\":300.30}"),
            "el total tiene que sumarse en decimal exacto y viajar con la escala de la moneda");
    }

    // ---------- Validación de la semana pedida --------------------------------------

    /**
     * Sin semana, 400. Va con el vacío al lado porque son caminos DISTINTOS de entrada aunque
     * terminen en la misma rama: el ausente no trae el parámetro y el vacío lo trae en blanco, que
     * es lo que manda un formulario cuando el usuario no eligió nada.
     */
    @ParameterizedTest
    @NullAndEmptySource
    void report_withoutTheWeek_returns400(String weekStart) {
        reportRequest(adminToken, weekStart).then().statusCode(400)
            .body("code", equalTo("COM-001"));
    }

    /**
     * Fechas que no sirven. El borde inferior de la ventana de negocio va con un MIÉRCOLES
     * ({@code 1899-12-31} es domingo): con un día cualquiera lo rechazaría la regla del miércoles y
     * el caso quedaría verde aunque alguien borrara la ventana entera. {@code 2099-03-12} es un
     * jueves bien formado y dentro de ventana, y es el único valor que aísla la regla del miércoles
     * a través del recurso (hasta acá solo la medía el unit del mapper).
     *
     * <p>Los DOS bordes de la ventana van con miércoles: {@code 1899-12-27} y {@code 3000-01-01}
     * (que es el miércoles siguiente a {@code 2999-12-25}, el último aceptable). Sin el de arriba,
     * borrar la mitad superior de la comprobación sobrevive esta suite.
     */
    @ParameterizedTest
    @ValueSource(strings = {
        "11/03/2099", "2099-02-31", "ayer", "+999999999-12-31",
        "1899-12-27", "3000-01-01", "2099-03-12"})
    void report_withAnUnusableDate_returns400(String weekStart) {
        reportRequest(adminToken, weekStart).then().statusCode(400)
            .body("code", equalTo("COM-001"));
    }

    // ---------- Autorización -------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"admin", "sales", "general_manager", "operations_manager"})
    void report_withARoleThatSeesPrices_returns200(String role) {
        reportRequest(TestAuth.fabricateAccessToken("reportuser", role), ANCHOR.toString())
            .then().statusCode(200);
    }

    /**
     * El despacho NO recibe una versión sin importes: recibe 403. Es un reporte de facturación
     * entero, no una fila con dos campos omitidos (RN-OP8).
     *
     */
    @Test
    void report_asDispatcher_returns403() {
        reportRequest(TestAuth.fabricateAccessToken("jcamones", "dispatcher"), ANCHOR.toString())
            .then().statusCode(403);
    }

    /**
     * El caso que PINCHA la lista de {@code @RolesAllowed} por separado del veto.
     *
     * <p>Los dos rechazos son 403 con el MISMO código —el manejador de la casa mapea igual el del
     * framework— así que el cuerpo no los distingue. Lo que sí los distingue es CUÁNDO ocurren: la
     * lista de roles corre antes del cuerpo del método, o sea antes de que se parseen los
     * parámetros; el veto corre después. Con una fecha ilegible, entonces, un rol FUERA de la lista
     * recibe 403 y uno vetado DENTRO de ella recibe 400.
     *
     * <p>Sin este caso, borrar el {@code @RolesAllowed} entero dejaba la suite en verde: el veto
     * también rechaza al despacho, así que el 403 seguía saliendo. La reja que el contrato publica
     * como {@code x-required-roles} quedaba sin nadie que la midiera.
     */
    @Test
    void report_asDispatcherWithAnUnusableDate_returns403_provingTheRoleListRunsFirst() {
        reportRequest(TestAuth.fabricateAccessToken("jcamones", "dispatcher"), "ayer")
            .then().statusCode(403);
    }

    /**
     * Y su complemento: quien SÍ pasa la lista de roles llega al parseo, así que con la misma fecha
     * ilegible recibe 400 y no el 403 del veto. Los dos casos juntos fijan el orden.
     */
    @Test
    void report_asDispatcherWhoAlsoSellsWithAnUnusableDate_returns400_becauseParsingRunsFirst() {
        reportRequest(
            TestAuth.fabricateAccessTokenWithRoles("dual", Set.of("dispatcher", "sales")), "ayer")
            .then().statusCode(400).body("code", equalTo("COM-001"));
    }

    /**
     * Un rol que no está en NINGUNA de las dos listas —ni en la del recurso ni entre los que ven
     * precios— tampoco entra. Es el caso que sostiene que la lista sea POSITIVA: un rol nuevo no
     * hereda el permiso por no haber sido nombrado.
     */
    @ParameterizedTest
    @ValueSource(strings = {"warehouse_keeper", "finance_manager"})
    void report_withARoleOutsideBothLists_returns403(String role) {
        reportRequest(TestAuth.fabricateAccessToken("ajeno", role), ANCHOR.toString()).then().statusCode(403);

        // Y con la fecha ILEGIBLE también 403, que es lo que PINCHA la lista de roles. Con solo la
        // aserción de arriba, agregar estos roles al @RolesAllowed sobrevivía: el veto los rechaza
        // igual, con el mismo 403 y el mismo código. La lista es lo que el contrato publica como
        // x-required-roles, así que ese drift no lo veía nadie salvo para el despacho. Si alguien
        // los agrega, el parseo pasa a correr y esto se vuelve 400.
        reportRequest(TestAuth.fabricateAccessToken("ajeno", role), "ayer").then().statusCode(403);
    }

    /**
     * Y el caso que hace falta que el VETO exista además de la lista de roles: un usuario que sumara
     * despacho y ventas ENTRA por la lista —que es un O— y tiene que rebotar igual.
     *
     * <p>Sin la llamada al veto en el service este caso da 200 y el despacho ve toda la facturación.
     */
    @Test
    void report_asDispatcherWhoAlsoSells_returns403_becauseTheVetoWins() {
        reportRequest(
            TestAuth.fabricateAccessTokenWithRoles("dual", Set.of("dispatcher", "sales")),
            ANCHOR.toString())
            .then().statusCode(403).body("code", equalTo("COM-003"));
    }

    @Test
    void report_withoutToken_returns401() {
        given().accept(ContentType.JSON)
            .queryParam("weekStart", ANCHOR.toString())
        .when().get("/services/report")
        .then().statusCode(401);
    }

    // ---------- Ruteo y cabeceras ---------------------------------------------------

    /**
     * {@code /services/report} NO puede caer en el detalle. El detalle recibe su id como TEXTO, así
     * que su plantilla matchea el literal sin ningún filtro de tipo: si la precedencia fallara,
     * el reporte contestaría "el servicio no existe".
     */
    @Test
    void report_doesNotFallIntoTheDetailRoute() {
        // El 200 ya alcanza: si "report" cayera en la plantilla del detalle, el id no parsearía y
        // saldría un 400, no un 200. Se afirma además la FORMA del cuerpo para que el caso no se
        // conforme con cualquier respuesta exitosa de otra ruta.
        String body = reportRequest(adminToken, ANCHOR.toString())
            .then().statusCode(200).extract().asString();

        assertTrue(body.contains("\"weekCycle\""), "el cuerpo tiene que ser el del reporte");
    }

    /** El cuerpo son todo importes: no debe sobrevivir a la sesión ni servirse a otro rol. */
    @Test
    void report_sendsNoStoreAndVary() {
        reportRequest(adminToken, ANCHOR.toString())
            .then().statusCode(200)
            .header("Cache-Control", "no-store")
            .header("Vary", "Authorization");
    }

    // ---------- Helpers --------------------------------------------------------------

    /**
     * Conductor de relleno, para los casos que NO miran quién es. Los que sí afirman un nombre
     * siembran el suyo explícito: atar la expectativa a "Conductor1" la haría depender de cuántos
     * sembró antes este contador.
     */
    private int seedDriver() {
        int seq = ++driverSeq;
        return operationsFixtures.seedDriver("Conductor" + seq, "Reporte" + seq);
    }

    /** El código del viaje, leído del detalle: el caso afirma el VALOR, no que exista. */
    private String codeOf(long serviceId) {
        return fieldOfDetail(serviceId, "code");
    }

    private String clientNameOf(long serviceId) {
        return fieldOfDetail(serviceId, "client.name");
    }

    private String fieldOfDetail(long serviceId, String path) {
        return given().header("Authorization", "Bearer " + adminToken)
            .when().get("/services/" + serviceId).then().statusCode(200)
            .extract().jsonPath().getString(path);
    }

    private static OffsetDateTime limaDayStart(LocalDate date) {
        return date.atStartOfDay(DateUtils.LIMA).toOffsetDateTime();
    }

    private static OffsetDateTime limaNoon(LocalDate date) {
        return limaDayStart(date).plusHours(12);
    }

    private io.restassured.response.Response reportRequest(String token, String weekStart) {
        var request = given().header("Authorization", "Bearer " + token).accept(ContentType.JSON);
        if (weekStart != null) {
            request = request.queryParam("weekStart", weekStart);
        }
        return request.when().get("/services/report");
    }

    /** El cuerpo tal cual viaja, sin que ninguna librería lo re-interprete. */
    private String rawReport(LocalDate weekStart) {
        return reportRequest(adminToken, weekStart.toString())
            .then().statusCode(200).extract().asString();
    }

    private JsonPath report(LocalDate weekStart) {
        return reportRequest(adminToken, weekStart.toString())
            .then().statusCode(200).extract().jsonPath();
    }

    private static Map<String, Object> onlyRow(JsonPath report) {
        List<Map<String, Object>> rows = report.getList("rows");
        assertEquals(1, rows.size(), "el caso esperaba exactamente una fila");
        return rows.get(0);
    }

    private static int totalServicesOf(JsonPath report, String currencyCode) {
        return report.getInt("totals.find { it.currencyCode == '" + currencyCode + "' }.totalServices");
    }

    private static double totalRevenueOf(JsonPath report, String currencyCode) {
        return report.getDouble("totals.find { it.currencyCode == '" + currencyCode + "' }.totalRevenue");
    }

    private static int sizeOfAdditionalDrivers(List<Map<String, Object>> rows, long serviceId) {
        return rows.stream()
            .filter(row -> ((Number) row.get("serviceId")).longValue() == serviceId)
            .findFirst()
            .map(row -> ((List<?>) row.get("additionalDrivers")).size())
            .orElseThrow(() -> new AssertionError("no vino la fila del viaje " + serviceId));
    }

    /**
     * Un viaje COMPLETADO con la fecha de fin, la moneda y el importe que el caso necesita, y con su
     * conductor principal.
     *
     * <p>La siembra se VERIFICA: un {@code UPDATE} que afectara cero filas dejaría al caso midiendo
     * una semana vacía y varios de los de arriba afirman justamente que una semana da cero, así que sin
     * esto pasarían por la razón contraria.
     */
    private long seedCompleted(OffsetDateTime endDateTime, int currencyId, BigDecimal price) {
        return seedCompleted(endDateTime, currencyId, price, "PROVINCIA");
    }

    private long seedCompleted(
            OffsetDateTime endDateTime, int currencyId, BigDecimal price, String tripScope) {
        return seedCompleted(endDateTime, currencyId, price, tripScope, seedDriver());
    }

    private long seedCompleted(OffsetDateTime endDateTime, int currencyId, BigDecimal price,
            String tripScope, int principalDriverId) {
        long serviceId = createService(currencyId, price, tripScope);
        operationsFixtures.forceServiceStatus(serviceId, ServiceStatus.COMPLETED.name());
        operationsFixtures.forceServiceResources(
            serviceId, principalDriverId, operationsFixtures.seedTractor(), null);
        operationsFixtures.forceServiceDates(serviceId, endDateTime.minusHours(6), endDateTime);

        // La precondición se verifica contra la BASE, no contra el reporte. Consultarla con el
        // endpoint que se está probando tiene dos costos: la precondición se apoya en el sujeto de
        // la prueba, y cualquier mutación de la ventana o del orden hace fallar la clase entera con
        // un mensaje que culpa al fixture y esconde que el defecto estaba en el endpoint.
        OffsetDateTime[] seeded = operationsFixtures.serviceDatesOf(serviceId);
        assertEquals(endDateTime.toInstant(), seeded[1].toInstant(),
            "la siembra no quedó como el caso la pidió: la fecha de fin no es la que se forzó");
        assertEquals(endDateTime.minusHours(6).toInstant(), seeded[0].toInstant(),
            "la siembra no quedó como el caso la pidió: la fecha de inicio no es la que se forzó");
        return serviceId;
    }

    private long createService(int currencyId, BigDecimal price, String tripScope) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("clientId", clientId);
        body.put("tripScope", tripScope);
        body.put("tentativeDate", LocalDate.now(DateUtils.LIMA).plusDays(3).toString());
        // Ruta propia por viaje: el alta rechaza como doble-click dos altas del mismo cliente y la
        // misma ruta dentro de la ventana, y varios casos arman tres seguidos.
        String origin = "Piura report " + (++routeSeq);
        String destination = "Lima report " + routeSeq;
        body.put("origin", origin);
        body.put("destination", destination);
        body.put("cargoTypeId", cargoTypeId);
        body.put("weightKg", 12000);
        body.put("price", price);
        body.put("currencyId", currencyId);

        long serviceId = given()
            .header("Authorization", "Bearer " + adminToken)
            .contentType(ContentType.JSON)
            .body(body)
        .when()
            .post("/services")
        .then()
            .statusCode(201)
            .extract().jsonPath().getLong("id");

        seededRoutes.put(serviceId, new String[] {origin, destination});
        return serviceId;
    }

    /** La ruta que el caso MANDÓ en el alta, para no usar otro endpoint como oráculo. */
    private String seededOrigin(long serviceId) {
        return seededRoutes.get(serviceId)[0];
    }

    private String seededDestination(long serviceId) {
        return seededRoutes.get(serviceId)[1];
    }
}
