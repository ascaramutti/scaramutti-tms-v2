package com.scaramutti.tms.warehouse.stats.mapper;

import com.scaramutti.tms.shared.mapper.SharedMapperConfig;
import com.scaramutti.tms.shared.repository.WarehouseStatsRepository.WarehouseStatsRow;
import com.scaramutti.tms.warehouse.stats.dto.WarehouseStatsResponse;
import org.mapstruct.Mapper;

/**
 * Mapper de la capa Service: fila agregada de la query de stats al response
 * (shaping campo a campo; los KPIs ya vienen contados por el repositorio).
 */
@Mapper(config = SharedMapperConfig.class)
public interface WarehouseStatsServiceMapper {

    WarehouseStatsResponse toWarehouseStatsResponse(WarehouseStatsRow row);
}
