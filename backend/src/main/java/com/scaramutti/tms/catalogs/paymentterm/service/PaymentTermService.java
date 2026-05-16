package com.scaramutti.tms.catalogs.paymentterm.service;

import com.scaramutti.tms.catalogs.paymentterm.dto.PaymentTermResponse;
import com.scaramutti.tms.catalogs.paymentterm.mapper.PaymentTermServiceMapper;
import com.scaramutti.tms.catalogs.paymentterm.service.cmd.ListPaymentTermsQuery;
import com.scaramutti.tms.shared.entity.PaymentTerm;
import com.scaramutti.tms.shared.repository.PaymentTermRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;

@ApplicationScoped
public class PaymentTermService {

    @Inject PaymentTermRepository paymentTermRepository;
    @Inject PaymentTermServiceMapper paymentTermServiceMapper;

    public List<PaymentTermResponse> listPaymentTerms(ListPaymentTermsQuery listPaymentTermsQuery) {
        Boolean isActiveFilter = listPaymentTermsQuery.isActive();
        List<PaymentTerm> paymentTerms = (isActiveFilter == null)
            ? paymentTermRepository.listAllOrderedByName()
            : paymentTermRepository.listByIsActiveOrderedByName(isActiveFilter);

        return paymentTermServiceMapper.toPaymentTermResponseList(paymentTerms);
    }
}
