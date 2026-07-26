package com.scaramutti.tms.shared.repository;

import com.scaramutti.tms.shared.entity.ProductCategory;
import com.scaramutti.tms.shared.entity.ProductCategory_;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

@ApplicationScoped
public class ProductCategoryRepository implements PanacheRepositoryBase<ProductCategory, Integer> {

    public List<ProductCategory> listAllOrderedByName() {
        return listAll(Sort.by(ProductCategory_.NAME).ascending());
    }

    public List<ProductCategory> listByIsActiveOrderedByName(boolean isActive) {
        return list(
            ProductCategory_.IS_ACTIVE + " = ?1",
            Sort.by(ProductCategory_.NAME).ascending(),
            isActive
        );
    }

    /**
     * Case-insensitive: respaldado en BD por el indice funcional
     * uq_product_categories_name_ci (V003) — este chequeo cubre el happy path,
     * la race condition la traduce el catch de persistOrTranslateDuplicate
     * en el service.
     */
    public boolean existsByNameIgnoreCase(String name) {
        return count("lower(" + ProductCategory_.NAME + ") = lower(?1)", name) > 0;
    }
}
