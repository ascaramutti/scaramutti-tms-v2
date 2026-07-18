package com.scaramutti.tms.warehouse.withdrawal.mapper;

import com.scaramutti.tms.shared.entity.Withdrawal;
import com.scaramutti.tms.shared.mapper.SharedMapperConfig;
import com.scaramutti.tms.warehouse.withdrawal.service.cmd.CreateWarehouseWithdrawalCommand;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * Mapper de la capa Service: arma la entity a partir del command + el id del usuario
 * autenticado. NO setea id/withdrawnAt/updatedAt/status/campos de anulación (los
 * maneja el {@code @PrePersist} de la entity / la anulación).
 */
@Mapper(config = SharedMapperConfig.class)
public interface WarehouseWithdrawalServiceMapper {

    @Mapping(target = "id",           ignore = true)
    @Mapping(target = "withdrawnAt",  ignore = true)
    @Mapping(target = "updatedAt",    ignore = true)
    @Mapping(target = "status",       ignore = true)
    @Mapping(target = "cancelReason", ignore = true)
    @Mapping(target = "cancelledBy",  ignore = true)
    @Mapping(target = "cancelledAt",  ignore = true)
    @Mapping(target = "receivedBy",   source = "command.receivedByWorkerId")
    @Mapping(target = "registeredBy", source = "userId")
    Withdrawal toWithdrawalEntity(CreateWarehouseWithdrawalCommand command, Integer userId);
}
