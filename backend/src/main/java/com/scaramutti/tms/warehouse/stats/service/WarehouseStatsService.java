package com.scaramutti.tms.warehouse.stats.service;

import com.scaramutti.tms.shared.repository.WarehouseStatsRepository;
import com.scaramutti.tms.shared.repository.WarehouseStatsRepository.WarehouseStatsRow;
import com.scaramutti.tms.shared.util.DateUtils;
import com.scaramutti.tms.warehouse.stats.dto.WarehouseStatsResponse;
import com.scaramutti.tms.warehouse.stats.mapper.WarehouseStatsServiceMapper;
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
    @Inject WarehouseStatsServiceMapper warehouseStatsServiceMapper;

    public WarehouseStatsResponse getStats() {
        LocalDate firstOfMonth = LocalDate.now(DateUtils.LIMA).withDayOfMonth(1);
        OffsetDateTime monthStart = DateUtils.limaDayStart(firstOfMonth);
        OffsetDateTime monthEndExclusive = DateUtils.limaDayStart(firstOfMonth.plusMonths(1));

        WarehouseStatsRow row = warehouseStatsRepository.getStats(monthStart, monthEndExclusive);
        return warehouseStatsServiceMapper.toWarehouseStatsResponse(row);
    }
}
