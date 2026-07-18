package com.scaramutti.tms.warehouse.kardex.mapper;

import com.scaramutti.tms.shared.mapper.SharedMapperConfig;
import com.scaramutti.tms.warehouse.kardex.service.cmd.GetWarehouseKardexQuery;
import org.mapstruct.Mapper;

import java.time.LocalDate;

/**
 * Mapper de la capa REST del kardex. Sin normalizacion propia (los params ya
 * llegan tipados/validados por JAX-RS): solo agrupa la firma en el Query.
 */
@Mapper(config = SharedMapperConfig.class)
public interface WarehouseKardexResourceMapper {

    GetWarehouseKardexQuery toGetWarehouseKardexQuery(
        Integer productId, LocalDate dateFrom, LocalDate dateTo, int page, int size
    );
}
