package com.scaramutti.tms.auth.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

public record UserResponse(
    @Schema(description = "ID del usuario", example = "2")
    Integer id,

    @Schema(description = "Username (unico)", example = "lcampos")
    String username,

    @Schema(description = "Nombre completo (snapshot del Worker)", example = "Luraidis Campos")
    String fullName,

    @Schema(description = "Cargo (Worker.position)", example = "Ejecutiva de Ventas")
    String position,

    @Schema(description = "Rol del sistema", example = "VENDEDOR")
    String role,

    @Schema(description = "Indica si el usuario esta activo", example = "true")
    Boolean isActive
) {}
