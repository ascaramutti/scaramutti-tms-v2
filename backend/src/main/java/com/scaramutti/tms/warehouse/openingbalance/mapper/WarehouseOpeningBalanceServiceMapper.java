package com.scaramutti.tms.warehouse.openingbalance.mapper;

import com.scaramutti.tms.auth.dto.UserResponse;
import com.scaramutti.tms.shared.entity.OpeningBalance;
import com.scaramutti.tms.shared.entity.Product;
import com.scaramutti.tms.shared.entity.UnitOfMeasure;
import com.scaramutti.tms.shared.mapper.SharedMapperConfig;
import com.scaramutti.tms.warehouse.openingbalance.dto.WarehouseOpeningBalanceResponse;
import com.scaramutti.tms.warehouse.openingbalance.service.cmd.CreateWarehouseOpeningBalanceCommand;
import com.scaramutti.tms.warehouse.product.dto.WarehouseProductSummary;
import com.scaramutti.tms.warehouse.product.mapper.WarehouseProductSummaryMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * Mapper de la capa Service: arma la entity a partir del command + el id del
 * usuario autenticado (NO setea id/registeredAt: los maneja la BD /
 * {@code @PrePersist} de la entity) y el shaping al response. Los lookups
 * (producto, unidad, usuario) los resuelve el service; aca llegan resueltos.
 */
@Mapper(config = SharedMapperConfig.class)
public interface WarehouseOpeningBalanceServiceMapper extends WarehouseProductSummaryMapping {

    @Mapping(target = "id",           ignore = true)
    @Mapping(target = "registeredAt", ignore = true)
    @Mapping(target = "registeredBy", source = "userId")
    OpeningBalance toOpeningBalanceEntity(CreateWarehouseOpeningBalanceCommand createWarehouseOpeningBalanceCommand, Integer userId);

    default WarehouseOpeningBalanceResponse toWarehouseOpeningBalanceResponse(
        OpeningBalance openingBalance, Product product, UnitOfMeasure unitOfMeasure, UserResponse registeredBy
    ) {
        return toWarehouseOpeningBalanceResponse(
            openingBalance, toWarehouseProductSummary(product, unitOfMeasure), registeredBy);
    }

    @Mapping(target = "id",           source = "openingBalance.id")
    @Mapping(target = "registeredBy", source = "registeredBy")
    WarehouseOpeningBalanceResponse toWarehouseOpeningBalanceResponse(
        OpeningBalance openingBalance, WarehouseProductSummary product, UserResponse registeredBy
    );
}
