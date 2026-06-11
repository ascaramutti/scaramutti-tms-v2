package com.scaramutti.tms.catalogs.quotationservicetype.service;

import com.scaramutti.tms.catalogs.quotationservicetype.dto.QuotationServiceTypeResponse;
import com.scaramutti.tms.catalogs.quotationservicetype.mapper.QuotationServiceTypeServiceMapper;
import com.scaramutti.tms.catalogs.quotationservicetype.service.cmd.ListQuotationServiceTypesQuery;
import com.scaramutti.tms.shared.entity.QuotationServiceType;
import com.scaramutti.tms.shared.repository.QuotationServiceTypeRepository;
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
 * (QuotationServiceTypesResourceTest) cubren el resultado end-to-end con BD real.
 */
@ExtendWith(MockitoExtension.class)
class QuotationServiceTypeServiceTest {

    @Mock QuotationServiceTypeRepository quotationServiceTypeRepository;
    @Mock QuotationServiceTypeServiceMapper quotationServiceTypeServiceMapper;
    @InjectMocks QuotationServiceTypeService quotationServiceTypeService;

    @Test
    void listQuotationServiceTypes_withNullFilter_callsListAllOrderedByCode() {
        ListQuotationServiceTypesQuery query = new ListQuotationServiceTypesQuery(null);
        when(quotationServiceTypeRepository.listAllOrderedByCode()).thenReturn(List.of());
        when(quotationServiceTypeServiceMapper.toQuotationServiceTypeResponseList(any())).thenReturn(List.of());

        quotationServiceTypeService.listQuotationServiceTypes(query);

        verify(quotationServiceTypeRepository).listAllOrderedByCode();
        verify(quotationServiceTypeRepository, never()).listByIsActiveOrderedByCode(anyBoolean());
    }

    @Test
    void listQuotationServiceTypes_withTrueFilter_callsListByIsActiveOrderedByCodeWithTrue() {
        ListQuotationServiceTypesQuery query = new ListQuotationServiceTypesQuery(true);
        when(quotationServiceTypeRepository.listByIsActiveOrderedByCode(true)).thenReturn(List.of());
        when(quotationServiceTypeServiceMapper.toQuotationServiceTypeResponseList(any())).thenReturn(List.of());

        quotationServiceTypeService.listQuotationServiceTypes(query);

        verify(quotationServiceTypeRepository).listByIsActiveOrderedByCode(true);
        verify(quotationServiceTypeRepository, never()).listAllOrderedByCode();
    }

    @Test
    void listQuotationServiceTypes_withFalseFilter_callsListByIsActiveOrderedByCodeWithFalse() {
        ListQuotationServiceTypesQuery query = new ListQuotationServiceTypesQuery(false);
        when(quotationServiceTypeRepository.listByIsActiveOrderedByCode(false)).thenReturn(List.of());
        when(quotationServiceTypeServiceMapper.toQuotationServiceTypeResponseList(any())).thenReturn(List.of());

        quotationServiceTypeService.listQuotationServiceTypes(query);

        verify(quotationServiceTypeRepository).listByIsActiveOrderedByCode(false);
        verify(quotationServiceTypeRepository, never()).listAllOrderedByCode();
    }

    @Test
    void listQuotationServiceTypes_returnsResultFromMapper() {
        // Verifica que el resultado del mapper SE devuelve. Defensa anti-refactor.
        ListQuotationServiceTypesQuery query = new ListQuotationServiceTypesQuery(null);
        QuotationServiceType scb = new QuotationServiceType();
        scb.id = 1;
        scb.code = "SCB";
        scb.name = "Servicio de transporte en Cama Baja";
        scb.description = "Transporte en cama baja";
        scb.isActive = true;
        List<QuotationServiceType> entitiesFromRepository = List.of(scb);

        QuotationServiceTypeResponse scbResponse = new QuotationServiceTypeResponse(
            1, "SCB", "Servicio de transporte en Cama Baja", "SERVICIO",
            "Transporte en cama baja", true
        );
        List<QuotationServiceTypeResponse> mappedResponses = List.of(scbResponse);

        when(quotationServiceTypeRepository.listAllOrderedByCode()).thenReturn(entitiesFromRepository);
        when(quotationServiceTypeServiceMapper.toQuotationServiceTypeResponseList(entitiesFromRepository)).thenReturn(mappedResponses);

        List<QuotationServiceTypeResponse> result = quotationServiceTypeService.listQuotationServiceTypes(query);

        assertEquals(mappedResponses, result);
    }
}
