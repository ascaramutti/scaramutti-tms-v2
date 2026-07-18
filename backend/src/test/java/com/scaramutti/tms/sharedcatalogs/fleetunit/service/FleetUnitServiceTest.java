package com.scaramutti.tms.sharedcatalogs.fleetunit.service;

import com.scaramutti.tms.sharedcatalogs.fleetunit.mapper.FleetUnitServiceMapper;
import com.scaramutti.tms.shared.repository.FleetUnitRepository;
import com.scaramutti.tms.shared.repository.FleetUnitRepository.FleetUnitRow;
import com.scaramutti.tms.sharedcatalogs.fleetunit.dto.FleetUnitResponse;
import com.scaramutti.tms.sharedcatalogs.fleetunit.service.cmd.ListFleetUnitsQuery;
import com.scaramutti.tms.warehouse.model.FleetUnitKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mapstruct.factory.Mappers;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests del service de flota. Cubre la delegacion del filtro al repo y el mapeo de la
 * fila (kind String de la union a enum de dominio; brand/model null de carretas). El SQL
 * real del UNION lo cubren los integration tests (FleetUnitsResourceTest).
 */
@ExtendWith(MockitoExtension.class)
class FleetUnitServiceTest {

    @Mock FleetUnitRepository fleetUnitRepository;
    @InjectMocks FleetUnitService fleetUnitService;

    // El mapper es un colaborador REAL (impl generada por MapStruct), no un mock:
    // estos tests cubren justamente el shaping fila a response (incluido el kind).
    @BeforeEach
    void wireRealMapper() {
        fleetUnitService.fleetUnitServiceMapper = Mappers.getMapper(FleetUnitServiceMapper.class);
    }

    @Test
    void listFleetUnits_delegatesFilterAndMapsKindToEnum() {
        when(fleetUnitRepository.search(FleetUnitKind.TRACTOR, true))
            .thenReturn(List.of(new FleetUnitRow("TRACTOR", 5, "ABC123", "Volvo", "FH", true)));

        List<FleetUnitResponse> result =
            fleetUnitService.listFleetUnits(new ListFleetUnitsQuery(FleetUnitKind.TRACTOR, true));

        verify(fleetUnitRepository).search(FleetUnitKind.TRACTOR, true);
        assertEquals(1, result.size());
        FleetUnitResponse r = result.get(0);
        assertEquals(FleetUnitKind.TRACTOR, r.kind());
        assertEquals(5, r.id());
        assertEquals("ABC123", r.plate());
        assertEquals("Volvo", r.brand());
        assertEquals("FH", r.model());
        assertEquals(true, r.isActive());
    }

    @Test
    void listFleetUnits_trailerRowMapsBrandModelNull() {
        when(fleetUnitRepository.search(null, null))
            .thenReturn(List.of(new FleetUnitRow("TRAILER", 9, "XYZ789", null, null, true)));

        List<FleetUnitResponse> result =
            fleetUnitService.listFleetUnits(new ListFleetUnitsQuery(null, null));

        FleetUnitResponse r = result.get(0);
        assertEquals(FleetUnitKind.TRAILER, r.kind());
        assertNull(r.brand());
        assertNull(r.model());
    }

    @Test
    void listFleetUnits_emptyRepositoryResult_returnsEmptyList() {
        when(fleetUnitRepository.search(FleetUnitKind.ESCORT, null)).thenReturn(List.of());

        List<FleetUnitResponse> result =
            fleetUnitService.listFleetUnits(new ListFleetUnitsQuery(FleetUnitKind.ESCORT, null));

        assertEquals(0, result.size());
    }
}
