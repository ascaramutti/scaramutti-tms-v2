package com.scaramutti.tms.catalogs.quotationservicetype.service;

import com.scaramutti.tms.catalogs.quotationservicetype.dto.QuotationServiceTypeResponse;
import com.scaramutti.tms.catalogs.quotationservicetype.mapper.QuotationServiceTypeServiceMapper;
import com.scaramutti.tms.catalogs.quotationservicetype.service.cmd.ListQuotationServiceTypesQuery;
import com.scaramutti.tms.shared.entity.QuotationServiceType;
import com.scaramutti.tms.shared.repository.QuotationServiceTypeRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;

@ApplicationScoped
public class QuotationServiceTypeService {

    @Inject QuotationServiceTypeRepository quotationServiceTypeRepository;
    @Inject QuotationServiceTypeServiceMapper quotationServiceTypeServiceMapper;

    public List<QuotationServiceTypeResponse> listQuotationServiceTypes(ListQuotationServiceTypesQuery listQuotationServiceTypesQuery) {
        Boolean isActiveFilter = listQuotationServiceTypesQuery.isActive();
        List<QuotationServiceType> quotationServiceTypes = (isActiveFilter == null)
            ? quotationServiceTypeRepository.listAllOrderedByCode()
            : quotationServiceTypeRepository.listByIsActiveOrderedByCode(isActiveFilter);

        return quotationServiceTypeServiceMapper.toQuotationServiceTypeResponseList(quotationServiceTypes);
    }
}
