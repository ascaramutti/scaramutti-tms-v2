package com.scaramutti.tms.auth.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * El usuario de la sesion (ingreso, renovacion y {@code /auth/me}): los campos de
 * {@link UserResponse} mas el id de su trabajador, para que la pantalla reconozca la ficha propia.
 * Es un tipo aparte porque {@code UserResponse} es tambien el autor que se embebe en cotizaciones
 * y almacen, y ahi el id del trabajador no tiene que salir.
 */
public record SessionUserResponse(
    @Schema(description = "ID del usuario", example = "2")
    Integer id,

    @Schema(description = "Username (unico)", example = "sales")
    String username,

    @Schema(description = "Nombre completo del trabajador", example = "Valeria Torres")
    String fullName,

    @Schema(description = "Cargo: el nombre visible del rol del trabajador", example = "Ejecutivo de Ventas")
    String position,

    @Schema(description = "Rol del sistema", example = "sales")
    String role,

    @Schema(description = "Indica si el usuario esta activo", example = "true")
    Boolean isActive,

    @Schema(description = "ID del trabajador de la cuenta; toda cuenta tiene uno", example = "29119")
    Integer workerId
) {}
