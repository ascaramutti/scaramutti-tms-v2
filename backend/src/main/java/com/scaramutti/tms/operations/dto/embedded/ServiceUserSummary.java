package com.scaramutti.tms.operations.dto.embedded;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Vista del usuario para embeber en el servicio y en su bitacora: quien registro, quien
 * escribio cada linea. Deja fuera el rol y el estado de la cuenta, que pertenecen al master de
 * usuarios y no aportan al contexto del viaje.
 */
public record ServiceUserSummary(

    @Schema(description = "ID interno del usuario", example = "4")
    Integer id,

    @Schema(description = "Usuario de acceso", example = "lcampos")
    String username,

    @Schema(description = "Nombre completo", example = "Valeria Torres")
    String fullName,

    @Schema(nullable = true, description = "Cargo del trabajador", example = "Ejecutiva de Ventas")
    String position
) {}
