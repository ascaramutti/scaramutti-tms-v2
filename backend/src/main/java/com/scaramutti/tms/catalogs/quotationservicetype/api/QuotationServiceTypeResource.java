package com.scaramutti.tms.catalogs.quotationservicetype.api;

import com.scaramutti.tms.catalogs.quotationservicetype.dto.QuotationServiceTypeResponse;
import com.scaramutti.tms.catalogs.quotationservicetype.mapper.QuotationServiceTypeResourceMapper;
import com.scaramutti.tms.catalogs.quotationservicetype.service.QuotationServiceTypeService;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import java.util.List;

@Path("/quotation-service-types")
@Produces(MediaType.APPLICATION_JSON)
public class QuotationServiceTypeResource {

    @Inject QuotationServiceTypeService quotationServiceTypeService;
    @Inject QuotationServiceTypeResourceMapper quotationServiceTypeResourceMapper;

    @GET
    public List<QuotationServiceTypeResponse> listQuotationServiceTypes(@QueryParam("isActive") Boolean isActive) {
        return quotationServiceTypeService.listQuotationServiceTypes(
            quotationServiceTypeResourceMapper.toListQuotationServiceTypesQuery(isActive)
        );
    }
}
