package com.scaramutti.tms.clients.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.time.OffsetDateTime;

public record ClientResponse(
    @Schema(description = "ID interno", example = "1")
    Integer id,

    @Schema(description = "Razón social o nombre comercial (siempre en mayúsculas)", example = "ACME CORP SAC")
    String name,

    @Schema(description = "RUC del cliente (11 dígitos)", example = "20123456789", pattern = "^\\d{11}$")
    String ruc,

    @Schema(description = "Teléfono de contacto (9 dígitos, opcional)", example = "987654321", nullable = true)
    String phone,

    @Schema(description = "Nombre del contacto principal (opcional)", example = "Juan Pérez", nullable = true)
    String contactName,

    @Schema(description = "Indica si el cliente está activo", example = "true")
    Boolean isActive,

    @Schema(description = "Fecha de creación (UTC)", example = "2026-05-16T12:00:00Z")
    OffsetDateTime createdAt
) {}
