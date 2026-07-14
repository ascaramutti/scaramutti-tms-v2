package com.scaramutti.tms.warehouse.purchaseinvoice.dto;

import com.scaramutti.tms.auth.dto.UserResponse;
import com.scaramutti.tms.warehouse.model.WarehouseRecordStatus;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Detalle de una entrada (respuesta del POST y, a futuro, del GET/{id}).
 * {@code total} = Σ items.subtotal, derivado en código (nunca persistido, patrón v2).
 * Los campos de anulación ({@code cancelReason}/{@code cancelledBy}/{@code cancelledAt})
 * y {@code lastEdit} viajan null mientras la factura esté ACTIVE y sin ediciones (A8);
 * los pueblan la anulación y la edición (A9).
 */
public record WarehousePurchaseInvoiceResponse(
    Integer id,
    WarehouseInvoiceSupplierRef supplier,
    String invoiceNumber,
    LocalDate invoiceDate,
    @Schema(nullable = true) String guideNumber,
    WarehouseInvoiceCurrencyRef currency,
    @Schema(nullable = true) String observations,
    List<WarehouseInvoiceItemResponse> items,
    @Schema(description = "Σ items.subtotal en la moneda de la factura, derivado en código") BigDecimal total,
    WarehouseRecordStatus status,
    @Schema(nullable = true) String cancelReason,
    @Schema(nullable = true) UserResponse cancelledBy,
    @Schema(nullable = true) OffsetDateTime cancelledAt,
    @Schema(nullable = true) WarehouseEditTrace lastEdit,
    UserResponse registeredBy,
    OffsetDateTime createdAt,
    @Schema(description = "Fuente de la versión; usar el header ETag opaco en If-Match, NO este valor") OffsetDateTime updatedAt
) {}
