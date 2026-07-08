package com.scaramutti.tms.warehouse.productcategory.service;

import com.scaramutti.tms.shared.entity.ProductCategory;
import com.scaramutti.tms.shared.repository.ProductCategoryRepository;
import com.scaramutti.tms.warehouse.productcategory.dto.WarehouseProductCategoryResponse;
import com.scaramutti.tms.warehouse.productcategory.mapper.WarehouseProductCategoryServiceMapper;
import com.scaramutti.tms.warehouse.productcategory.service.cmd.ListWarehouseProductCategoriesQuery;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;

@ApplicationScoped
public class WarehouseProductCategoryService {

    @Inject ProductCategoryRepository productCategoryRepository;
    @Inject WarehouseProductCategoryServiceMapper warehouseProductCategoryServiceMapper;

    public List<WarehouseProductCategoryResponse> listProductCategories(
            ListWarehouseProductCategoriesQuery listWarehouseProductCategoriesQuery) {
        Boolean isActiveFilter = listWarehouseProductCategoriesQuery.isActive();
        List<ProductCategory> productCategories = (isActiveFilter == null)
            ? productCategoryRepository.listAllOrderedByName()
            : productCategoryRepository.listByIsActiveOrderedByName(isActiveFilter);

        return warehouseProductCategoryServiceMapper.toWarehouseProductCategoryResponseList(productCategories);
    }
}
