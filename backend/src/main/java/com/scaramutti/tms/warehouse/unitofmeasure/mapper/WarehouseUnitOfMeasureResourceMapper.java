package com.scaramutti.tms.warehouse.unitofmeasure.mapper;

import com.scaramutti.tms.shared.mapper.SharedMapperConfig;
import com.scaramutti.tms.warehouse.unitofmeasure.service.cmd.ListWarehouseUnitsOfMeasureQuery;
import org.mapstruct.Mapper;
import org.mapstruct.NullValueMappingStrategy;

@Mapper(config = SharedMapperConfig.class,
        nullValueMappingStrategy = NullValueMappingStrategy.RETURN_DEFAULT)
public interface WarehouseUnitOfMeasureResourceMapper {

    ListWarehouseUnitsOfMeasureQuery toListWarehouseUnitsOfMeasureQuery(Boolean isActive);
}
