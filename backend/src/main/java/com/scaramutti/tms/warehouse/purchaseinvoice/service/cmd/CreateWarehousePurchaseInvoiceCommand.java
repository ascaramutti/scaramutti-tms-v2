package com.scaramutti.tms.warehouse.purchaseinvoice.service.cmd;

import java.time.LocalDate;
import java.util.List;

/**
 * Alta de una entrada, ya normalizada por la capa REST ({@code guideNumber}/
 * {@code observations} con "" → null).
 */
public record CreateWarehousePurchaseInvoiceCommand(
    Integer supplierId,
    String invoiceNumber,
    LocalDate invoiceDate,
    String guideNumber,
    Integer currencyId,
    String observations,
    List<CreateWarehouseInvoiceItemCommand> items
) {}
