package com.scaramutti.tms.warehouse.purchaseinvoice.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Proveedor embebido en el detalle de una entrada: id + razón social + RUC.
 * El listado usa una forma más chica ({@link WarehouseInvoiceSupplierSummaryRef},
 * solo id + name), como fija el contrato.
 */
public record WarehouseInvoiceSupplierRef(
    @Schema(example = "4") Integer id,
    @Schema(example = "REPUESTOS DIESEL S.A.C.") String name,
    @Schema(example = "20512345678", nullable = true) String ruc
) {}
