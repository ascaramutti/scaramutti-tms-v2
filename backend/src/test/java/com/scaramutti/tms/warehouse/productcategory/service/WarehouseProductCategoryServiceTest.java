package com.scaramutti.tms.warehouse.productcategory.service;

import com.scaramutti.tms.shared.entity.ProductCategory;
import com.scaramutti.tms.shared.repository.ProductCategoryRepository;
import com.scaramutti.tms.warehouse.productcategory.dto.WarehouseProductCategoryResponse;
import com.scaramutti.tms.warehouse.productcategory.mapper.WarehouseProductCategoryServiceMapper;
import com.scaramutti.tms.warehouse.productcategory.service.cmd.ListWarehouseProductCategoriesQuery;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests del branching del service. Calco de CurrencyServiceTest. Los
 * integration tests (WarehouseProductCategoriesResourceTest) cubren el
 * end-to-end con BD real.
 */
@ExtendWith(MockitoExtension.class)
class WarehouseProductCategoryServiceTest {

    @Mock ProductCategoryRepository productCategoryRepository;
    @Mock WarehouseProductCategoryServiceMapper warehouseProductCategoryServiceMapper;
    @InjectMocks WarehouseProductCategoryService warehouseProductCategoryService;

    @Test
    void listProductCategories_withNullFilter_callsListAllOrderedByName() {
        ListWarehouseProductCategoriesQuery query = new ListWarehouseProductCategoriesQuery(null);
        when(productCategoryRepository.listAllOrderedByName()).thenReturn(List.of());
        when(warehouseProductCategoryServiceMapper.toWarehouseProductCategoryResponseList(any())).thenReturn(List.of());

        warehouseProductCategoryService.listProductCategories(query);

        verify(productCategoryRepository).listAllOrderedByName();
        verify(productCategoryRepository, never()).listByIsActiveOrderedByName(anyBoolean());
    }

    @Test
    void listProductCategories_withTrueFilter_callsListByIsActiveOrderedByNameWithTrue() {
        ListWarehouseProductCategoriesQuery query = new ListWarehouseProductCategoriesQuery(true);
        when(productCategoryRepository.listByIsActiveOrderedByName(true)).thenReturn(List.of());
        when(warehouseProductCategoryServiceMapper.toWarehouseProductCategoryResponseList(any())).thenReturn(List.of());

        warehouseProductCategoryService.listProductCategories(query);

        verify(productCategoryRepository).listByIsActiveOrderedByName(true);
        verify(productCategoryRepository, never()).listAllOrderedByName();
    }

    @Test
    void listProductCategories_withFalseFilter_callsListByIsActiveOrderedByNameWithFalse() {
        ListWarehouseProductCategoriesQuery query = new ListWarehouseProductCategoriesQuery(false);
        when(productCategoryRepository.listByIsActiveOrderedByName(false)).thenReturn(List.of());
        when(warehouseProductCategoryServiceMapper.toWarehouseProductCategoryResponseList(any())).thenReturn(List.of());

        warehouseProductCategoryService.listProductCategories(query);

        verify(productCategoryRepository).listByIsActiveOrderedByName(false);
        verify(productCategoryRepository, never()).listAllOrderedByName();
    }

    @Test
    void listProductCategories_returnsResultFromMapper() {
        ListWarehouseProductCategoriesQuery query = new ListWarehouseProductCategoriesQuery(null);
        ProductCategory entity = new ProductCategory();
        entity.id = 1;
        entity.name = "Filtros";
        entity.isActive = true;
        List<ProductCategory> entitiesFromRepository = List.of(entity);

        WarehouseProductCategoryResponse mappedResponse = new WarehouseProductCategoryResponse(1, "Filtros", null, true);
        List<WarehouseProductCategoryResponse> mappedResponses = List.of(mappedResponse);

        when(productCategoryRepository.listAllOrderedByName()).thenReturn(entitiesFromRepository);
        when(warehouseProductCategoryServiceMapper.toWarehouseProductCategoryResponseList(entitiesFromRepository))
            .thenReturn(mappedResponses);

        List<WarehouseProductCategoryResponse> result = warehouseProductCategoryService.listProductCategories(query);

        assertEquals(mappedResponses, result);
    }
}
