package com.scaramutti.tms.catalogs.paymentterm.service;

import com.scaramutti.tms.catalogs.CatalogsError;
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

    /**
     * Devuelve el término de pago con el id dado, o tira CAT-004 (404) si no existe.
     * NO filtra por isActive — el caller decide.
     */
    public PaymentTermResponse findById(Integer id) {
        PaymentTerm paymentTerm = paymentTermRepository.findById(id);
        if (paymentTerm == null) {
            throw CatalogsError.PAYMENT_TERM_NOT_FOUND.toException();
        }
        return paymentTermServiceMapper.toPaymentTermResponse(paymentTerm);
    }

    public List<PaymentTermResponse> listPaymentTerms(ListPaymentTermsQuery listPaymentTermsQuery) {
        Boolean isActiveFilter = listPaymentTermsQuery.isActive();
        List<PaymentTerm> paymentTerms = (isActiveFilter == null)
            ? paymentTermRepository.listAllOrderedByName()
            : paymentTermRepository.listByIsActiveOrderedByName(isActiveFilter);

        return paymentTermServiceMapper.toPaymentTermResponseList(paymentTerms);
    }
}
