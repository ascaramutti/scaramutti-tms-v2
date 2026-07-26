package com.scaramutti.tms.warehouse.stats.service;

import com.scaramutti.tms.warehouse.stats.mapper.WarehouseStatsServiceMapper;
import com.scaramutti.tms.shared.repository.WarehouseStatsRepository;
import com.scaramutti.tms.shared.repository.WarehouseStatsRepository.WarehouseStatsRow;
import com.scaramutti.tms.shared.util.DateUtils;
import com.scaramutti.tms.warehouse.stats.dto.WarehouseStatsResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mapstruct.factory.Mappers;

import java.time.LocalDate;
import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests del service de stats. Cubre: mapeo 1:1 de la fila del repo al
 * response y el calculo de los bordes del mes calendario en curso (America/Lima,
 * semiabierto). El SQL real (los 4 contadores, anulados, low_stock estricto) lo
 * cubren los integration tests (WarehouseStatsResourceTest).
 */
@ExtendWith(MockitoExtension.class)
class WarehouseStatsServiceTest {

    @Mock WarehouseStatsRepository warehouseStatsRepository;
    @InjectMocks WarehouseStatsService warehouseStatsService;

    // El mapper es un colaborador REAL (impl generada por MapStruct), no un mock:
    // estos tests cubren justamente el shaping fila a response.
    @BeforeEach
    void wireRealMapper() {
        warehouseStatsService.warehouseStatsServiceMapper = Mappers.getMapper(WarehouseStatsServiceMapper.class);
    }

    @Test
    void getStats_mapsRepositoryRowToResponseFields() {
        when(warehouseStatsRepository.getStats(any(), any()))
            .thenReturn(new WarehouseStatsRow(42, 3, 7, 12));

        WarehouseStatsResponse response = warehouseStatsService.getStats();

        assertEquals(42, response.activeProducts());
        assertEquals(3, response.lowStockCount());
        assertEquals(7, response.entriesThisMonth());
        assertEquals(12, response.withdrawalsThisMonth());
    }

    @Test
    void getStats_zeroRepositoryResults_returnsAllZero() {
        when(warehouseStatsRepository.getStats(any(), any()))
            .thenReturn(new WarehouseStatsRow(0, 0, 0, 0));

        WarehouseStatsResponse response = warehouseStatsService.getStats();

        assertEquals(0, response.activeProducts());
        assertEquals(0, response.lowStockCount());
        assertEquals(0, response.entriesThisMonth());
        assertEquals(0, response.withdrawalsThisMonth());
    }

    @Test
    void getStats_passesCurrentCalendarMonthBoundsInLima() {
        when(warehouseStatsRepository.getStats(any(), any()))
            .thenReturn(new WarehouseStatsRow(0, 0, 0, 0));

        warehouseStatsService.getStats();

        ArgumentCaptor<OffsetDateTime> startCaptor = ArgumentCaptor.forClass(OffsetDateTime.class);
        ArgumentCaptor<OffsetDateTime> endCaptor = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(warehouseStatsRepository).getStats(startCaptor.capture(), endCaptor.capture());

        LocalDate firstOfMonth = LocalDate.now(DateUtils.LIMA).withDayOfMonth(1);
        assertEquals(
            firstOfMonth.atStartOfDay(DateUtils.LIMA).toOffsetDateTime(),
            startCaptor.getValue());
        assertEquals(
            firstOfMonth.plusMonths(1).atStartOfDay(DateUtils.LIMA).toOffsetDateTime(),
            endCaptor.getValue());
    }
}
