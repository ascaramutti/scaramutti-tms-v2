package com.scaramutti.tms.warehouse.reports.mapper;

import com.scaramutti.tms.shared.mapper.SharedMapperConfig;
import com.scaramutti.tms.shared.repository.WarehouseReportRepository.ReportRowView;
import com.scaramutti.tms.warehouse.reports.dto.WarehouseReportResponse;
import com.scaramutti.tms.warehouse.reports.dto.WarehouseReportRowResponse;
import com.scaramutti.tms.warehouse.reports.service.cmd.GetWarehouseReportQuery;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.math.BigDecimal;
import java.util.List;

/**
 * Mapper de la capa Service: filas de la query de reportes y header del response.
 * Los totales por moneda (RN-WH7, sin conversion) los calcula el service y llegan
 * ya sumados; aca solo se ensambla el record.
 */
@Mapper(config = SharedMapperConfig.class)
public interface WarehouseReportServiceMapper {

    WarehouseReportRowResponse toWarehouseReportRowResponse(ReportRowView view);

    List<WarehouseReportRowResponse> toWarehouseReportRowResponseList(List<ReportRowView> views);

    @Mapping(target = "cut",      source = "query.cut")
    @Mapping(target = "dateFrom", source = "query.dateFrom")
    @Mapping(target = "dateTo",   source = "query.dateTo")
    WarehouseReportResponse toWarehouseReportResponse(
        GetWarehouseReportQuery query,
        List<WarehouseReportRowResponse> rows,
        BigDecimal totalPEN,
        BigDecimal totalUSD,
        BigDecimal totalCount
    );
}
