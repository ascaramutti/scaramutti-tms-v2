package com.scaramutti.tms.warehouse.stats.service;

import com.scaramutti.tms.shared.repository.WarehouseStatsRepository;
import com.scaramutti.tms.shared.repository.WarehouseStatsRepository.WarehouseStatsRow;
import com.scaramutti.tms.shared.util.DateUtils;
import com.scaramutti.tms.warehouse.stats.dto.WarehouseStatsResponse;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * KPIs del strip de Existencias (GET /warehouse/stats). Read-only, sin
 * {@code @Transactional} (misma convencion que {@code WarehouseKardexService}).
 * Calcula los bordes del mes calendario en curso (America/Lima) y delega el
 * conteo a {@link WarehouseStatsRepository}.
 */
@ApplicationScoped
public class WarehouseStatsService {

    @Inject WarehouseStatsRepository warehouseStatsRepository;

    public WarehouseStatsResponse getStats() {
        LocalDate firstOfMonth = LocalDate.now(DateUtils.LIMA).withDayOfMonth(1);
        OffsetDateTime monthStart = firstOfMonth.atStartOfDay(DateUtils.LIMA).toOffsetDateTime();
        OffsetDateTime monthEndExclusive = firstOfMonth.plusMonths(1).atStartOfDay(DateUtils.LIMA).toOffsetDateTime();

        WarehouseStatsRow row = warehouseStatsRepository.getStats(monthStart, monthEndExclusive);
        return new WarehouseStatsResponse(
            row.activeProducts(),
            row.lowStockCount(),
            row.entriesThisMonth(),
            row.withdrawalsThisMonth()
        );
    }
}
