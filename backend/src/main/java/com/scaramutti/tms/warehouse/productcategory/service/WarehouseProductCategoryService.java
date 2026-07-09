package com.scaramutti.tms.warehouse.productcategory.service;

import com.scaramutti.tms.shared.entity.ProductCategory;
import com.scaramutti.tms.shared.exception.CommonError;
import com.scaramutti.tms.shared.repository.ProductCategoryRepository;
import com.scaramutti.tms.warehouse.WarehouseError;
import com.scaramutti.tms.warehouse.productcategory.dto.WarehouseProductCategoryResponse;
import com.scaramutti.tms.warehouse.productcategory.mapper.WarehouseProductCategoryServiceMapper;
import com.scaramutti.tms.warehouse.productcategory.service.cmd.CreateWarehouseProductCategoryCommand;
import com.scaramutti.tms.warehouse.productcategory.service.cmd.ListWarehouseProductCategoriesQuery;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.PersistenceException;
import jakarta.transaction.Transactional;
import org.hibernate.exception.ConstraintViolationException;
import org.jboss.logging.Logger;

import java.util.List;

/**
 * Patron identico a CargoTypeService/ClientService: chequeo previo de
 * duplicado (happy path) + catch de ConstraintViolationException en el
 * persist+flush para traducir la race condition a WH-010 (best-effort,
 * misma clase de deuda aceptada en el resto del proyecto).
 */
@ApplicationScoped
public class WarehouseProductCategoryService {

    private static final Logger LOG = Logger.getLogger(WarehouseProductCategoryService.class);

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

    @Transactional
    public WarehouseProductCategoryResponse createProductCategory(
            CreateWarehouseProductCategoryCommand createWarehouseProductCategoryCommand) {
        validatePostTrim(createWarehouseProductCategoryCommand.name());
        validateNoDuplicates(createWarehouseProductCategoryCommand);

        ProductCategory productCategory = warehouseProductCategoryServiceMapper
            .toProductCategoryEntity(createWarehouseProductCategoryCommand);
        persistOrTranslateDuplicate(productCategory);

        return warehouseProductCategoryServiceMapper.toWarehouseProductCategoryResponse(productCategory);
    }

    /**
     * Guarda invariante: el name no puede llegar vacio al service. Por el flow
     * REST normal, WarehouseProductCategoryResourceMapper.trimToNull ya
     * convierte "   " -> null antes de armar el Command; este branch solo
     * dispara para callers que bypasean el mapper (defense-in-depth).
     */
    private void validatePostTrim(String name) {
        if (name == null || name.isEmpty()) {
            throw CommonError.VALIDATION_FAILED.toException();
        }
    }

    private void validateNoDuplicates(CreateWarehouseProductCategoryCommand createWarehouseProductCategoryCommand) {
        if (productCategoryRepository.existsByNameIgnoreCase(createWarehouseProductCategoryCommand.name())) {
            throw WarehouseError.PRODUCT_CATEGORY_NAME_DUPLICATED.toException();
        }
    }

    /**
     * Cubre la race condition donde dos requests pasan validateNoDuplicates
     * simultaneamente. El indice funcional uq_product_categories_name_ci (V003)
     * garantiza que Postgres rechaza el segundo INSERT aunque el casing
     * difiera; el constraint autogenerado del UNIQUE plano (V002) tambien
     * contiene "name" y cae en la misma rama.
     */
    private void persistOrTranslateDuplicate(ProductCategory productCategory) {
        try {
            productCategoryRepository.persist(productCategory);
            productCategoryRepository.flush();
        } catch (PersistenceException ex) {
            ConstraintViolationException cve = extractConstraintViolation(ex);
            if (cve == null) {
                throw ex;
            }
            String constraintName = cve.getConstraintName();
            if (constraintName != null && constraintName.contains("name")) {
                LOG.warnf("Race condition: UNIQUE name violation [name=%s]", productCategory.name);
                throw WarehouseError.PRODUCT_CATEGORY_NAME_DUPLICATED.toException();
            }
            LOG.errorf(ex, "Unhandled DB constraint violation [constraint=%s]", constraintName);
            throw ex;
        }
    }

    private ConstraintViolationException extractConstraintViolation(PersistenceException ex) {
        if (ex instanceof ConstraintViolationException cve) {
            return cve;
        }
        Throwable cause = ex.getCause();
        return (cause instanceof ConstraintViolationException cve) ? cve : null;
    }
}
