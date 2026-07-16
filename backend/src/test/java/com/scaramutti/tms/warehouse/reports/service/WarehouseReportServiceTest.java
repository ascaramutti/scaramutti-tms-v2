package com.scaramutti.tms.warehouse.reports.service;

import com.scaramutti.tms.shared.exception.ApiException;
import com.scaramutti.tms.shared.repository.WarehouseReportRepository;
import com.scaramutti.tms.shared.repository.WarehouseReportRepository.ReportRowView;
import com.scaramutti.tms.warehouse.reports.dto.WarehouseReportResponse;
import com.scaramutti.tms.warehouse.reports.model.WarehouseReportCut;
import com.scaramutti.tms.warehouse.reports.service.cmd.GetWarehouseReportQuery;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests del service de reportes. Cubre: dispatch por corte a la query
 * correcta, validacion cross-field dateFrom>dateTo (COM-001, antes de tocar el
 * repo), totales = suma de las filas y el corte BY_SUPPLIER que pasa LocalDate
 * (no OffsetDateTime). El SQL real de cada agregacion lo cubren los integration
 * tests (WarehouseReportResourceTest).
 */
@ExtendWith(MockitoExtension.class)
class WarehouseReportServiceTest {

    @Mock WarehouseReportRepository warehouseReportRepository;
    @InjectMocks WarehouseReportService warehouseReportService;

    private static final LocalDate FROM = LocalDate.of(2026, 6, 1);
    private static final LocalDate TO = LocalDate.of(2026, 6, 30);

    private GetWarehouseReportQuery query(WarehouseReportCut cut) {
        return new GetWarehouseReportQuery(cut, FROM, TO);
    }

    @Test
    void getReport_dateFromAfterDateTo_throwsCom001BeforeHittingRepository() {
        GetWarehouseReportQuery bad = new GetWarehouseReportQuery(
            WarehouseReportCut.BY_PRODUCT, LocalDate.of(2026, 6, 30), LocalDate.of(2026, 6, 1));

        ApiException ex = assertThrows(ApiException.class, () -> warehouseReportService.getReport(bad));

        assertEquals("COM-001", ex.code());
        assertEquals(400, ex.status());
        verifyNoInteractions(warehouseReportRepository);
    }

    @Test
    void getReport_byUnit_delegatesToUnitQuery() {
        when(warehouseReportRepository.findByUnit(any(), any())).thenReturn(List.of());
        warehouseReportService.getReport(query(WarehouseReportCut.BY_UNIT));
        verify(warehouseReportRepository).findByUnit(any(), any());
        verify(warehouseReportRepository, never()).findByProduct(any(), any());
    }

    @Test
    void getReport_byPeriod_delegatesToPeriodQuery() {
        when(warehouseReportRepository.findByPeriod(any(), any())).thenReturn(List.of());
        warehouseReportService.getReport(query(WarehouseReportCut.BY_PERIOD));
        verify(warehouseReportRepository).findByPeriod(any(), any());
    }

    @Test
    void getReport_byProduct_delegatesToProductQuery() {
        when(warehouseReportRepository.findByProduct(any(), any())).thenReturn(List.of());
        warehouseReportService.getReport(query(WarehouseReportCut.BY_PRODUCT));
        verify(warehouseReportRepository).findByProduct(any(), any());
    }

    @Test
    void getReport_bySupplier_delegatesToSupplierQueryWithLocalDates() {
        when(warehouseReportRepository.findBySupplier(FROM, TO)).thenReturn(List.of());
        warehouseReportService.getReport(query(WarehouseReportCut.BY_SUPPLIER));
        verify(warehouseReportRepository).findBySupplier(FROM, TO);
    }

    @Test
    void getReport_totalsAreSumOfRowFields() {
        when(warehouseReportRepository.findByProduct(any(), any())).thenReturn(List.of(
            new ReportRowView("A", "UND", new BigDecimal("3"), new BigDecimal("10.00"), BigDecimal.ZERO),
            new ReportRowView("B", "UND", new BigDecimal("2"), BigDecimal.ZERO, new BigDecimal("5.50"))
        ));

        WarehouseReportResponse response = warehouseReportService.getReport(query(WarehouseReportCut.BY_PRODUCT));

        assertEquals(2, response.rows().size());
        assertEquals(0, new BigDecimal("10.00").compareTo(response.totalPEN()));
        assertEquals(0, new BigDecimal("5.50").compareTo(response.totalUSD()));
        assertEquals(0, new BigDecimal("5").compareTo(response.totalCount()));
        assertEquals(WarehouseReportCut.BY_PRODUCT, response.cut());
        assertEquals(FROM, response.dateFrom());
        assertEquals(TO, response.dateTo());
    }

    @Test
    void getReport_emptyResult_returnsEmptyRowsAndZeroTotals() {
        when(warehouseReportRepository.findBySupplier(any(), any())).thenReturn(List.of());

        WarehouseReportResponse response = warehouseReportService.getReport(query(WarehouseReportCut.BY_SUPPLIER));

        assertTrue(response.rows().isEmpty());
        assertEquals(0, BigDecimal.ZERO.compareTo(response.totalPEN()));
        assertEquals(0, BigDecimal.ZERO.compareTo(response.totalUSD()));
        assertEquals(0, BigDecimal.ZERO.compareTo(response.totalCount()));
    }
}
