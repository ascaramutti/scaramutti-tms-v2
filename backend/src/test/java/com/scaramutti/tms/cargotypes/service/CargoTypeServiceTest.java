package com.scaramutti.tms.cargotypes.service;

import com.scaramutti.tms.cargotypes.dto.CargoTypeResponse;
import com.scaramutti.tms.cargotypes.mapper.CargoTypeServiceMapper;
import com.scaramutti.tms.cargotypes.service.cmd.ListCargoTypesQuery;
import com.scaramutti.tms.shared.dto.PageResponse;
import com.scaramutti.tms.shared.entity.CargoType;
import com.scaramutti.tms.shared.repository.CargoTypeRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests del service. Calco del listClients de ClientServiceTest.
 * Aisla la logica del IO de BD usando mocks. Los integration tests cubren
 * el end-to-end con BD real.
 */
@ExtendWith(MockitoExtension.class)
class CargoTypeServiceTest {

    @Mock CargoTypeRepository cargoTypeRepository;
    @Mock CargoTypeServiceMapper cargoTypeServiceMapper;
    @InjectMocks CargoTypeService cargoTypeService;

    @Test
    void listCargoTypes_passesQueryToRepoUnchanged() {
        // El service no transforma el Query — solo lo pasa al repo (search y count).
        ListCargoTypesQuery listCargoTypesQuery = new ListCargoTypesQuery("EXCAVADORA", true, 0, 20);
        when(cargoTypeRepository.searchPaged(eq(listCargoTypesQuery))).thenReturn(List.of());
        when(cargoTypeRepository.countSearch(eq(listCargoTypesQuery))).thenReturn(0L);

        PageResponse<CargoTypeResponse> response = cargoTypeService.listCargoTypes(listCargoTypesQuery);

        verify(cargoTypeRepository).searchPaged(listCargoTypesQuery);
        verify(cargoTypeRepository).countSearch(listCargoTypesQuery);
        assertTrue(response.content().isEmpty());
    }

    @Test
    void listCargoTypes_withNullQAndNullIsActive_passesNullsToRepo() {
        ListCargoTypesQuery listCargoTypesQuery = new ListCargoTypesQuery(null, null, 0, 20);
        when(cargoTypeRepository.searchPaged(eq(listCargoTypesQuery))).thenReturn(List.of());
        when(cargoTypeRepository.countSearch(eq(listCargoTypesQuery))).thenReturn(0L);

        cargoTypeService.listCargoTypes(listCargoTypesQuery);

        verify(cargoTypeRepository).searchPaged(listCargoTypesQuery);
    }

    @Test
    void listCargoTypes_withIsActiveTrue_passesFilterInQuery() {
        ListCargoTypesQuery listCargoTypesQuery = new ListCargoTypesQuery(null, true, 0, 20);
        when(cargoTypeRepository.searchPaged(eq(listCargoTypesQuery))).thenReturn(List.of());
        when(cargoTypeRepository.countSearch(eq(listCargoTypesQuery))).thenReturn(0L);

        cargoTypeService.listCargoTypes(listCargoTypesQuery);

        verify(cargoTypeRepository).searchPaged(listCargoTypesQuery);
    }

    @Test
    void listCargoTypes_withIsActiveFalse_passesFilterInQuery() {
        ListCargoTypesQuery listCargoTypesQuery = new ListCargoTypesQuery(null, false, 0, 20);
        when(cargoTypeRepository.searchPaged(eq(listCargoTypesQuery))).thenReturn(List.of());
        when(cargoTypeRepository.countSearch(eq(listCargoTypesQuery))).thenReturn(0L);

        cargoTypeService.listCargoTypes(listCargoTypesQuery);

        verify(cargoTypeRepository).searchPaged(listCargoTypesQuery);
    }

    @Test
    void listCargoTypes_mapsRepoPageToPageResponse_correctMeta() {
        CargoType c1 = newCargoType(1, "A");
        CargoType c2 = newCargoType(2, "B");
        CargoType c3 = newCargoType(3, "C");

        ListCargoTypesQuery listCargoTypesQuery = new ListCargoTypesQuery(null, null, 1, 5);
        when(cargoTypeRepository.searchPaged(eq(listCargoTypesQuery))).thenReturn(List.of(c1, c2, c3));
        when(cargoTypeRepository.countSearch(eq(listCargoTypesQuery))).thenReturn(11L);

        List<CargoTypeResponse> mappedList = List.of(
            newResponse(1, "A"),
            newResponse(2, "B"),
            newResponse(3, "C")
        );
        when(cargoTypeServiceMapper.toCargoTypeResponseList(any())).thenReturn(mappedList);

        PageResponse<CargoTypeResponse> response = cargoTypeService.listCargoTypes(listCargoTypesQuery);

        assertEquals(1, response.page());
        assertEquals(5, response.size());
        assertEquals(11L, response.totalElements());
        assertEquals(3, response.totalPages());      // ceil(11/5) = 3
        assertEquals(3, response.numberOfElements());
        assertFalse(response.first());
        assertFalse(response.last());
        assertFalse(response.empty());
        assertEquals(3, response.content().size());
        verify(cargoTypeServiceMapper, times(1)).toCargoTypeResponseList(any());
    }

