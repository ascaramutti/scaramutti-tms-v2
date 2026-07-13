package com.scaramutti.tms.warehouse.purchaseinvoice.service.cmd;

import java.math.BigDecimal;

/** Línea de una entrada, ya normalizada por la capa REST. */
public record CreateWarehouseInvoiceItemCommand(
    Integer productId,
    BigDecimal quantity,
    BigDecimal unitPrice
) {}
