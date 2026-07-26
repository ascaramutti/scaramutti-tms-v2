package com.scaramutti.tms.warehouse.unitofmeasure.mapper;

import com.scaramutti.tms.shared.entity.UnitOfMeasure;
import com.scaramutti.tms.shared.mapper.SharedMapperConfig;
import com.scaramutti.tms.warehouse.unitofmeasure.dto.WarehouseUnitOfMeasureResponse;
import org.mapstruct.Mapper;

import java.util.List;

@Mapper(config = SharedMapperConfig.class)
public interface WarehouseUnitOfMeasureServiceMapper {

    WarehouseUnitOfMeasureResponse toWarehouseUnitOfMeasureResponse(UnitOfMeasure unitOfMeasure);

    List<WarehouseUnitOfMeasureResponse> toWarehouseUnitOfMeasureResponseList(List<UnitOfMeasure> unitsOfMeasure);
}