    @Test
    void listCargoTypes_emptyRepoResult_returnsEmptyPageWithFirstAndLastTrue() {
        when(cargoTypeRepository.searchPaged(any(ListCargoTypesQuery.class))).thenReturn(List.of());
        when(cargoTypeRepository.countSearch(any(ListCargoTypesQuery.class))).thenReturn(0L);
        when(cargoTypeServiceMapper.toCargoTypeResponseList(any())).thenReturn(List.of());

        PageResponse<CargoTypeResponse> response = cargoTypeService.listCargoTypes(
            new ListCargoTypesQuery("NADA", null, 0, 20)
        );

        assertTrue(response.content().isEmpty());
        assertEquals(0L, response.totalElements());
        assertEquals(0, response.totalPages());
        assertTrue(response.first());
        assertTrue(response.last());
        assertTrue(response.empty());
    }

    @Test
    void listCargoTypes_firstPageOfMany_setsFirstTrueLastFalse() {
        when(cargoTypeRepository.searchPaged(any(ListCargoTypesQuery.class))).thenReturn(List.of());
        when(cargoTypeRepository.countSearch(any(ListCargoTypesQuery.class))).thenReturn(50L);
        when(cargoTypeServiceMapper.toCargoTypeResponseList(any())).thenReturn(List.of());

        PageResponse<CargoTypeResponse> response = cargoTypeService.listCargoTypes(
            new ListCargoTypesQuery(null, null, 0, 20)
        );

        assertTrue(response.first());
        assertFalse(response.last());
        assertEquals(3, response.totalPages()); // ceil(50/20) = 3
    }

    @Test
    void listCargoTypes_lastPageOfMany_setsFirstFalseLastTrue() {
        when(cargoTypeRepository.searchPaged(any(ListCargoTypesQuery.class))).thenReturn(List.of());
        when(cargoTypeRepository.countSearch(any(ListCargoTypesQuery.class))).thenReturn(50L);
        when(cargoTypeServiceMapper.toCargoTypeResponseList(any())).thenReturn(List.of());

        PageResponse<CargoTypeResponse> response = cargoTypeService.listCargoTypes(
            new ListCargoTypesQuery(null, null, 2, 20)
        );

        assertFalse(response.first());
        assertTrue(response.last());
    }

    @Test
    void listCargoTypes_singlePageResult_setsBothFirstAndLastTrue() {
        CargoType c = newCargoType(1, "ONLY");
        when(cargoTypeRepository.searchPaged(any(ListCargoTypesQuery.class))).thenReturn(List.of(c));
        when(cargoTypeRepository.countSearch(any(ListCargoTypesQuery.class))).thenReturn(1L);
        when(cargoTypeServiceMapper.toCargoTypeResponseList(any())).thenReturn(
            List.of(newResponse(1, "ONLY"))
        );

        PageResponse<CargoTypeResponse> response = cargoTypeService.listCargoTypes(
            new ListCargoTypesQuery(null, null, 0, 20)
        );

        assertTrue(response.first());
        assertTrue(response.last());
        assertEquals(1, response.numberOfElements());
        assertFalse(response.empty());
    }

    @Test
    void listCargoTypes_pageOverflow_emptyContentButLastTrue() {
        // page=99 con totalElements=2 → content vacio, totalPages=1, last=true.
        when(cargoTypeRepository.searchPaged(any(ListCargoTypesQuery.class))).thenReturn(List.of());
        when(cargoTypeRepository.countSearch(any(ListCargoTypesQuery.class))).thenReturn(2L);
        when(cargoTypeServiceMapper.toCargoTypeResponseList(any())).thenReturn(List.of());

        PageResponse<CargoTypeResponse> response = cargoTypeService.listCargoTypes(
            new ListCargoTypesQuery(null, null, 99, 20)
        );

        assertTrue(response.content().isEmpty());
        assertEquals(2L, response.totalElements());
        assertEquals(1, response.totalPages());
        assertFalse(response.first());
        assertTrue(response.last());
        assertTrue(response.empty());
    }

    // ---------- Helpers -------------------------------------------------------

    private CargoType newCargoType(int id, String name) {
        CargoType c = new CargoType();
        c.id = id;
        c.name = name;
        c.standardWeight = BigDecimal.ONE;
        c.isActive = true;
        return c;
    }

    private CargoTypeResponse newResponse(int id, String name) {
        return new CargoTypeResponse(id, name, null, BigDecimal.ONE, null, null, null, true);
    }
}
