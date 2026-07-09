package com.scaramutti.tms.warehouse.unitofmeasure.service;

import com.scaramutti.tms.shared.entity.UnitOfMeasure;
import com.scaramutti.tms.shared.repository.UnitOfMeasureRepository;
import com.scaramutti.tms.warehouse.unitofmeasure.dto.WarehouseUnitOfMeasureResponse;
import com.scaramutti.tms.warehouse.unitofmeasure.mapper.WarehouseUnitOfMeasureServiceMapper;
import com.scaramutti.tms.warehouse.unitofmeasure.service.cmd.ListWarehouseUnitsOfMeasureQuery;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests del branching del service (isActive null/true/false). Calco
 * exacto de CurrencyServiceTest — catalogo GET-only sin duplicados que
 * validar. Los integration tests cubren el end-to-end con BD real.
 */
@ExtendWith(MockitoExtension.class)
class WarehouseUnitOfMeasureServiceTest {

    @Mock UnitOfMeasureRepository unitOfMeasureRepository;
    @Mock WarehouseUnitOfMeasureServiceMapper warehouseUnitOfMeasureServiceMapper;
    @InjectMocks WarehouseUnitOfMeasureService warehouseUnitOfMeasureService;

    @Test
    void listUnitsOfMeasure_withNullFilter_callsListAllOrderedByCode() {
        ListWarehouseUnitsOfMeasureQuery query = new ListWarehouseUnitsOfMeasureQuery(null);
        when(unitOfMeasureRepository.listAllOrderedByCode()).thenReturn(List.of());
        when(warehouseUnitOfMeasureServiceMapper.toWarehouseUnitOfMeasureResponseList(any())).thenReturn(List.of());

        warehouseUnitOfMeasureService.listUnitsOfMeasure(query);

        verify(unitOfMeasureRepository).listAllOrderedByCode();
        verify(unitOfMeasureRepository, never()).listByIsActiveOrderedByCode(anyBoolean());
    }

    @Test
    void listUnitsOfMeasure_withTrueFilter_callsListByIsActiveOrderedByCodeWithTrue() {
        ListWarehouseUnitsOfMeasureQuery query = new ListWarehouseUnitsOfMeasureQuery(true);
        when(unitOfMeasureRepository.listByIsActiveOrderedByCode(true)).thenReturn(List.of());
        when(warehouseUnitOfMeasureServiceMapper.toWarehouseUnitOfMeasureResponseList(any())).thenReturn(List.of());

        warehouseUnitOfMeasureService.listUnitsOfMeasure(query);

        verify(unitOfMeasureRepository).listByIsActiveOrderedByCode(true);
        verify(unitOfMeasureRepository, never()).listAllOrderedByCode();
    }

    @Test
    void listUnitsOfMeasure_withFalseFilter_callsListByIsActiveOrderedByCodeWithFalse() {
        ListWarehouseUnitsOfMeasureQuery query = new ListWarehouseUnitsOfMeasureQuery(false);
        when(unitOfMeasureRepository.listByIsActiveOrderedByCode(false)).thenReturn(List.of());
        when(warehouseUnitOfMeasureServiceMapper.toWarehouseUnitOfMeasureResponseList(any())).thenReturn(List.of());

        warehouseUnitOfMeasureService.listUnitsOfMeasure(query);

        verify(unitOfMeasureRepository).listByIsActiveOrderedByCode(false);
        verify(unitOfMeasureRepository, never()).listAllOrderedByCode();
    }

    @Test
    void listUnitsOfMeasure_returnsResultFromMapper() {
        ListWarehouseUnitsOfMeasureQuery query = new ListWarehouseUnitsOfMeasureQuery(null);
        UnitOfMeasure gal = new UnitOfMeasure();
        gal.id = 1;
        gal.code = "GAL";
        gal.name = "Galón";
        gal.isActive = true;
        List<UnitOfMeasure> entitiesFromRepository = List.of(gal);

        WarehouseUnitOfMeasureResponse mappedResponse = new WarehouseUnitOfMeasureResponse(1, "GAL", "Galón", true);
        List<WarehouseUnitOfMeasureResponse> mappedResponses = List.of(mappedResponse);

        when(unitOfMeasureRepository.listAllOrderedByCode()).thenReturn(entitiesFromRepository);
        when(warehouseUnitOfMeasureServiceMapper.toWarehouseUnitOfMeasureResponseList(entitiesFromRepository))
            .thenReturn(mappedResponses);

        List<WarehouseUnitOfMeasureResponse> result = warehouseUnitOfMeasureService.listUnitsOfMeasure(query);

        assertEquals(mappedResponses, result);
    }
}
