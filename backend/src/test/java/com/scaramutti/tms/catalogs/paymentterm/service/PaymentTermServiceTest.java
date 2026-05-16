package com.scaramutti.tms.catalogs.paymentterm.service;

import com.scaramutti.tms.catalogs.paymentterm.dto.PaymentTermResponse;
import com.scaramutti.tms.catalogs.paymentterm.mapper.PaymentTermServiceMapper;
import com.scaramutti.tms.catalogs.paymentterm.service.cmd.ListPaymentTermsQuery;
import com.scaramutti.tms.shared.entity.PaymentTerm;
import com.scaramutti.tms.shared.repository.PaymentTermRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests del branching del service. Aisla la decision "isActive null vs
 * not-null" del IO de BD usando mocks. Los integration tests
 * (PaymentTermsResourceTest) cubren el resultado end-to-end con BD real.
 */
@ExtendWith(MockitoExtension.class)
class PaymentTermServiceTest {

    @Mock PaymentTermRepository paymentTermRepository;
    @Mock PaymentTermServiceMapper paymentTermServiceMapper;
    @InjectMocks PaymentTermService paymentTermService;

    @Test
    void listPaymentTerms_withNullFilter_callsListAllOrderedByName() {
        ListPaymentTermsQuery query = new ListPaymentTermsQuery(null);
        when(paymentTermRepository.listAllOrderedByName()).thenReturn(List.of());
        when(paymentTermServiceMapper.toPaymentTermResponseList(any())).thenReturn(List.of());

        paymentTermService.listPaymentTerms(query);

        verify(paymentTermRepository).listAllOrderedByName();
        verify(paymentTermRepository, never()).listByIsActiveOrderedByName(anyBoolean());
    }

    @Test
    void listPaymentTerms_withTrueFilter_callsListByIsActiveOrderedByNameWithTrue() {
        ListPaymentTermsQuery query = new ListPaymentTermsQuery(true);
        when(paymentTermRepository.listByIsActiveOrderedByName(true)).thenReturn(List.of());
        when(paymentTermServiceMapper.toPaymentTermResponseList(any())).thenReturn(List.of());

        paymentTermService.listPaymentTerms(query);

        verify(paymentTermRepository).listByIsActiveOrderedByName(true);
        verify(paymentTermRepository, never()).listAllOrderedByName();
    }

    @Test
    void listPaymentTerms_withFalseFilter_callsListByIsActiveOrderedByNameWithFalse() {
        ListPaymentTermsQuery query = new ListPaymentTermsQuery(false);
        when(paymentTermRepository.listByIsActiveOrderedByName(false)).thenReturn(List.of());
        when(paymentTermServiceMapper.toPaymentTermResponseList(any())).thenReturn(List.of());

        paymentTermService.listPaymentTerms(query);

        verify(paymentTermRepository).listByIsActiveOrderedByName(false);
        verify(paymentTermRepository, never()).listAllOrderedByName();
    }

    @Test
    void listPaymentTerms_returnsResultFromMapper() {
        // Verifica que el resultado del mapper SE devuelve (no se ignora ni
        // se re-procesa). Defensa ante futuros refactors que rompan la cadena
        // repo → mapper → return.
        ListPaymentTermsQuery query = new ListPaymentTermsQuery(null);
        PaymentTerm contado = new PaymentTerm();
        contado.id = 1;
        contado.name = "Contado";
        contado.days = 0;
        contado.isActive = true;
        List<PaymentTerm> entitiesFromRepository = List.of(contado);

        PaymentTermResponse contadoResponse = new PaymentTermResponse(1, "Contado", 0, true);
        List<PaymentTermResponse> mappedResponses = List.of(contadoResponse);

        when(paymentTermRepository.listAllOrderedByName()).thenReturn(entitiesFromRepository);
        when(paymentTermServiceMapper.toPaymentTermResponseList(entitiesFromRepository)).thenReturn(mappedResponses);

        List<PaymentTermResponse> result = paymentTermService.listPaymentTerms(query);

        assertEquals(mappedResponses, result);
    }
}
