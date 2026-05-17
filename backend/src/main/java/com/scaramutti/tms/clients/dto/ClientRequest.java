package com.scaramutti.tms.clients.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

public record ClientRequest(
    @Schema(description = "Razón social o nombre comercial del cliente", example = "Acme Corp SAC", minLength = 1, maxLength = 200)
    @NotBlank
    @Size(min = 1, max = 200)
    String name,

    @Schema(description = "RUC del cliente (11 dígitos)", example = "20123456789", pattern = "^\\d{11}$")
    @NotBlank
    @Pattern(regexp = "^\\d{11}$")
    String ruc,

    @Schema(description = "Teléfono de contacto (9 dígitos, opcional)", example = "987654321", pattern = "^\\d{9}$", nullable = true)
    @Pattern(regexp = "^\\d{9}$")
    String phone,

    @Schema(description = "Nombre del contacto principal (opcional)", example = "Juan Pérez", maxLength = 100, nullable = true)
    @Size(max = 100)
    String contactName
) {}
