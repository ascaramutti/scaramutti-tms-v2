package com.scaramutti.tms.warehouse.product.mapper;

import com.scaramutti.tms.shared.entity.Product;
import com.scaramutti.tms.warehouse.product.service.cmd.CreateWarehouseProductCommand;
import com.scaramutti.tms.warehouse.product.service.cmd.UpdateWarehouseProductCommand;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.MappingTarget;

/**
 * Mapper de la capa Service: arma la entity a partir del command + el SKU ya
 * generado por el backend + el id del usuario autenticado.
 * Setea isActive=true explícitamente (el POST siempre nace activo); NO setea
 * id/createdAt/updatedAt (los maneja la BD / @PrePersist de la entity).
 * La construcción del Response se hace en el service (necesita lookups de
 * categoría/unidad/usuario y los derivados stock/lowStock).
 */
@Mapper(componentModel = MappingConstants.ComponentModel.CDI)
public interface WarehouseProductServiceMapper {

    @Mapping(target = "id",        ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "isActive",  constant = "true")
    @Mapping(target = "code",      source = "generatedCode")
    @Mapping(target = "createdBy", source = "userId")
    Product toProductEntity(
        CreateWarehouseProductCommand createWarehouseProductCommand,
        String generatedCode,
        Integer userId
    );

    /**
     * Aplica el PUT sobre la entity gestionada. Campos inmutables ignorados:
     * id/code/unitOfMeasureId/createdBy/createdAt (nunca viajan en el command de
     * update) y updatedAt (lo regenera {@code @PreUpdate} de la entity, no el mapper).
     */
    @Mapping(target = "id",               ignore = true)
    @Mapping(target = "code",             ignore = true)
    @Mapping(target = "unitOfMeasureId",  ignore = true)
    @Mapping(target = "createdBy",        ignore = true)
    @Mapping(target = "createdAt",        ignore = true)
    @Mapping(target = "updatedAt",        ignore = true)
    void applyUpdate(@MappingTarget Product product, UpdateWarehouseProductCommand command);
}
