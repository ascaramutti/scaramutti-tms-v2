package com.scaramutti.tms.warehouse.product.mapper;

import com.scaramutti.tms.shared.entity.Product;
import com.scaramutti.tms.shared.entity.UnitOfMeasure;
import com.scaramutti.tms.warehouse.product.dto.WarehouseProductSummary;
import org.mapstruct.Mapping;

/**
 * Mixin de mapeo (NO es un {@code @Mapper}): declara UNA vez el shaping del
 * {@link WarehouseProductSummary} embebido y lo heredan los ServiceMappers que
 * lo embeben (aperturas, retiros). Cada mapper que lo extiende genera su propia
 * implementación; así el shaping tiene una sola declaración sin acoplar los
 * mappers de features entre sí.
 *
 * <p>Targets explícitos: id/code/name existen también en UnitOfMeasure y
 * MapStruct no puede desambiguar solo.
 */
public interface WarehouseProductSummaryMapping {

    @Mapping(target = "id",       source = "product.id")
    @Mapping(target = "code",     source = "product.code")
    @Mapping(target = "name",     source = "product.name")
    @Mapping(target = "unitCode", source = "unitOfMeasure.code")
    WarehouseProductSummary toWarehouseProductSummary(Product product, UnitOfMeasure unitOfMeasure);
}
