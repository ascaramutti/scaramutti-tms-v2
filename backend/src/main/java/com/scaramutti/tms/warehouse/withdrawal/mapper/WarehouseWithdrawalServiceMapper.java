package com.scaramutti.tms.warehouse.withdrawal.mapper;

import com.scaramutti.tms.auth.dto.UserResponse;
import com.scaramutti.tms.shared.dto.WorkerResponse;
import com.scaramutti.tms.shared.entity.Product;
import com.scaramutti.tms.shared.entity.UnitOfMeasure;
import com.scaramutti.tms.shared.entity.Withdrawal;
import com.scaramutti.tms.shared.entity.Worker;
import com.scaramutti.tms.shared.mapper.SharedMapperConfig;
import com.scaramutti.tms.warehouse.product.dto.WarehouseProductSummary;
import com.scaramutti.tms.warehouse.product.mapper.WarehouseProductSummaryMapping;
import com.scaramutti.tms.warehouse.purchaseinvoice.dto.WarehouseEditTrace;
import com.scaramutti.tms.warehouse.withdrawal.dto.FleetUnitRef;
import com.scaramutti.tms.warehouse.withdrawal.dto.WarehouseWithdrawalResponse;
import com.scaramutti.tms.warehouse.withdrawal.service.cmd.CreateWarehouseWithdrawalCommand;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * Mapper de la capa Service: arma la entity a partir del command + el id del usuario
 * autenticado (NO setea id/withdrawnAt/updatedAt/status/campos de anulación: los
 * maneja el {@code @PrePersist} de la entity / la anulación) y el shaping al
 * response. Los lookups (producto, unidad, trabajador, flota, usuarios, lastEdit)
 * los resuelve el service; aca llegan resueltos.
 */
@Mapper(config = SharedMapperConfig.class)
public interface WarehouseWithdrawalServiceMapper extends WarehouseProductSummaryMapping {

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

    default WarehouseWithdrawalResponse toWarehouseWithdrawalResponse(
        Withdrawal withdrawal, Product product, UnitOfMeasure unit, Worker worker,
        FleetUnitRef fleetUnit, UserResponse registeredBy, UserResponse cancelledBy,
        WarehouseEditTrace lastEdit
    ) {
        return toWarehouseWithdrawalResponse(
            withdrawal, toWarehouseProductSummary(product, unit), toWorkerResponse(worker),
            fleetUnit, registeredBy, cancelledBy, lastEdit);
    }

    /**
     * Targets explícitos: receivedBy/cancelledBy/registeredBy existen en la entity
     * como ids (Integer) y como parámetro resuelto, MapStruct no puede desambiguar
     * solo. El status String de la entity se reconstruye al enum (valueOf generado).
     */
    @Mapping(target = "id",           source = "withdrawal.id")
    @Mapping(target = "product",      source = "product")
    @Mapping(target = "quantity",     source = "withdrawal.quantity")
    @Mapping(target = "withdrawnAt",  source = "withdrawal.withdrawnAt")
    @Mapping(target = "receivedBy",   source = "receivedBy")
    @Mapping(target = "fleetUnit",    source = "fleetUnit")
    @Mapping(target = "observations", source = "withdrawal.observations")
    @Mapping(target = "status",       source = "withdrawal.status")
    @Mapping(target = "cancelReason", source = "withdrawal.cancelReason")
    @Mapping(target = "cancelledBy",  source = "cancelledBy")
    @Mapping(target = "cancelledAt",  source = "withdrawal.cancelledAt")
    @Mapping(target = "lastEdit",     source = "lastEdit")
    @Mapping(target = "registeredBy", source = "registeredBy")
    @Mapping(target = "updatedAt",    source = "withdrawal.updatedAt")
    WarehouseWithdrawalResponse toWarehouseWithdrawalResponse(
        Withdrawal withdrawal, WarehouseProductSummary product, WorkerResponse receivedBy,
        FleetUnitRef fleetUnit, UserResponse registeredBy, UserResponse cancelledBy,
        WarehouseEditTrace lastEdit
    );

    /**
     * Declaración gemela de {@code WorkerServiceMapper.toWorkerResponse} (sharedcatalogs):
     * no se comparte para no crear un ciclo de packages warehouse <-> sharedcatalogs
     * (sharedcatalogs ya importa warehouse.model.FleetUnitKind).
     */
    @Mapping(target = "fullName", expression = "java(worker.fullName())")
    WorkerResponse toWorkerResponse(Worker worker);
}
