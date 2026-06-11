package com.scaramutti.tms.shared.repository;

import com.scaramutti.tms.shared.entity.QuotationServiceType;
import com.scaramutti.tms.shared.entity.QuotationServiceType_;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

@ApplicationScoped
public class QuotationServiceTypeRepository implements PanacheRepositoryBase<QuotationServiceType, Integer> {

    public List<QuotationServiceType> listAllOrderedByCode() {
        return listAll(Sort.by(QuotationServiceType_.CODE).ascending());
    }

    public List<QuotationServiceType> listByIsActiveOrderedByCode(boolean isActive) {
        return list(
            QuotationServiceType_.IS_ACTIVE + " = ?1",
            Sort.by(QuotationServiceType_.CODE).ascending(),
            isActive
        );
    }
}
