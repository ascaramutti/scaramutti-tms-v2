package com.scaramutti.tms.users.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.time.OffsetDateTime;

/**
 * Una cuenta completa: lo de la fila de la lista, el trabajador con su nombre en partes y la
 * autoria. Sin el hash ni la version de las sesiones. {@code createdBy} es nulo: hoy ninguna cuenta
 * registra quien la creo. {@code updatedBy} es nulo hasta el primer cambio que la firma.
 */
public record UserAccountDetailResponse(
    @Schema(example = "12") Integer id,
    @Schema(example = "jperez") String username,
    UserAccountWorkerDetailRef worker,
    UserAccountRoleResponse role,
    Boolean isActive,
    OffsetDateTime createdAt,
    @Schema(nullable = true) UserAccountAuthorRef createdBy,
    OffsetDateTime updatedAt,
    @Schema(nullable = true) UserAccountAuthorRef updatedBy
) {}
