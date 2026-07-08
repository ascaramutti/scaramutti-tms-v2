package com.scaramutti.tms.warehouse.productcategory.mapper;

import com.scaramutti.tms.warehouse.productcategory.service.cmd.ListWarehouseProductCategoriesQuery;
import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;
import org.mapstruct.NullValueMappingStrategy;

@Mapper(componentModel = MappingConstants.ComponentModel.CDI,
        nullValueMappingStrategy = NullValueMappingStrategy.RETURN_DEFAULT)
public interface WarehouseProductCategoryResourceMapper {

    ListWarehouseProductCategoriesQuery toListWarehouseProductCategoriesQuery(Boolean isActive);
}
