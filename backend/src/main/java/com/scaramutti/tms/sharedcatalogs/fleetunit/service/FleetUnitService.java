package com.scaramutti.tms.sharedcatalogs.fleetunit.service;

import com.scaramutti.tms.shared.repository.FleetUnitRepository;
import com.scaramutti.tms.shared.repository.FleetUnitRepository.FleetUnitRow;
import com.scaramutti.tms.sharedcatalogs.fleetunit.dto.FleetUnitResponse;
import com.scaramutti.tms.sharedcatalogs.fleetunit.service.cmd.ListFleetUnitsQuery;
import com.scaramutti.tms.warehouse.model.FleetUnitKind;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;

/**
 * Listado unificado de unidades de flota (GET /fleet-units). Read-only, sin
 * {@code @Transactional}. La union de los tres subtipos, los filtros y el orden los resuelve
 * {@link FleetUnitRepository#search}; aca solo se mapea la fila al response (el {@code kind}
 * viaja como String desde la union y se reconstruye al enum de dominio).
 */
@ApplicationScoped
public class FleetUnitService {

    @Inject FleetUnitRepository fleetUnitRepository;

    public List<FleetUnitResponse> listFleetUnits(ListFleetUnitsQuery query) {
        return fleetUnitRepository.search(query.kind(), query.isActive()).stream()
            .map(this::toFleetUnitResponse)
            .toList();
    }

    private FleetUnitResponse toFleetUnitResponse(FleetUnitRow row) {
        return new FleetUnitResponse(
            FleetUnitKind.valueOf(row.kind()),
            row.id(),
            row.plate(),
            row.brand(),
            row.model(),
            row.isActive()
        );
    }
}
