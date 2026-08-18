package com.scaramutti.tms.operations.service;

import com.scaramutti.tms.operations.dto.embedded.ServiceWeekCycleSummary;
import com.scaramutti.tms.shared.exception.ApiException;
import com.scaramutti.tms.shared.util.DateUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * El ciclo operativo (RN-OP14), medido con instantes LITERALES.
 *
 * <p>Esta clase existe por un motivo concreto y no por completitud: desde un test de integración,
 * todo borde de día de la semana depende del día en que corra la suite. Un caso "martes 23:00"
 * discrimina un corte lunes→domingo solo si hoy cae en ciertos días, así que la regla más delicada
 * del módulo quedaría cubierta a medias y de forma intermitente. Acá los instantes se escriben, y
 * la prueba vale para los siete días de la semana.
 *
 * <p>Las tres formas de equivocarse que estos casos matan: usar el huso del SERVIDOR en vez del de
 * Lima (que es el defecto real del tablero del sistema anterior), cortar lunes→domingo, y tomar el
 * ciclo como los últimos siete días móviles.
 *
 * <p>Ciclo de referencia: miércoles 2026-08-19 a martes 2026-08-25.
 */
class OperationsWeekCycleTest {

    /** Lima es UTC-5 sin horario de verano, así que la medianoche de Lima son las 05:00 UTC. */
    private static final String CYCLE_OPENS_UTC = "2026-08-19T05:00:00Z";

    /**
     * Los siete días del ciclo resuelven a la MISMA ventana.
     *
     * <p>Mata el corte lunes→domingo: el lunes 24 y el martes 25 caen en la semana Mon→Sun
     * SIGUIENTE y darían otra ventana. Y mata el ciclo móvil, que daría siete ventanas distintas.
     */
    @ParameterizedTest
    @ValueSource(strings = {
        "2026-08-19", "2026-08-20", "2026-08-21", "2026-08-22",
        "2026-08-23", "2026-08-24", "2026-08-25"
    })
    void at_anyDayOfTheCycle_resolvesToTheSameWednesdayToTuesdayWindow(String limaDate) {
        // Mediodía de Lima: lejos de los dos bordes, para que este caso mida el DÍA y no la hora.
        ServiceWeekCycleSummary cycle = OperationsWeekCycle.at(
            Instant.parse(limaDate + "T17:00:00Z"));

        assertEquals(LocalDate.parse("2026-08-19"), cycle.start());
        assertEquals(LocalDate.parse("2026-08-25"), cycle.end());
    }

    /**
     * EL caso canónico, vuelto determinista: martes 23:00 de Lima pertenece al ciclo que CIERRA.
     *
     * <p>En UTC ese instante ya es miércoles, así que una implementación que calcule el día con el
     * huso del servidor lo manda a la semana siguiente. Es el defecto exacto del tablero anterior.
     */
    @Test
    void at_tuesdayElevenPmLima_belongsToTheClosingCycle() {
        ServiceWeekCycleSummary cycle = OperationsWeekCycle.at(
            Instant.parse("2026-08-19T04:00:00Z"));   // martes 2026-08-18, 23:00 Lima

        assertEquals(LocalDate.parse("2026-08-12"), cycle.start());
        assertEquals(LocalDate.parse("2026-08-18"), cycle.end());
    }

    /**
     * Y su gemelo, a UNA HORA de distancia: la medianoche del miércoles de Lima abre el ciclo
     * nuevo. El par es lo que hace que no haya forma de pasar los dos con el huso equivocado.
     */
    @Test
    void at_wednesdayMidnightLima_opensTheNewCycle() {
        ServiceWeekCycleSummary cycle = OperationsWeekCycle.at(
            Instant.parse(CYCLE_OPENS_UTC));          // miércoles 2026-08-19, 00:00 Lima

        assertEquals(LocalDate.parse("2026-08-19"), cycle.start());
        assertEquals(LocalDate.parse("2026-08-25"), cycle.end());
    }

