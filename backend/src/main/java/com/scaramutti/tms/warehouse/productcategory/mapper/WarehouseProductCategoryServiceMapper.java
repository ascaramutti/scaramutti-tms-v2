package com.scaramutti.tms.warehouse.productcategory.mapper;

import com.scaramutti.tms.shared.entity.ProductCategory;
import com.scaramutti.tms.shared.mapper.SharedMapperConfig;
import com.scaramutti.tms.warehouse.productcategory.dto.WarehouseProductCategoryResponse;
import com.scaramutti.tms.warehouse.productcategory.service.cmd.CreateWarehouseProductCategoryCommand;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

/**
 * Mapper de la capa Service: traduce entidades del dominio a DTOs y viceversa.
 * toProductCategoryEntity setea isActive=true explicitamente y NO setea id
 * (lo asigna la BD).
 */
@Mapper(config = SharedMapperConfig.class)
public interface WarehouseProductCategoryServiceMapper {

    @Mapping(target = "id",       ignore = true)
    @Mapping(target = "isActive", constant = "true")
    ProductCategory toProductCategoryEntity(CreateWarehouseProductCategoryCommand createWarehouseProductCategoryCommand);

    WarehouseProductCategoryResponse toWarehouseProductCategoryResponse(ProductCategory productCategory);

    List<WarehouseProductCategoryResponse> toWarehouseProductCategoryResponseList(List<ProductCategory> productCategories);
}
