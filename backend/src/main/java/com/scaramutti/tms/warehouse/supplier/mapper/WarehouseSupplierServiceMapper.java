package com.scaramutti.tms.warehouse.supplier.mapper;

import com.scaramutti.tms.shared.entity.Supplier;
import com.scaramutti.tms.warehouse.supplier.dto.WarehouseSupplierResponse;
import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;

import java.util.List;

@Mapper(componentModel = MappingConstants.ComponentModel.CDI)
public interface WarehouseSupplierServiceMapper {

    WarehouseSupplierResponse toWarehouseSupplierResponse(Supplier supplier);

    List<WarehouseSupplierResponse> toWarehouseSupplierResponseList(List<Supplier> suppliers);
}
