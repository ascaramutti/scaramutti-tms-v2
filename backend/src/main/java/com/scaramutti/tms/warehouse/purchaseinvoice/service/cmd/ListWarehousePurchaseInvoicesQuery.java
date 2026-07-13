package com.scaramutti.tms.warehouse.purchaseinvoice.service.cmd;

import com.scaramutti.tms.warehouse.model.WarehouseRecordStatus;

import java.time.LocalDate;

/**
 * Filtros del listado de entradas. {@code q} llega ya trimmed (multi-palabra
 * RN-WH14). {@code status} null = trae ACTIVAS y ANULADAS (el contrato lista las
 * anuladas con badge). {@code dateFrom}/{@code dateTo} filtran por {@code invoiceDate}
 * (rango inclusive).
 */
public record ListWarehousePurchaseInvoicesQuery(
    String q,
    Integer supplierId,
    WarehouseRecordStatus status,
    LocalDate dateFrom,
    LocalDate dateTo,
    int page,
    int size
) {}
