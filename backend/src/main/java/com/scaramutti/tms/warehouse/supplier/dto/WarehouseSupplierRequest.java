package com.scaramutti.tms.warehouse.supplier.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

public record WarehouseSupplierRequest(

    @Schema(description = "Razón social o nombre comercial del proveedor", example = "REPUESTOS DIÉSEL S.A.C.", minLength = 3, maxLength = 200)
    @NotBlank
    @Size(min = 3, max = 200)
    String name,

    @Schema(description = "RUC del proveedor (11 dígitos, opcional)", example = "20512345678", pattern = "^\\d{11}$", nullable = true)
    @Pattern(regexp = "^\\d{11}$")
    String ruc,

    @Schema(description = "Teléfono de contacto (9 dígitos, opcional)", example = "014567890", pattern = "^\\d{9}$", nullable = true)
    @Pattern(regexp = "^\\d{9}$")
    String phone,

    @Schema(description = "Nombre del contacto principal (opcional)", example = "Marco Salazar", maxLength = 100, nullable = true)
    @Size(max = 100)
    String contactName
) {}
