package com.scaramutti.tms.warehouse.purchaseinvoice.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Proveedor embebido en la fila del listado de entradas: solo id + razón social
 * (el listado no muestra RUC, a diferencia del detalle).
 */
public record WarehouseInvoiceSupplierSummaryRef(
    @Schema(example = "4") Integer id,
    @Schema(example = "REPUESTOS DIESEL S.A.C.") String name
) {}
