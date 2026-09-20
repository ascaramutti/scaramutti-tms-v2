package com.scaramutti.tms.workers.dto;

import com.scaramutti.tms.workers.model.DriverProfileMode;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Rol de {@code public.roles}, que desde el mantenimiento de trabajadores es tambien el
 * cargo. Sirve al catalogo de cargos y va embebido en el detalle: asi el formulario de
 * edicion sabe el nivel y la modalidad de ficha del cargo actual sin cruzar con el catalogo.
 *
 * <p>{@code name} es texto y no el enum de roles de sesion: cuatro de los once cargos nunca
 * inician sesion y no pertenecen a ese enum, que es el tipo del rol de un usuario.
 */
public record RoleResponse(
    @Schema(description = "Nombre de sistema", example = "driver") String name,
    @Schema(description = "Nombre visible del cargo", example = "Conductor") String description,
    @Schema(description = "Nivel del organigrama, de 4 a 1", example = "1") Integer level,
    @Schema(description = "Si el rol puede tener usuario", example = "false") Boolean canLogin,
    DriverProfileMode driverProfile
) {}
