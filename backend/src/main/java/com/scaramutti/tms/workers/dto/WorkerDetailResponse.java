package com.scaramutti.tms.workers.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * Un trabajador completo. No reusa la respuesta del listado: esa tiene cuatro campos para un
 * combobox y esta es la ficha entera, con el documento, que el combobox nunca muestra y por eso el
 * detalle lo leen menos roles.
 *
 * <p>Sin materno, {@code maternalLastName} viaja nulo, nunca vacio; {@code fullName} lo compone la
 * consulta, con {@code WorkerRepository.fullNameExpression}.
 *
 * <p>{@code hasUser} es {@code account} no nulo: verdadero tambien con la cuenta inactiva, porque
 * desactivar un trabajador apaga su cuenta pero no la borra, y la pantalla necesita saber que la
 * cuenta sigue ahi.
 *
 * <p>En las filas anteriores a este modulo, {@code createdBy} es el usuario admin, que las cargo
 * (nulo si no habia admin al migrar), y {@code updatedBy} es nulo hasta su primera edicion, baja
 * o reactivacion desde la aplicacion.
 */
public record WorkerDetailResponse(
    @Schema(example = "8") Integer id,
    @Schema(example = "Juan") String firstName,
    @Schema(example = "Pérez") String paternalLastName,
    @Schema(example = "Huamán", nullable = true) String maternalLastName,
    @Schema(example = "Juan Pérez Huamán") String fullName,
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
    @Schema(description = "Su cuenta del sistema, activa o no, o nulo si no tiene", nullable = true)
        WorkerAccountRef account,
    @Schema(description = "Si existe un usuario para este trabajador, activo o no", example = "false")
        Boolean hasUser
) {}
