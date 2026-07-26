package com.scaramutti.tms.warehouse.supplier.mapper;

import com.scaramutti.tms.shared.entity.Supplier;
import com.scaramutti.tms.shared.mapper.SharedMapperConfig;
import com.scaramutti.tms.warehouse.supplier.dto.WarehouseSupplierResponse;
import com.scaramutti.tms.warehouse.supplier.service.cmd.CreateWarehouseSupplierCommand;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

/**
 * Mapper de la capa Service: toSupplierEntity setea isActive=true
 * explícitamente, NO setea id (BD) ni createdAt (@PrePersist de la entity).
 */
@Mapper(config = SharedMapperConfig.class)
public interface WarehouseSupplierServiceMapper {

    @Mapping(target = "id",        ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "isActive",  constant = "true")
    Supplier toSupplierEntity(CreateWarehouseSupplierCommand createWarehouseSupplierCommand);

    WarehouseSupplierResponse toWarehouseSupplierResponse(Supplier supplier);

    List<WarehouseSupplierResponse> toWarehouseSupplierResponseList(List<Supplier> suppliers);
}
