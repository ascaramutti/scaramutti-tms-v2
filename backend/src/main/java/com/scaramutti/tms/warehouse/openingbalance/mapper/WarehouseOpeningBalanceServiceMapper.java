package com.scaramutti.tms.warehouse.openingbalance.mapper;

import com.scaramutti.tms.shared.entity.OpeningBalance;
import com.scaramutti.tms.shared.mapper.SharedMapperConfig;
import com.scaramutti.tms.warehouse.openingbalance.service.cmd.CreateWarehouseOpeningBalanceCommand;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * Mapper de la capa Service: arma la entity a partir del command + el id del
 * usuario autenticado. NO setea id/registeredAt (los maneja la BD /
 * {@code @PrePersist} de la entity).
 */
@Mapper(config = SharedMapperConfig.class)
public interface WarehouseOpeningBalanceServiceMapper {

    @Mapping(target = "id",           ignore = true)
    @Mapping(target = "registeredAt", ignore = true)
    @Mapping(target = "registeredBy", source = "userId")
    OpeningBalance toOpeningBalanceEntity(CreateWarehouseOpeningBalanceCommand createWarehouseOpeningBalanceCommand, Integer userId);
}
