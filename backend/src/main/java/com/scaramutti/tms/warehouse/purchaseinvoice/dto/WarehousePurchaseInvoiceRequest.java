package com.scaramutti.tms.warehouse.purchaseinvoice.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.time.LocalDate;
import java.util.List;

/**
 * Body del POST /warehouse/purchase-invoices: cabecera + ítems ({@code >= 1}).
 * Todo se crea en una transacción; cada ítem suma stock vía la VIEW.
 * {@code guideNumber}/{@code observations} son opcionales.
 */
public record WarehousePurchaseInvoiceRequest(
    @Schema(example = "4") @NotNull Integer supplierId,
    @Schema(example = "F001-00123") @NotBlank @Size(min = 1, max = 50) String invoiceNumber,
    @Schema(example = "2026-07-02") @NotNull LocalDate invoiceDate,
    @Schema(example = "T001-0004567", nullable = true) @Size(max = 50) String guideNumber,
    @Schema(example = "2") @NotNull Integer currencyId,
    @Schema(nullable = true) String observations,
    @NotNull @Size(min = 1, max = 200) @Valid List<WarehouseInvoiceItemRequest> items
) {}
