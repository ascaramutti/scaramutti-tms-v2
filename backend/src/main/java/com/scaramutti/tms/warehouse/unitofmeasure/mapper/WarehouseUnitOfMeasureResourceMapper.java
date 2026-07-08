package com.scaramutti.tms.warehouse.unitofmeasure.mapper;

import com.scaramutti.tms.warehouse.unitofmeasure.service.cmd.ListWarehouseUnitsOfMeasureQuery;
import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;
import org.mapstruct.NullValueMappingStrategy;

@Mapper(componentModel = MappingConstants.ComponentModel.CDI,
        nullValueMappingStrategy = NullValueMappingStrategy.RETURN_DEFAULT)
public interface WarehouseUnitOfMeasureResourceMapper {

    ListWarehouseUnitsOfMeasureQuery toListWarehouseUnitsOfMeasureQuery(Boolean isActive);
}
