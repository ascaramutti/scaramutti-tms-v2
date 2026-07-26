package com.scaramutti.tms.warehouse.purchaseinvoice.service.cmd;

import java.time.LocalDate;
import java.util.List;

/**
 * Edición de una entrada, ya normalizada por la capa REST. Lleva el {@code id} del path
 * y el {@code ifMatch} del header. SIN {@code supplierId} (inmutable). Los {@code items}
 * reemplazan a los existentes. Reusa {@link CreateWarehouseInvoiceItemCommand} para las
 * líneas (misma forma).
 */
public record UpdateWarehousePurchaseInvoiceCommand(
    Integer invoiceId,
    String ifMatch,
    String invoiceNumber,
    LocalDate invoiceDate,
    String guideNumber,
    Integer currencyId,
    String observations,
    List<CreateWarehouseInvoiceItemCommand> items,
    String reason
) {}
