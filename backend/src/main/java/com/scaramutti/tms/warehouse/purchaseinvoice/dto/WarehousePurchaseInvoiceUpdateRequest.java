package com.scaramutti.tms.warehouse.purchaseinvoice.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.time.LocalDate;
import java.util.List;

/**
 * Body del PUT /warehouse/purchase-invoices/{id}. SIN {@code supplierId}: el proveedor
 * es INMUTABLE (RN-WH4, proveedor equivocado = anular y re-registrar). Los {@code items}
 * REEMPLAZAN a los existentes. {@code reason} (>= 10) va a {@code almacen.audit_logs}.
 */
public record WarehousePurchaseInvoiceUpdateRequest(
    @Schema(example = "F001-00123") @NotBlank @Size(min = 1, max = 50) String invoiceNumber,
    @Schema(example = "2026-07-02") @NotNull LocalDate invoiceDate,
    @Schema(example = "T001-0004567", nullable = true) @Size(max = 50) String guideNumber,
    @Schema(example = "2") @NotNull Integer currencyId,
    @Schema(nullable = true) String observations,
    @NotNull @Size(min = 1, max = 200) @Valid List<WarehouseInvoiceItemRequest> items,
    @Schema(example = "Corrección del número de guía de remisión") @NotBlank @Size(min = 10, max = 500) String reason
) {}