    /** Los dos bordes de la CONSULTA, que no son los dos que se publican. */
    @Test
    void theQueryBoundsAreTheLimaMidnights() {
        ServiceWeekCycleSummary cycle = OperationsWeekCycle.at(Instant.parse(CYCLE_OPENS_UTC));

        assertEquals(OffsetDateTime.parse(CYCLE_OPENS_UTC),
            OperationsWeekCycle.startInstant(cycle).withOffsetSameInstant(java.time.ZoneOffset.UTC));
        assertEquals(OffsetDateTime.parse("2026-08-26T05:00:00Z"),
            OperationsWeekCycle.endExclusiveInstant(cycle).withOffsetSameInstant(java.time.ZoneOffset.UTC));
    }

    /**
     * El fin que se PUBLICA es el martes; el que se CONSULTA es el miércoles siguiente. Están a un
     * día de distancia y confundirlos corre la etiqueta un dia.
     */
    @Test
    void thePublishedEndIsTheTuesdayNotTheExclusiveWednesday() {
        ServiceWeekCycleSummary cycle = OperationsWeekCycle.at(Instant.parse(CYCLE_OPENS_UTC));

        assertEquals(DayOfWeek.TUESDAY, cycle.end().getDayOfWeek());
        assertEquals(cycle.end().plusDays(1),
            OperationsWeekCycle.endExclusiveInstant(cycle).toLocalDate());
    }

    /** El ciclo cruza el fin de año sin tratarlo distinto. Mata la aritmética por semana ISO. */
    @Test
    void at_aCycleSpanningTheYearBoundary_isComputedAcrossYears() {
        ServiceWeekCycleSummary cycle = OperationsWeekCycle.at(
            Instant.parse("2026-01-03T17:00:00Z"));   // sábado 2026-01-03, mediodía de Lima

        assertEquals(LocalDate.parse("2025-12-31"), cycle.start());
        assertEquals(LocalDate.parse("2026-01-06"), cycle.end());
    }

    /** Invariantes que valen para cualquier fecha del año, incluido el cambio de mes.
     *  Seis días de distancia entre los dos bordes publicados, o sea un ciclo de siete. */
    @ParameterizedTest
    @CsvSource({
        "2026-01-01", "2026-02-28", "2026-03-01", "2026-04-15", "2026-06-30",
        "2026-07-01", "2026-09-30", "2026-10-01", "2026-12-31"
    })
    void at_anyDate_opensOnWednesdayAndClosesSixDaysLater(String limaDate) {
        ServiceWeekCycleSummary cycle = OperationsWeekCycle.at(
            Instant.parse(limaDate + "T17:00:00Z"));

        assertEquals(DayOfWeek.WEDNESDAY, cycle.start().getDayOfWeek());
        assertEquals(6, ChronoUnit.DAYS.between(cycle.start(), cycle.end()));
    }

    /** Y el ciclo siempre CONTIENE al instante que lo pidió: ni adelantado ni atrasado. */
    @ParameterizedTest
    @ValueSource(strings = {
        "2026-08-19T05:00:00Z", "2026-08-22T12:00:00Z", "2026-08-26T04:59:59Z"
    })
    void at_anyInstant_theCycleContainsIt(String instant) {
        ServiceWeekCycleSummary cycle = OperationsWeekCycle.at(Instant.parse(instant));
        OffsetDateTime asked = OffsetDateTime.parse(instant);

        assertTrue(!asked.isBefore(OperationsWeekCycle.startInstant(cycle)),
            "el ciclo empieza después del instante que lo pidió");
        assertTrue(asked.isBefore(OperationsWeekCycle.endExclusiveInstant(cycle)),
            "el ciclo termina antes del instante que lo pidió");
    }

    // ---------- Si el ciclo ya cerro ---------------------------------------------

