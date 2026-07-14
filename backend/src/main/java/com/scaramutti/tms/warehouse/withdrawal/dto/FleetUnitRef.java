package com.scaramutti.tms.warehouse.withdrawal.dto;

import com.scaramutti.tms.warehouse.model.FleetUnitKind;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Referencia mínima a la unidad destino de un retiro. {@code id} es el id de la tabla del
 * SUBTIPO; la dirección completa es el par {@code (kind, id)}. {@code plate} = placa de 6
 * caracteres sin guión (estándar del dueño; la presentación lo agrega).
 */
public record FleetUnitRef(
    FleetUnitKind kind,
    @Schema(example = "5") Integer id,
    @Schema(example = "ABC123", minLength = 6, maxLength = 6) String plate
) {}
