package com.scaramutti.tms.workers.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.time.LocalDate;

/**
 * Alta de un trabajador.
 *
 * <p>{@code role} es TEXTO y no un enum: los cargos viven en la base y se agregan por
 * migracion, asi que un enum de Java seria una segunda lista que se despega de la primera. Que
 * el valor sea un cargo vigente lo decide el servicio, con su propio codigo, y no el
 * deserializador con un 400 sin cuerpo.
 *
 * <p>{@code hireDate} es dia calendario, sin hora ni zona, y el servidor NO rechaza fechas
 * futuras: esa guarda vive solo en el cliente, a proposito, para poder corregir a mano una
 * ficha cargada tarde.
 *
 * <p>Lo que el servidor pone y este cuerpo no puede mover: si esta activo, cuando se creo,
 * quien lo creo, cuando se modifico y quien. No estan aca, y por eso no hay nada que ignorar.
 */
public record WorkerRequest(
    @NotBlank @Size(max = 100)
    @Schema(example = "Juan") String firstName,

    @NotBlank @Size(max = 100)
    @Schema(example = "Pérez Huamán") String lastName,

    @NotNull
    @Schema(example = "1") Integer documentTypeId,

    @NotBlank @Size(max = 20)
    @Schema(example = "45678912") String documentNumber,

    @Pattern(regexp = "^\\d{9}$")
    @Schema(nullable = true, example = "987654321") String phone,

    @NotBlank @Size(max = 50)
    @Schema(description = "Nombre de sistema del cargo", example = "driver") String role,

    @NotNull
    @Schema(example = "2026-09-01") LocalDate hireDate,

    @Valid
    @Schema(nullable = true) WorkerDriverProfileRequest driver
) {}
