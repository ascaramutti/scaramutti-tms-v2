package com.scaramutti.tms.warehouse.product.mapper;

import com.scaramutti.tms.shared.entity.Product;
import com.scaramutti.tms.warehouse.product.service.cmd.CreateWarehouseProductCommand;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;

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
}
