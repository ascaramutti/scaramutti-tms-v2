package com.scaramutti.tms.catalogs.quotationservicetype.service;

import com.scaramutti.tms.catalogs.quotationservicetype.dto.QuotationServiceTypeResponse;
import com.scaramutti.tms.catalogs.quotationservicetype.mapper.QuotationServiceTypeServiceMapper;
import com.scaramutti.tms.catalogs.quotationservicetype.service.cmd.ListQuotationServiceTypesQuery;
import com.scaramutti.tms.shared.entity.QuotationServiceType;
import com.scaramutti.tms.shared.repository.QuotationServiceTypeRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

@ApplicationScoped
public class QuotationServiceTypeService {

    @Inject QuotationServiceTypeRepository quotationServiceTypeRepository;
    @Inject QuotationServiceTypeServiceMapper quotationServiceTypeServiceMapper;

    /**
     * Bulk fetch por ids. UNA sola query (WHERE id IN). NO valida que todos
     * los ids existan — el caller compara la lista devuelta vs los ids pedidos
     * y decide cómo manejar los faltantes (típicamente tirar NOT_FOUND con contexto).
     * Método interno, sin endpoint REST asociado.
     */
    public List<QuotationServiceTypeResponse> findByIds(Set<Integer> ids) {
        if (ids == null || ids.isEmpty()) return Collections.emptyList();
        List<QuotationServiceType> serviceTypes = quotationServiceTypeRepository.list("id in ?1", new ArrayList<>(ids));
        return quotationServiceTypeServiceMapper.toQuotationServiceTypeResponseList(serviceTypes);
    }

    public List<QuotationServiceTypeResponse> listQuotationServiceTypes(ListQuotationServiceTypesQuery listQuotationServiceTypesQuery) {
        Boolean isActiveFilter = listQuotationServiceTypesQuery.isActive();
        List<QuotationServiceType> quotationServiceTypes = (isActiveFilter == null)
            ? quotationServiceTypeRepository.listAllOrderedByCode()
            : quotationServiceTypeRepository.listByIsActiveOrderedByCode(isActiveFilter);

        return quotationServiceTypeServiceMapper.toQuotationServiceTypeResponseList(quotationServiceTypes);
    }
}
