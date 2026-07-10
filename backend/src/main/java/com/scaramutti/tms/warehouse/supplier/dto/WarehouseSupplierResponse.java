package com.scaramutti.tms.warehouse.supplier.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.time.OffsetDateTime;

public record WarehouseSupplierResponse(
    @Schema(description = "ID interno", example = "1")
    Integer id,

    @Schema(description = "Razón social o nombre comercial del proveedor", example = "REPUESTOS DIÉSEL S.A.C.", maxLength = 200)
    String name,

    @Schema(description = "RUC del proveedor", example = "20512345678", pattern = "^\\d{11}$", nullable = true)
    String ruc,

    @Schema(description = "Teléfono de contacto", example = "014567890", pattern = "^\\d{9}$", nullable = true)
    String phone,

    @Schema(description = "Nombre del contacto principal", example = "Juan Ramos", maxLength = 100, nullable = true)
    String contactName,

    @Schema(description = "Indica si el proveedor está activo", example = "true")
    Boolean isActive,

    @Schema(description = "Fecha de registro")
    OffsetDateTime createdAt
) {}
