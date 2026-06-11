package com.scaramutti.tms.catalogs.paymentterm.api;

import com.scaramutti.tms.catalogs.paymentterm.dto.PaymentTermResponse;
import com.scaramutti.tms.catalogs.paymentterm.mapper.PaymentTermResourceMapper;
import com.scaramutti.tms.catalogs.paymentterm.service.PaymentTermService;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import java.util.List;

@Path("/payment-terms")
@Produces(MediaType.APPLICATION_JSON)
public class PaymentTermResource {

    @Inject PaymentTermService paymentTermService;
    @Inject PaymentTermResourceMapper paymentTermResourceMapper;

    @GET
    public List<PaymentTermResponse> listPaymentTerms(@QueryParam("isActive") Boolean isActive) {
        return paymentTermService.listPaymentTerms(
            paymentTermResourceMapper.toListPaymentTermsQuery(isActive)
        );
    }
}
