package com.scaramutti.tms.shared.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Trabajador de {@code public.workers}. El padrón se mantiene
 * desde esta aplicación a partir del módulo de trabajadores; este objeto es el del LISTADO,
 * que sigue siendo de lectura y alimenta los combobox.
 * Vive en {@code shared/dto/} porque lo comparten dos módulos: el listado de {@code workers}
 * y el retiro de almacén, que lo anida como {@code receivedBy}.
 */
public record WorkerResponse(
    @Schema(example = "8") Integer id,
    @Schema(example = "Juan Pérez Huamán") String fullName,
    @Schema(description = "Cargo: el nombre visible del rol", example = "Operador", nullable = true) String position,
    Boolean isActive
) {}
