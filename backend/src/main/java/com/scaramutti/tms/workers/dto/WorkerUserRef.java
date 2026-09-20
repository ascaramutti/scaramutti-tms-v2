package com.scaramutti.tms.workers.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Quien creo o modifico un trabajador. El cargo es el del rol de SU trabajador, igual que
 * en el token de sesion, para que la misma persona se presente igual en toda la aplicacion.
 */
public record WorkerUserRef(
    @Schema(example = "3") Integer id,
    @Schema(example = "operations_manager") String username,
    @Schema(example = "Juan Pérez Huamán") String fullName,
    @Schema(example = "Gerente de Operaciones") String position
) {}
