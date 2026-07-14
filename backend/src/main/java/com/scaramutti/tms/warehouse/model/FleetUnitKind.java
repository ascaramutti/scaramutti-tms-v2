package com.scaramutti.tms.warehouse.model;

/**
 * Subtipo de la flota (generalización disyunta y total, decisión del dueño 2026-07-03): toda
 * unidad es exactamente uno. {@code ESCORT} = vehículo escolta ({@code public.escort_vehicles}).
 * Enum de dominio en {@code <module>/model/}; identifica el subtipo de la unidad destino de un
 * retiro junto con su id ({@code FleetUnitRef}).
 */
public enum FleetUnitKind {
    TRACTOR,
    TRAILER,
    ESCORT
}
