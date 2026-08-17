package com.scaramutti.tms.operations.service;

import com.scaramutti.tms.operations.dto.ServiceStatsResponse;
import com.scaramutti.tms.shared.repository.ServiceStatsRepository;
import com.scaramutti.tms.shared.repository.ServiceStatsRepository.ServiceStatsRow;
import com.scaramutti.tms.shared.util.DateUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.TemporalAdjusters;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Unit del armado de la respuesta: que cada número de la fila llegue a SU campo. */
class GetServiceStatsServiceTest {

    private final ServiceStatsRepository serviceStatsRepository = mock(ServiceStatsRepository.class);

    private GetServiceStatsService getServiceStatsService;

    @BeforeEach
    void setUp() {
        getServiceStatsService = new GetServiceStatsService();
        getServiceStatsService.serviceStatsRepository = serviceStatsRepository;
    }

    /**
     * Los ocho valores son DISTINTOS entre sí a propósito: con números repetidos, cruzar
     * {@code active} con {@code total}, o los conductores con los tractos, pasa desapercibido — y
     * ese cruce es el error que el armado a mano puede cometer y el compilador no ve, porque los
     * ocho son {@code int}.
     */
    @Test
    void getServiceStats_mapsEveryNumberToItsOwnField() {
        when(serviceStatsRepository.getStats(any(), any()))
            .thenReturn(new ServiceStatsRow(11, 22, 33, 44, 55, 66, 77, 88));

        ServiceStatsResponse stats = getServiceStatsService.getServiceStats();

        assertEquals(11, stats.pendingAssignment());
        assertEquals(22, stats.pendingStart());
        assertEquals(33, stats.inProgress());
        assertEquals(44, stats.completedThisWeek());
        assertEquals(55, stats.driversOnRoad().active());
        assertEquals(66, stats.driversOnRoad().total());
        assertEquals(77, stats.unitsOnRoad().active());
        assertEquals(88, stats.unitsOnRoad().total());
    }

    /**
     * Con todo en cero la respuesta sigue trayendo el ciclo: ese no sale de la fila.
     *
     * <p>Solo se afirma el ciclo. Comparar los ceros contra cero no distinguiría nada —con la fila
     * entera en cero, cualquier permutación de los ocho campos, y hasta un cero fijo, pasa igual—;
     * de que cada número llegue a SU campo se ocupa el caso de arriba, que usa ocho valores
     * distintos.
     */
    @Test
    void getServiceStats_withAnEmptyRow_stillCarriesTheWeekCycle() {
        when(serviceStatsRepository.getStats(any(), any()))
            .thenReturn(new ServiceStatsRow(0, 0, 0, 0, 0, 0, 0, 0));

        ServiceStatsResponse stats = getServiceStatsService.getServiceStats();

        assertEquals(DayOfWeek.WEDNESDAY, stats.weekCycle().start().getDayOfWeek());
        assertEquals(DayOfWeek.TUESDAY, stats.weekCycle().end().getDayOfWeek());
    }

    /**
     * Los bordes que recibe la consulta son las MEDIANOCHES DE LIMA, y el de arriba es exclusivo.
     * Sin este caso, calcular la ventana con el huso del servidor —el defecto del sistema
     * anterior— no lo detecta nada de este nivel.
     */
    @Test
    void getServiceStats_asksTheQueryForTheLimaMidnightsOfTheCurrentCycle() {
        when(serviceStatsRepository.getStats(any(), any()))
            .thenReturn(new ServiceStatsRow(0, 0, 0, 0, 0, 0, 0, 0));
        LocalDate expectedStart = LocalDate.now(DateUtils.LIMA)
            .with(TemporalAdjusters.previousOrSame(DayOfWeek.WEDNESDAY));

        getServiceStatsService.getServiceStats();

        ArgumentCaptor<OffsetDateTime> start = ArgumentCaptor.forClass(OffsetDateTime.class);
        ArgumentCaptor<OffsetDateTime> endExclusive = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(serviceStatsRepository).getStats(start.capture(), endExclusive.capture());

        assertEquals(expectedStart.atStartOfDay(DateUtils.LIMA).toOffsetDateTime(), start.getValue());
        assertEquals(expectedStart.plusDays(7).atStartOfDay(DateUtils.LIMA).toOffsetDateTime(),
            endExclusive.getValue());
    }

    /**
     * El fin que se publica está UN DÍA antes del borde con el que se consulta. Es la confusión
     * más fácil de cometer acá, y la única forma de verla es comparar los dos.
     */
    @Test
    void getServiceStats_publishesTheInclusiveTuesdayNotTheExclusiveBound() {
        when(serviceStatsRepository.getStats(any(), any()))
            .thenReturn(new ServiceStatsRow(0, 0, 0, 0, 0, 0, 0, 0));

        ServiceStatsResponse stats = getServiceStatsService.getServiceStats();

        ArgumentCaptor<OffsetDateTime> endExclusive = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(serviceStatsRepository).getStats(any(), endExclusive.capture());

        assertEquals(stats.weekCycle().end().plusDays(1),
            endExclusive.getValue().withOffsetSameInstant(
                DateUtils.LIMA.getRules().getOffset(endExclusive.getValue().toInstant())).toLocalDate());
        assertEquals(stats.weekCycle().start().plusDays(6), stats.weekCycle().end());
    }
}
