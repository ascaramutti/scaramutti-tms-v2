package com.scaramutti.tms.warehouse.purchaseinvoice.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Body del POST /warehouse/purchase-invoices/{id}/cancel. {@code reason} (>= 10) es el
 * motivo de anulacion (RN-WH3): va a {@code cancel_reason} y a {@code almacen.audit_logs}
 * (fila CANCELLED). Reusable por la anulacion de retiros.
 */
public record WarehouseCancelRequest(
    @Schema(example = "Factura registrada por error: correspondía a otro proveedor")
    @NotBlank @Size(min = 10, max = 500) String reason
) {}
