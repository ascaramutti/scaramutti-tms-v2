package com.scaramutti.tms.warehouse.purchaseinvoice.mapper;

import com.scaramutti.tms.shared.mapper.SharedMapperConfig;
import com.scaramutti.tms.shared.util.StringUtils;
import com.scaramutti.tms.warehouse.model.WarehouseRecordStatus;
import com.scaramutti.tms.warehouse.purchaseinvoice.dto.WarehousePurchaseInvoiceRequest;
import com.scaramutti.tms.warehouse.purchaseinvoice.dto.WarehousePurchaseInvoiceUpdateRequest;
import com.scaramutti.tms.warehouse.purchaseinvoice.service.cmd.CreateWarehousePurchaseInvoiceCommand;
import com.scaramutti.tms.warehouse.purchaseinvoice.service.cmd.ListWarehousePurchaseInvoicesQuery;
import com.scaramutti.tms.warehouse.purchaseinvoice.service.cmd.UpdateWarehousePurchaseInvoiceCommand;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.NullValueMappingStrategy;

import java.time.LocalDate;

/**
 * Mapper de la capa REST. Normaliza {@code guideNumber}/{@code observations} (trim,
 * "" → null) y el {@code q} del listado (trimToNull; la búsqueda es case-insensitive
 * vía ILIKE, sin uppercase, y el orden del listado es createdAt DESC, no similarity).
 *
 * <p>{@code nullValueMappingStrategy = RETURN_DEFAULT}: sin esto MapStruct trata el
 * único parámetro objeto opcional del {@code toList...Query} como "la" fuente y
 * genera un early-return al llegar null, tirando abajo page/size. Mismo criterio que
 * WarehouseOpeningBalanceResourceMapper/WarehouseProductResourceMapper.
 */
@Mapper(
    config = SharedMapperConfig.class,
    uses = StringUtils.class,
    nullValueMappingStrategy = NullValueMappingStrategy.RETURN_DEFAULT
)
public interface WarehousePurchaseInvoiceResourceMapper {

    // invoiceNumber se trimea aunque sea obligatorio: es EL campo de unicidad (proveedor
    // + número, RN-WH5), así que un espacio de borde (copy-paste de Excel de finanzas)
    // no debe registrar la misma factura dos veces ni burlar el UNIQUE. Solo trim, SIN
    // upper: el índice de V002 es case-sensitive y el código espeja la BD. @NotBlank ya
    // garantiza contenido no-blank antes del mapper, así que trimToNull nunca da null.
    @Mapping(target = "invoiceNumber", source = "invoiceNumber", qualifiedByName = "trimToNull")
    @Mapping(target = "guideNumber",  source = "guideNumber",  qualifiedByName = "trimToNull")
    @Mapping(target = "observations", source = "observations", qualifiedByName = "trimToNull")
    CreateWarehousePurchaseInvoiceCommand toCreateWarehousePurchaseInvoiceCommand(
        WarehousePurchaseInvoiceRequest warehousePurchaseInvoiceRequest
    );

    @Mapping(target = "q", source = "q", qualifiedByName = "trimToNull")
    ListWarehousePurchaseInvoicesQuery toListWarehousePurchaseInvoicesQuery(
        String q, Integer supplierId, WarehouseRecordStatus status,
        LocalDate dateFrom, LocalDate dateTo, int page, int size
    );

    // invoiceNumber trimeado (campo de unicidad, mismo criterio que el alta); guideNumber/
    // observations trim → null. supplierId NO está (inmutable). El id y el ifMatch vienen
    // del path/header. Los items reusan el mapeo por forma (misma shape que el alta).
    @Mapping(target = "invoiceNumber", source = "request.invoiceNumber", qualifiedByName = "trimToNull")
    @Mapping(target = "guideNumber",   source = "request.guideNumber",   qualifiedByName = "trimToNull")
    @Mapping(target = "observations",  source = "request.observations",  qualifiedByName = "trimToNull")
    @Mapping(target = "invoiceDate",   source = "request.invoiceDate")
    @Mapping(target = "currencyId",    source = "request.currencyId")
    @Mapping(target = "items",         source = "request.items")
    @Mapping(target = "reason",        source = "request.reason")
    UpdateWarehousePurchaseInvoiceCommand toUpdateWarehousePurchaseInvoiceCommand(
        Integer invoiceId, String ifMatch, WarehousePurchaseInvoiceUpdateRequest request
    );
}
