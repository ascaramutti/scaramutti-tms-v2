package com.scaramutti.tms.warehouse.openingbalance.mapper;

import com.scaramutti.tms.shared.entity.OpeningBalance;
import com.scaramutti.tms.warehouse.openingbalance.service.cmd.CreateWarehouseOpeningBalanceCommand;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;

/**
 * Mapper de la capa Service: arma la entity a partir del command + el id del
 * usuario autenticado. NO setea id/registeredAt (los maneja la BD /
 * {@code @PrePersist} de la entity).
 */
@Mapper(componentModel = MappingConstants.ComponentModel.CDI)
public interface WarehouseOpeningBalanceServiceMapper {

    @Mapping(target = "id",           ignore = true)
    @Mapping(target = "registeredAt", ignore = true)
    @Mapping(target = "registeredBy", source = "userId")
    OpeningBalance toOpeningBalanceEntity(CreateWarehouseOpeningBalanceCommand createWarehouseOpeningBalanceCommand, Integer userId);
}
