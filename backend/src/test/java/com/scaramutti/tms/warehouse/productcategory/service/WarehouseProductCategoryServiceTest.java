package com.scaramutti.tms.warehouse.productcategory.service;

import com.scaramutti.tms.shared.entity.ProductCategory;
import com.scaramutti.tms.shared.exception.ApiException;
import com.scaramutti.tms.shared.repository.ProductCategoryRepository;
import com.scaramutti.tms.warehouse.productcategory.dto.WarehouseProductCategoryResponse;
import com.scaramutti.tms.warehouse.productcategory.mapper.WarehouseProductCategoryServiceMapper;
import com.scaramutti.tms.warehouse.productcategory.service.cmd.CreateWarehouseProductCategoryCommand;
import com.scaramutti.tms.warehouse.productcategory.service.cmd.ListWarehouseProductCategoriesQuery;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
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

    // ---------- createProductCategory — validacion + duplicados + race condition ------

    private CreateWarehouseProductCategoryCommand sampleCommand() {
        return new CreateWarehouseProductCategoryCommand("ZTEST_Empaques", "Cajas y embalajes");
    }

    @Test
    void createProductCategory_withValidCommand_persistsEntityAndReturnsResponse() {
        CreateWarehouseProductCategoryCommand command = sampleCommand();
        ProductCategory mappedEntity = new ProductCategory();
        mappedEntity.name = command.name();
        WarehouseProductCategoryResponse expectedResponse =
            new WarehouseProductCategoryResponse(1, command.name(), command.description(), true);

        when(productCategoryRepository.existsByNameIgnoreCase(command.name())).thenReturn(false);
        when(warehouseProductCategoryServiceMapper.toProductCategoryEntity(command)).thenReturn(mappedEntity);
        when(warehouseProductCategoryServiceMapper.toWarehouseProductCategoryResponse(mappedEntity)).thenReturn(expectedResponse);

        WarehouseProductCategoryResponse actual = warehouseProductCategoryService.createProductCategory(command);

        assertSame(expectedResponse, actual);
        verify(productCategoryRepository).persist(mappedEntity);
        verify(productCategoryRepository).flush();
    }

    @Test
    void createProductCategory_whenNameDuplicate_throwsApiExceptionWH010_andDoesNotPersist() {
        CreateWarehouseProductCategoryCommand command = sampleCommand();
        when(productCategoryRepository.existsByNameIgnoreCase(command.name())).thenReturn(true);

        ApiException ex = assertThrows(ApiException.class,
            () -> warehouseProductCategoryService.createProductCategory(command));
        assertEquals("WH-010", ex.code());
        assertEquals(409, ex.status());

        verify(productCategoryRepository, never()).persist(any(ProductCategory.class));
    }

    @Test
    void createProductCategory_withNullName_throwsValidationFailedCOM001() {
        CreateWarehouseProductCategoryCommand command = new CreateWarehouseProductCategoryCommand(null, null);

        ApiException ex = assertThrows(ApiException.class,
            () -> warehouseProductCategoryService.createProductCategory(command));
        assertEquals("COM-001", ex.code());
        assertEquals(400, ex.status());

        verify(productCategoryRepository, never()).persist(any(ProductCategory.class));
    }

    @Test
    void createProductCategory_withEmptyName_throwsValidationFailedCOM001() {
        CreateWarehouseProductCategoryCommand command = new CreateWarehouseProductCategoryCommand("", null);

        ApiException ex = assertThrows(ApiException.class,
            () -> warehouseProductCategoryService.createProductCategory(command));
        assertEquals("COM-001", ex.code());

        verify(productCategoryRepository, never()).persist(any(ProductCategory.class));
    }

    @Test
    void createProductCategory_whenFlushThrowsConstraintViolationOnName_translatesToWH010() {
        // Race condition: validateNoDuplicates pasa pero el flush concurrente
        // viola el indice unico (V002 plano o V003 funcional, ambos contienen "name").
        CreateWarehouseProductCategoryCommand command = sampleCommand();
        ProductCategory mappedEntity = new ProductCategory();

        when(productCategoryRepository.existsByNameIgnoreCase(command.name())).thenReturn(false);
        when(warehouseProductCategoryServiceMapper.toProductCategoryEntity(command)).thenReturn(mappedEntity);

        org.hibernate.exception.ConstraintViolationException hibernateCve =
            new org.hibernate.exception.ConstraintViolationException(
                "Postgres UNIQUE violation",
                new java.sql.SQLException("violates unique constraint \"uq_product_categories_name_ci\""),
                "uq_product_categories_name_ci"
            );
        jakarta.persistence.PersistenceException jpaWrapper =
            new jakarta.persistence.PersistenceException(hibernateCve);
        doThrow(jpaWrapper).when(productCategoryRepository).flush();

        ApiException ex = assertThrows(ApiException.class,
            () -> warehouseProductCategoryService.createProductCategory(command));
        assertEquals("WH-010", ex.code());
    }

    @Test
    void createProductCategory_invokesMapperToEntityAndPersist() {
        CreateWarehouseProductCategoryCommand command = sampleCommand();
        ProductCategory mappedEntity = new ProductCategory();
        WarehouseProductCategoryResponse mappedResponse =
            new WarehouseProductCategoryResponse(1, command.name(), null, true);

        when(productCategoryRepository.existsByNameIgnoreCase(command.name())).thenReturn(false);
        when(warehouseProductCategoryServiceMapper.toProductCategoryEntity(command)).thenReturn(mappedEntity);
        when(warehouseProductCategoryServiceMapper.toWarehouseProductCategoryResponse(mappedEntity)).thenReturn(mappedResponse);

        warehouseProductCategoryService.createProductCategory(command);

        verify(warehouseProductCategoryServiceMapper).toProductCategoryEntity(command);
        verify(productCategoryRepository).persist(mappedEntity);
        verify(warehouseProductCategoryServiceMapper).toWarehouseProductCategoryResponse(mappedEntity);
    }

    @Test
    void createProductCategory_existsByNameIgnoreCase_calledBeforePersist() {
        CreateWarehouseProductCategoryCommand command = sampleCommand();
        ProductCategory mappedEntity = new ProductCategory();

        when(productCategoryRepository.existsByNameIgnoreCase(command.name())).thenReturn(false);
        when(warehouseProductCategoryServiceMapper.toProductCategoryEntity(command)).thenReturn(mappedEntity);

        warehouseProductCategoryService.createProductCategory(command);

        InOrder order = inOrder(productCategoryRepository);
        order.verify(productCategoryRepository).existsByNameIgnoreCase(command.name());
        order.verify(productCategoryRepository).persist(any(ProductCategory.class));
        order.verify(productCategoryRepository).flush();
    }
}
