package com.scaramutti.tms.workers.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * Un trabajador completo. No reusa la respuesta del listado: esa tiene cuatro campos para un
 * combobox y esta tiene quince, con el documento y el telefono, que el combobox nunca muestra
 * y por eso el detalle lo leen menos roles.
 *
 * <p>{@code hasUser} no es una columna: se deriva de que exista una fila de usuario apuntando
 * a este trabajador. Es verdadero tambien cuando ese usuario esta inactivo, porque desactivar
 * un trabajador apaga su usuario pero no lo borra, y la pantalla necesita saber que la cuenta
 * sigue ahi.
 *
 * <p>{@code createdBy} y {@code updatedBy} son nulos en las filas anteriores a este modulo:
 * se migraron por SQL del sistema anterior y nadie sabe quien las cargo.
 */
public record WorkerDetailResponse(
    @Schema(example = "8") Integer id,
    @Schema(example = "Juan") String firstName,
    @Schema(example = "Pérez Huamán") String lastName,
    DocumentTypeResponse documentType,
    @Schema(example = "45678912") String documentNumber,
    @Schema(example = "987654321", nullable = true) String phone,
    RoleResponse role,
    @Schema(description = "Cuando entro a la empresa, distinta de cuando se grabo la fila",
        example = "2024-03-01") LocalDate hireDate,
    @Schema(example = "true") Boolean isActive,
    OffsetDateTime createdAt,
    @Schema(nullable = true) WorkerUserRef createdBy,
    OffsetDateTime updatedAt,
    @Schema(nullable = true) WorkerUserRef updatedBy,
    @Schema(description = "La ficha de conductor, o nulo si el trabajador no tiene", nullable = true)
        WorkerDriverProfileResponse driver,
    @Schema(description = "Si existe un usuario para este trabajador, activo o no", example = "false")
        Boolean hasUser
) {}
