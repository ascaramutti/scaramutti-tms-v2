package com.scaramutti.tms.sharedcatalogs.fleetunit.dto;

import com.scaramutti.tms.sharedcatalogs.model.FleetResourceStatus;
import com.scaramutti.tms.warehouse.model.FleetUnitKind;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Unidad de flota unificada (GET /fleet-units): union en codigo de los tres subtipos
 * DISYUNTOS ({@code public.tractors}/{@code trailers}/{@code escort_vehicles}). {@code id}
 * es el id de la tabla del SUBTIPO; la direccion completa es el par {@code (kind, id)}.
 *
 * <p>Record PLANO (no anida {@code FleetUnitRef}): el contrato lo modela con {@code allOf}
 * (FleetUnitRef + brand/model/isActive), que aplana los campos. {@code brand}/{@code model}
 * pueden ser null: las carretas ({@code trailers}) no tienen esas columnas. Reusa el enum de
 * dominio {@link FleetUnitKind} (mismo que el {@code FleetUnitRef} del retiro).
 *
 * <p>{@code status} es la disponibilidad con la que operaciones elige tracto y carreta al
 * asignar un viaje. Viaja en null en las escoltas: no participan de esa asignacion (solo son
 * unidad destino de un retiro de almacen), asi que su estado no significa nada aca.
 */
public record FleetUnitResponse(
    FleetUnitKind kind,
    @Schema(example = "5") Integer id,
    @Schema(example = "ABC123", minLength = 6, maxLength = 6) String plate,
    @Schema(example = "Volvo", nullable = true) String brand,
    @Schema(example = "FH", nullable = true) String model,
    @Schema(nullable = true) FleetResourceStatus status,
    Boolean isActive
) {}
