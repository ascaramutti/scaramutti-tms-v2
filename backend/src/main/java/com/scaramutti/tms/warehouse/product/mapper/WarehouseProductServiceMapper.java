package com.scaramutti.tms.warehouse.product.mapper;

import com.scaramutti.tms.auth.dto.UserResponse;
import com.scaramutti.tms.shared.entity.Product;
import com.scaramutti.tms.shared.entity.ProductCategory;
import com.scaramutti.tms.shared.entity.UnitOfMeasure;
import com.scaramutti.tms.shared.mapper.SharedMapperConfig;
import com.scaramutti.tms.shared.repository.ProductRepository.ProductStockView;
import com.scaramutti.tms.warehouse.product.dto.WarehouseProductResponse;
import com.scaramutti.tms.warehouse.product.dto.WarehouseProductStockResponse;
import com.scaramutti.tms.warehouse.product.service.cmd.CreateWarehouseProductCommand;
import com.scaramutti.tms.warehouse.product.service.cmd.UpdateWarehouseProductCommand;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

/**
 * Mapper de la capa Service: arma la entity a partir del command + el SKU ya
 * generado por el backend + el id del usuario autenticado.
 * Setea isActive=true explícitamente (el POST siempre nace activo); NO setea
 * id/createdAt/updatedAt (los maneja la BD / @PrePersist de la entity).
 * También el shaping a los responses: los lookups (categoría/unidad/usuario) y
 * los derivados stock/lowStock los resuelve el service y llegan resueltos.
 */
@Mapper(config = SharedMapperConfig.class)
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

    /**
     * Todos los targets van explícitos: varios nombres (id/code/name/isActive)
     * existen en más de una fuente y MapStruct no puede desambiguar solo.
     * Compartido por alta (stock inicial 0), detalle, listado y PUT: una sola
     * forma del DTO.
     */
    @Mapping(target = "id",            source = "product.id")
    @Mapping(target = "code",          source = "product.code")
    @Mapping(target = "name",          source = "product.name")
    @Mapping(target = "category",      source = "category")
    @Mapping(target = "unitOfMeasure", source = "unitOfMeasure")
    @Mapping(target = "brand",         source = "product.brand")
    @Mapping(target = "partNumber",    source = "product.partNumber")
    @Mapping(target = "attributes",    source = "product.attributes")
    @Mapping(target = "minStock",      source = "product.minStock")
    @Mapping(target = "observations",  source = "product.observations")
    @Mapping(target = "isActive",      source = "product.isActive")
    @Mapping(target = "stock",         source = "stock.stock")
    @Mapping(target = "lowStock",      source = "stock.lowStock")
    @Mapping(target = "createdBy",     source = "createdBy")
    @Mapping(target = "createdAt",     source = "product.createdAt")
    @Mapping(target = "updatedAt",     source = "product.updatedAt")
    WarehouseProductResponse toWarehouseProductResponse(
        Product product,
        ProductCategory category,
        UnitOfMeasure unitOfMeasure,
        ProductStockView stock,
        UserResponse createdBy
    );

    @Mapping(target = "productId", source = "product.id")
    @Mapping(target = "stock",     source = "stock.stock")
    @Mapping(target = "minStock",  source = "product.minStock")
    @Mapping(target = "lowStock",  source = "stock.lowStock")
    WarehouseProductStockResponse toWarehouseProductStockResponse(Product product, ProductStockView stock);
}
