package com.scaramutti.tms.warehouse.unitofmeasure.service;

import com.scaramutti.tms.shared.entity.UnitOfMeasure;
import com.scaramutti.tms.shared.repository.UnitOfMeasureRepository;
import com.scaramutti.tms.warehouse.unitofmeasure.dto.WarehouseUnitOfMeasureResponse;
import com.scaramutti.tms.warehouse.unitofmeasure.mapper.WarehouseUnitOfMeasureServiceMapper;
import com.scaramutti.tms.warehouse.unitofmeasure.service.cmd.ListWarehouseUnitsOfMeasureQuery;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;

/** Lista cerrada (RN-WH9): solo lectura, sin create/update/delete. */
@ApplicationScoped
public class WarehouseUnitOfMeasureService {

    @Inject UnitOfMeasureRepository unitOfMeasureRepository;
    @Inject WarehouseUnitOfMeasureServiceMapper warehouseUnitOfMeasureServiceMapper;

    public List<WarehouseUnitOfMeasureResponse> listUnitsOfMeasure(
            ListWarehouseUnitsOfMeasureQuery listWarehouseUnitsOfMeasureQuery) {
        Boolean isActiveFilter = listWarehouseUnitsOfMeasureQuery.isActive();
        List<UnitOfMeasure> unitsOfMeasure = (isActiveFilter == null)
            ? unitOfMeasureRepository.listAllOrderedByCode()
            : unitOfMeasureRepository.listByIsActiveOrderedByCode(isActiveFilter);

        return warehouseUnitOfMeasureServiceMapper.toWarehouseUnitOfMeasureResponseList(unitsOfMeasure);
    }
}
