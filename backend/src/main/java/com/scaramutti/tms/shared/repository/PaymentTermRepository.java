package com.scaramutti.tms.shared.repository;

import com.scaramutti.tms.shared.entity.PaymentTerm;
import com.scaramutti.tms.shared.entity.PaymentTerm_;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

@ApplicationScoped
public class PaymentTermRepository implements PanacheRepositoryBase<PaymentTerm, Integer> {

    public List<PaymentTerm> listAllOrderedByName() {
        return listAll(Sort.by(PaymentTerm_.NAME).ascending());
    }

    public List<PaymentTerm> listByIsActiveOrderedByName(boolean isActive) {
        return list(
            PaymentTerm_.IS_ACTIVE + " = ?1",
            Sort.by(PaymentTerm_.NAME).ascending(),
            isActive
        );
    }
}
