package com.scaramutti.tms.users.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Quien creo o modifico una cuenta, con la forma del autor de un trabajador: el cargo es el de SU
 * trabajador, para que la misma persona se presente igual en toda la aplicacion.
 */
public record UserAccountAuthorRef(
    @Schema(example = "3") Integer id,
    @Schema(example = "operations_manager") String username,
    @Schema(example = "Juan Pérez Huamán") String fullName,
    @Schema(example = "Gerente de Operaciones") String position
) {}
