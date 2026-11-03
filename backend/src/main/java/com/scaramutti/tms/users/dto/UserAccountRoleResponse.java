package com.scaramutti.tms.users.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * El cargo de una cuenta, con la forma del catalogo de cargos. {@code driverProfile} va como el texto
 * de la columna, que su restriccion cierra a los valores del catalogo: el tipo de esos valores es de
 * trabajadores, que este modulo no importa.
 */
public record UserAccountRoleResponse(
    @Schema(example = "operations_manager") String name,
    @Schema(example = "Gerente de Operaciones") String description,
    @Schema(example = "3") Integer level,
    @Schema(example = "true") Boolean canLogin,
    @Schema(example = "NONE") String driverProfile
) {}
