package com.scaramutti.tms.sharedcatalogs.fleetunit.service;

import com.scaramutti.tms.shared.repository.FleetUnitRepository;
import com.scaramutti.tms.sharedcatalogs.fleetunit.dto.FleetUnitResponse;
import com.scaramutti.tms.sharedcatalogs.fleetunit.mapper.FleetUnitServiceMapper;
import com.scaramutti.tms.sharedcatalogs.fleetunit.service.cmd.ListFleetUnitsQuery;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;

/**
 * Listado unificado de unidades de flota (GET /fleet-units). Read-only, sin
 * {@code @Transactional}. La union de los tres subtipos, los filtros y el orden los resuelve
 * {@link FleetUnitRepository#search}; el shaping al response vive en el mapper.
 */
@ApplicationScoped
public class FleetUnitService {

    @Inject FleetUnitRepository fleetUnitRepository;
    @Inject FleetUnitServiceMapper fleetUnitServiceMapper;

    public List<FleetUnitResponse> listFleetUnits(ListFleetUnitsQuery query) {
        return fleetUnitServiceMapper.toFleetUnitResponseList(
            fleetUnitRepository.search(query.kind(), query.isActive())
        );
    }
}
