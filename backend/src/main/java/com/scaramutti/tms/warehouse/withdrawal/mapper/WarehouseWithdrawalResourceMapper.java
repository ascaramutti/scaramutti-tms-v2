package com.scaramutti.tms.warehouse.withdrawal.mapper;

import com.scaramutti.tms.shared.mapper.SharedMapperConfig;
import com.scaramutti.tms.shared.util.StringUtils;
import com.scaramutti.tms.warehouse.model.WarehouseRecordStatus;
import com.scaramutti.tms.warehouse.withdrawal.dto.WarehouseWithdrawalRequest;
import com.scaramutti.tms.warehouse.withdrawal.dto.WarehouseWithdrawalUpdateRequest;
import com.scaramutti.tms.warehouse.withdrawal.service.cmd.CreateWarehouseWithdrawalCommand;
import com.scaramutti.tms.warehouse.withdrawal.service.cmd.ListWarehouseWithdrawalsQuery;
import com.scaramutti.tms.warehouse.withdrawal.service.cmd.UpdateWarehouseWithdrawalCommand;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.NullValueMappingStrategy;

import java.time.LocalDate;

/**
 * Mapper de la capa REST. Normaliza {@code observations} (trim, "" → null). El mapeo del
 * listado se agrega con el endpoint de listado.
 */
@Mapper(
    config = SharedMapperConfig.class,
    uses = StringUtils.class,
    nullValueMappingStrategy = NullValueMappingStrategy.RETURN_DEFAULT
)
public interface WarehouseWithdrawalResourceMapper {

    @Mapping(target = "observations", source = "observations", qualifiedByName = "trimToNull")
    CreateWarehouseWithdrawalCommand toCreateWarehouseWithdrawalCommand(
        WarehouseWithdrawalRequest warehouseWithdrawalRequest
    );

    @Mapping(target = "withdrawalId",       source = "withdrawalId")
    @Mapping(target = "ifMatch",            source = "ifMatch")
    @Mapping(target = "observations",       source = "request.observations", qualifiedByName = "trimToNull")
    @Mapping(target = "quantity",           source = "request.quantity")
    @Mapping(target = "receivedByWorkerId", source = "request.receivedByWorkerId")
    @Mapping(target = "tractorId",          source = "request.tractorId")
    @Mapping(target = "trailerId",          source = "request.trailerId")
    @Mapping(target = "escortVehicleId",    source = "request.escortVehicleId")
    @Mapping(target = "reason",             source = "request.reason")
    UpdateWarehouseWithdrawalCommand toUpdateWarehouseWithdrawalCommand(
        Integer withdrawalId, String ifMatch, WarehouseWithdrawalUpdateRequest request
    );

    ListWarehouseWithdrawalsQuery toListWarehouseWithdrawalsQuery(
        Integer productId, Integer receivedByWorkerId, Integer tractorId, Integer trailerId,
        Integer escortVehicleId, WarehouseRecordStatus status, LocalDate dateFrom, LocalDate dateTo,
        int page, int size
    );
}
