package com.scaramutti.tms.shared.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Trabajador de {@code public.workers} (catálogo compartido con v1, read-only desde v2).
 * Vive en {@code shared/dto/} porque lo comparten dos módulos: el listado de {@code workers}
 * y el retiro de almacén, que lo anida como {@code receivedBy}.
 */
public record WorkerResponse(
    @Schema(example = "8") Integer id,
    @Schema(example = "Juan Pérez Huamán") String fullName,
    @Schema(example = "Mecánico", nullable = true) String position,
    Boolean isActive
) {}
