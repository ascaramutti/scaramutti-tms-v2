package com.scaramutti.tms.warehouse.purchaseinvoice.mapper;

import com.scaramutti.tms.shared.util.StringUtils;
import com.scaramutti.tms.warehouse.model.WarehouseRecordStatus;
import com.scaramutti.tms.warehouse.purchaseinvoice.dto.WarehousePurchaseInvoiceRequest;
import com.scaramutti.tms.warehouse.purchaseinvoice.service.cmd.CreateWarehousePurchaseInvoiceCommand;
import com.scaramutti.tms.warehouse.purchaseinvoice.service.cmd.ListWarehousePurchaseInvoicesQuery;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
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
    componentModel = MappingConstants.ComponentModel.CDI,
    uses = StringUtils.class,
    nullValueMappingStrategy = NullValueMappingStrategy.RETURN_DEFAULT
)
public interface WarehousePurchaseInvoiceResourceMapper {

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
}
