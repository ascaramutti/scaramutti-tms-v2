package com.scaramutti.tms.sharedcatalogs.fleetunit.service.cmd;

import com.scaramutti.tms.warehouse.model.FleetUnitKind;

/**
 * Filtros del listado de unidades de flota (GET /fleet-units), agrupados desde la capa
 * REST. {@code kind} nulo = los tres subtipos; {@code isActive} nulo = activas e inactivas.
 */
public record ListFleetUnitsQuery(
    FleetUnitKind kind,
    Boolean isActive
) {}
