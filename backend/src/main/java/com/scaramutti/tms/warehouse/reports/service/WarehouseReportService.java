package com.scaramutti.tms.warehouse.reports.service;

import com.scaramutti.tms.shared.exception.CommonError;
import com.scaramutti.tms.shared.repository.WarehouseReportRepository;
import com.scaramutti.tms.shared.repository.WarehouseReportRepository.ReportRowView;
import com.scaramutti.tms.shared.util.DateUtils;
import com.scaramutti.tms.warehouse.reports.dto.WarehouseReportResponse;
import com.scaramutti.tms.warehouse.reports.dto.WarehouseReportRowResponse;
import com.scaramutti.tms.warehouse.reports.service.cmd.GetWarehouseReportQuery;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.function.Function;

/**
 * Reporte agregado de almacen (GET /warehouse/reports). Read-only, sin
 * {@code @Transactional} (misma convencion que {@code WarehouseKardexService}).
 * Valida el rango, calcula los bordes de retiros en America/Lima (semiabierto,
 * igual que el kardex), despacha el corte a la query correspondiente y arma los
 * totales por moneda (RN-WH7, sin conversion). Los agregados no se persisten.
 */
@ApplicationScoped
public class WarehouseReportService {

    @Inject WarehouseReportRepository warehouseReportRepository;

    public WarehouseReportResponse getReport(GetWarehouseReportQuery query) {
        if (query.dateFrom().isAfter(query.dateTo())) {
            throw CommonError.VALIDATION_FAILED.toException("dateFrom no puede ser posterior a dateTo");
        }

        List<ReportRowView> views = switch (query.cut()) {
            case BY_UNIT -> warehouseReportRepository.findByUnit(fromInclusive(query), toExclusive(query));
            case BY_PERIOD -> warehouseReportRepository.findByPeriod(fromInclusive(query), toExclusive(query));
            case BY_PRODUCT -> warehouseReportRepository.findByProduct(fromInclusive(query), toExclusive(query));
            case BY_SUPPLIER -> warehouseReportRepository.findBySupplier(query.dateFrom(), query.dateTo());
        };

        List<WarehouseReportRowResponse> rows = views.stream().map(this::toRowResponse).toList();
        return new WarehouseReportResponse(
            query.cut(), query.dateFrom(), query.dateTo(), rows,
            sum(rows, WarehouseReportRowResponse::amountPEN),
            sum(rows, WarehouseReportRowResponse::amountUSD),
            sum(rows, WarehouseReportRowResponse::count)
        );
    }

    /** Inicio del rango (inclusive) en America/Lima, sobre withdrawn_at (timestamptz). */
    private OffsetDateTime fromInclusive(GetWarehouseReportQuery query) {
        return DateUtils.limaDayStart(query.dateFrom());
    }

    /** Fin del rango como inicio del dia siguiente (semiabierto): dateTo inclusive del dia completo. */
    private OffsetDateTime toExclusive(GetWarehouseReportQuery query) {
        return DateUtils.limaNextDayStart(query.dateTo());
    }

    private WarehouseReportRowResponse toRowResponse(ReportRowView view) {
        return new WarehouseReportRowResponse(
            view.label(), view.detail(), view.count(), view.amountPEN(), view.amountUSD());
    }

    private BigDecimal sum(List<WarehouseReportRowResponse> rows,
                           Function<WarehouseReportRowResponse, BigDecimal> field) {
        return rows.stream().map(field).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