    /**
     * El corte es el borde EXCLUSIVO —el miércoles siguiente a las 00:00 de Lima— y no el martes que
     * se publica. Los tres instantes están a un microsegundo de distancia entre sí, que es la
     * resolución de la columna, y ese es el único borde que importa.
     *
     * <p>Se prueba con instantes LITERALES porque el reloj no sirve: desde un test de integración
     * este borde solo se podría discriminar el día exacto en que la semana cierra, y una mutación
     * que corra el corte un día sobrevive cualquier caso que use una semana futura o una de hace
     * siete días. Eso pasó, y por eso este método existe acá y no dentro del service.
     */
    @Test
    void hasClosed_isDecidedByTheExclusiveBoundary_notByTheTuesdayItPublishes() {
        ServiceWeekCycleSummary cycle = OperationsWeekCycle.at(Instant.parse(CYCLE_OPENS_UTC));
        Instant closesAt = OperationsWeekCycle.endExclusiveInstant(cycle).toInstant();

        assertFalse(OperationsWeekCycle.hasClosed(cycle, closesAt.minusNanos(1_000)),
            "un microsegundo antes del borde la semana sigue ABIERTA");
        assertTrue(OperationsWeekCycle.hasClosed(cycle, closesAt),
            "en el borde exacto ya cerró: la ventana es semiabierta");
        assertTrue(OperationsWeekCycle.hasClosed(cycle, closesAt.plusNanos(1_000)),
            "y después, obviamente");
    }

    /**
     * El martes ENTERO la semana sigue abierta. Es el caso que separa el borde publicado del
     * consultado: atar el corte a {@code weekCycle.end()} daría por cerrada la semana durante todo
     * su último día, y el archivo de bonos saldría un día antes de tiempo.
     */
    @Test
    void hasClosed_isFalseForTheWholeLastDay() {
        ServiceWeekCycleSummary cycle = OperationsWeekCycle.at(Instant.parse(CYCLE_OPENS_UTC));
        Instant tuesdayNoonLima = DateUtils.limaDayStart(cycle.end()).plusHours(12).toInstant();

        assertFalse(OperationsWeekCycle.hasClosed(cycle, tuesdayNoonLima),
            "el martes al mediodía la semana todavía no cerró");
    }

    /**
     * El ciclo del reporte sale del dia PEDIDO, sin fabricar un instante intermedio. Fija las dos
     * mitades: que el miercoles pedido sea el que abre, y que el martes publicado sea el sexto dia.
     */
    @Test
    void startingOn_aWednesday_opensThatDayAndClosesSixDaysLater() {
        LocalDate wednesday = LocalDate.of(2027, 3, 10);

        ServiceWeekCycleSummary cycle = OperationsWeekCycle.startingOn(wednesday);

        assertEquals(wednesday, cycle.start());
        assertEquals(wednesday.plusDays(6), cycle.end());
    }

    /**
     * Cualquier otro dia es un error de PROGRAMACION: el rechazo con 400 que ve el cliente lo hace el
     * mapper, antes. Se afirma el codigo y no solo el tipo, porque lo que importa es que salga como
     * el Problem de la casa: un {@code IllegalArgumentException} no tiene mapper y daria un 500
     * pelado, sin {@code code} ni {@code detail}.
     */
    @ParameterizedTest
    @ValueSource(strings = {"2027-03-11", "2027-03-12", "2027-03-13", "2027-03-14", "2027-03-15",
        "2027-03-16"})
    void startingOn_anyOtherDay_failsWithTheHouseProblem(String notAWednesday) {
        ApiException thrown = assertThrows(ApiException.class,
            () -> OperationsWeekCycle.startingOn(LocalDate.parse(notAWednesday)));

        assertEquals("COM-500", thrown.code());
        assertTrue(thrown.getMessage().contains("miercoles"),
            "el mensaje tiene que decir en qué día abre el ciclo");
        assertTrue(thrown.getMessage().contains(notAWednesday),
            "y cuál se pidió: sin eso, la mitad final del mensaje no la mide nadie");
    }
}
