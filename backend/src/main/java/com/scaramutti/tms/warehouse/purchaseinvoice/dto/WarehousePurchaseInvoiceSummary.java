package com.scaramutti.tms.warehouse.purchaseinvoice.dto;

import com.scaramutti.tms.auth.dto.UserResponse;
import com.scaramutti.tms.warehouse.model.WarehouseRecordStatus;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * Fila del listado de Entradas. {@code itemsCount} y {@code total} se derivan de los
 * ítems (no persistidos), en una query agregada única por página (sin N+1).
 */
public record WarehousePurchaseInvoiceSummary(
    Integer id,
    WarehouseInvoiceSupplierSummaryRef supplier,
    String invoiceNumber,
    LocalDate invoiceDate,
    @Schema(nullable = true) String guideNumber,
    @Schema(example = "PEN") String currencyCode,
    @Schema(example = "2") int itemsCount,
    @Schema(description = "Derivado de los ítems (no persistido)") BigDecimal total,
    WarehouseRecordStatus status,
    @Schema(nullable = true) String cancelReason,
    UserResponse registeredBy,
    OffsetDateTime createdAt
) {}
