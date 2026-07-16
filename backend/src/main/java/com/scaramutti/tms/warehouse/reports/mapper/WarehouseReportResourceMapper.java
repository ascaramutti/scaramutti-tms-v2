package com.scaramutti.tms.warehouse.reports.mapper;

import com.scaramutti.tms.warehouse.reports.model.WarehouseReportCut;
import com.scaramutti.tms.warehouse.reports.service.cmd.GetWarehouseReportQuery;
import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;

import java.time.LocalDate;

/**
 * Mapper de la capa REST del reporte. Sin normalizacion propia (los params ya
 * llegan tipados/validados por JAX-RS): solo agrupa la firma en el Query (mismo
 * criterio que {@code WarehouseKardexResourceMapper}).
 */
@Mapper(componentModel = MappingConstants.ComponentModel.CDI)
public interface WarehouseReportResourceMapper {

    GetWarehouseReportQuery toGetWarehouseReportQuery(
        WarehouseReportCut cut, LocalDate dateFrom, LocalDate dateTo
    );
}
