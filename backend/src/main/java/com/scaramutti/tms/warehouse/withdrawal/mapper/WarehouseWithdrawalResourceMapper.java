package com.scaramutti.tms.warehouse.withdrawal.mapper;

import com.scaramutti.tms.shared.util.StringUtils;
import com.scaramutti.tms.warehouse.withdrawal.dto.WarehouseWithdrawalRequest;
import com.scaramutti.tms.warehouse.withdrawal.service.cmd.CreateWarehouseWithdrawalCommand;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.NullValueMappingStrategy;

/**
 * Mapper de la capa REST. Normaliza {@code observations} (trim, "" → null). El mapeo del
 * listado se agrega con el endpoint de listado.
 */
@Mapper(
    componentModel = MappingConstants.ComponentModel.CDI,
    uses = StringUtils.class,
    nullValueMappingStrategy = NullValueMappingStrategy.RETURN_DEFAULT
)
public interface WarehouseWithdrawalResourceMapper {

    @Mapping(target = "observations", source = "observations", qualifiedByName = "trimToNull")
    CreateWarehouseWithdrawalCommand toCreateWarehouseWithdrawalCommand(
        WarehouseWithdrawalRequest warehouseWithdrawalRequest
    );
}
