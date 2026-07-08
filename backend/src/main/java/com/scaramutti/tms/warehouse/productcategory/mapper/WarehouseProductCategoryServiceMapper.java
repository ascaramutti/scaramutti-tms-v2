package com.scaramutti.tms.warehouse.productcategory.mapper;

import com.scaramutti.tms.shared.entity.ProductCategory;
import com.scaramutti.tms.warehouse.productcategory.dto.WarehouseProductCategoryResponse;
import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;

import java.util.List;

@Mapper(componentModel = MappingConstants.ComponentModel.CDI)
public interface WarehouseProductCategoryServiceMapper {

    WarehouseProductCategoryResponse toWarehouseProductCategoryResponse(ProductCategory productCategory);

    List<WarehouseProductCategoryResponse> toWarehouseProductCategoryResponseList(List<ProductCategory> productCategories);
}
