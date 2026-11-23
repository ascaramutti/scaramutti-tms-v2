package com.scaramutti.tms.users.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Una fila de la lista de cuentas, sin el hash ni la version de las sesiones. {@code role} es el
 * cargo del trabajador: la cuenta no tiene rol propio.
 */
public record UserAccountSummaryResponse(
    @Schema(example = "12") Integer id,
    @Schema(example = "jperez") String username,
    UserAccountWorkerRef worker,
    UserAccountRoleResponse role,
    Boolean isActive
) {}
