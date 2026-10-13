package com.scaramutti.tms.auth.dto;

import com.scaramutti.tms.auth.validation.FitsBcrypt;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

public record LoginRequest(
    @Schema(description = "Nombre de usuario (alfanumerico, puntos, guiones, guion bajo)", example = "sales")
    @NotBlank
    @Size(min = 3, max = 50)
    @Pattern(regexp = "^[a-zA-Z0-9._-]+$",
             message = "solo letras, numeros, puntos, guiones y guion bajo")
    String username,

    @Schema(description = "Contrasena del usuario: hasta 72 bytes UTF-8", example = "Sales1234")
    @NotBlank
    @Size(min = 8, message = "debe tener al menos 8 caracteres")
    @FitsBcrypt
    String password
) {}
