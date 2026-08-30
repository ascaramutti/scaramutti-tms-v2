package com.scaramutti.tms.sharedcatalogs.fleetunit.dto;

import com.scaramutti.tms.warehouse.model.FleetUnitKind;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Referencia mínima a una unidad de flota. {@code id} es el id de la tabla del SUBTIPO; la
 * dirección completa es el par {@code (kind, id)}. {@code plate} = placa de 6 caracteres sin
 * guión (estándar del dueño; la presentación lo agrega).
 *
 * <p>Vive en el paquete del catálogo compartido y no en el de un módulo porque la usan dos:
 * almacén nombra con ella la unidad destino de un retiro, y operaciones el tracto y la carreta
 * de un viaje. Es el mismo dato con el mismo significado, así que duplicar el record dejaría
 * dos definiciones de la misma cosa que después se separan sin que nada falle.
 *
 * <p>El enum del subtipo sigue en {@code warehouse/model/}: ahí lo dejó el módulo que lo definió
 * y desde este mismo paquete ya lo importa {@link FleetUnitResponse}, así que moverlo también
 * sería una mudanza aparte, sin nada que la pida hoy.
 */
public record FleetUnitRef(
    FleetUnitKind kind,
    @Schema(example = "5") Integer id,
    @Schema(example = "ABC123", minLength = 6, maxLength = 6) String plate
) {}
