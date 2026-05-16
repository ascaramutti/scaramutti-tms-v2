package com.scaramutti.tms.shared.repository;

import com.scaramutti.tms.shared.entity.Currency;
import com.scaramutti.tms.shared.entity.Currency_;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

@ApplicationScoped
public class CurrencyRepository implements PanacheRepositoryBase<Currency, Integer> {

    public List<Currency> listAllOrderedByCode() {
        return listAll(Sort.by(Currency_.CODE).ascending());
    }

    public List<Currency> listByIsActiveOrderedByCode(boolean isActive) {
        return list(
            Currency_.IS_ACTIVE + " = ?1",
            Sort.by(Currency_.CODE).ascending(),
            isActive
        );
    }
}
