package com.scaramutti.tms.shared.repository;

import com.scaramutti.tms.shared.entity.UnitOfMeasure;
import com.scaramutti.tms.shared.entity.UnitOfMeasure_;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

@ApplicationScoped
public class UnitOfMeasureRepository implements PanacheRepositoryBase<UnitOfMeasure, Integer> {

    public List<UnitOfMeasure> listAllOrderedByCode() {
        return listAll(Sort.by(UnitOfMeasure_.CODE).ascending());
    }

    public List<UnitOfMeasure> listByIsActiveOrderedByCode(boolean isActive) {
        return list(
            UnitOfMeasure_.IS_ACTIVE + " = ?1",
            Sort.by(UnitOfMeasure_.CODE).ascending(),
            isActive
        );
    }
}
