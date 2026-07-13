package com.scaramutti.tms.warehouse.purchaseinvoice.mapper;

import com.scaramutti.tms.shared.entity.PurchaseInvoice;
import com.scaramutti.tms.shared.entity.PurchaseInvoiceItem;
import com.scaramutti.tms.warehouse.purchaseinvoice.service.cmd.CreateWarehouseInvoiceItemCommand;
import com.scaramutti.tms.warehouse.purchaseinvoice.service.cmd.CreateWarehousePurchaseInvoiceCommand;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;

/**
 * Mapper de la capa Service: arma las entities a partir del command + el id del
 * usuario autenticado. NO setea id/timestamps/status/campos de anulación (los
 * maneja la BD / el {@code @PrePersist} de la entity). El {@code invoiceId} de cada
 * ítem lo setea el service tras persistir la cabecera (FK plana, no cascade).
 */
@Mapper(componentModel = MappingConstants.ComponentModel.CDI)
public interface WarehousePurchaseInvoiceServiceMapper {

    @Mapping(target = "id",              ignore = true)
    @Mapping(target = "createdAt",       ignore = true)
    @Mapping(target = "updatedAt",       ignore = true)
    @Mapping(target = "status",          ignore = true)
    @Mapping(target = "attachmentPath",  ignore = true)
    @Mapping(target = "cancelReason",    ignore = true)
    @Mapping(target = "cancelledBy",     ignore = true)
    @Mapping(target = "cancelledAt",     ignore = true)
    @Mapping(target = "registeredBy",    source = "userId")
    PurchaseInvoice toEntity(CreateWarehousePurchaseInvoiceCommand command, Integer userId);

    @Mapping(target = "id",        ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "invoiceId", source = "invoiceId")
    PurchaseInvoiceItem toItemEntity(CreateWarehouseInvoiceItemCommand command, Integer invoiceId);
}
