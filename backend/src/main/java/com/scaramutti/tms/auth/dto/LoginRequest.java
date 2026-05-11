package com.scaramutti.tms.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

public record LoginRequest(
    @Schema(description = "Nombre de usuario (alfanumerico, puntos, guiones, guion bajo)", example = "lcampos")
    @NotBlank
    @Size(min = 3, max = 50)
    @Pattern(regexp = "^[a-zA-Z0-9._-]+$",
             message = "solo letras, numeros, puntos, guiones y guion bajo")
    String username,

    @Schema(description = "Contrasena del usuario", example = "Sales1234")
    @NotBlank
    @Size(min = 8, max = 100)
    String password
) {}
