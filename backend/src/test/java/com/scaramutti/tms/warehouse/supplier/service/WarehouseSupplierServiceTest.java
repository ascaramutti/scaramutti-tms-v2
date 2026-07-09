package com.scaramutti.tms.warehouse.supplier.service;

import com.scaramutti.tms.shared.dto.PageResponse;
import com.scaramutti.tms.shared.entity.Supplier;
import com.scaramutti.tms.shared.repository.SupplierRepository;
import com.scaramutti.tms.warehouse.supplier.dto.WarehouseSupplierResponse;
import com.scaramutti.tms.warehouse.supplier.mapper.WarehouseSupplierServiceMapper;
import com.scaramutti.tms.warehouse.supplier.service.cmd.ListWarehouseSuppliersQuery;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
 * Unit tests del service. Calco de ClientServiceTest (paginacion + 2 campos
 * unicos), con la diferencia de que ruc es nullable acá (el chequeo de
 * duplicado es condicional). Los integration tests (WarehouseSuppliersResourceTest)
 * cubren RN-WH14 (tokenizado multi-palabra), que vive en SQL, no en el service.
 */
@ExtendWith(MockitoExtension.class)
class WarehouseSupplierServiceTest {

    @Mock SupplierRepository supplierRepository;
    @Mock WarehouseSupplierServiceMapper warehouseSupplierServiceMapper;
    @InjectMocks WarehouseSupplierService warehouseSupplierService;

    // ---------- listSuppliers — paginacion ---------------------------------------

    @Test
    void listSuppliers_passesQueryToRepoUnchanged() {
        ListWarehouseSuppliersQuery query = new ListWarehouseSuppliersQuery("REPUESTOS", true, 0, 20);
        when(supplierRepository.searchPaged(eq(query))).thenReturn(List.of());
        when(supplierRepository.countSearch(eq(query))).thenReturn(0L);

        PageResponse<WarehouseSupplierResponse> response = warehouseSupplierService.listSuppliers(query);

        verify(supplierRepository).searchPaged(query);
        verify(supplierRepository).countSearch(query);
        assertTrue(response.content().isEmpty());
    }

    @Test
    void listSuppliers_mapsRepoPageToPageResponse_correctMeta() {
        Supplier s1 = newSupplier(1, "A");
        Supplier s2 = newSupplier(2, "B");
        Supplier s3 = newSupplier(3, "C");

        ListWarehouseSuppliersQuery query = new ListWarehouseSuppliersQuery(null, null, 1, 5);
        when(supplierRepository.searchPaged(eq(query))).thenReturn(List.of(s1, s2, s3));
        when(supplierRepository.countSearch(eq(query))).thenReturn(11L);

        List<WarehouseSupplierResponse> mappedList = List.of(
            newResponse(1, "A"), newResponse(2, "B"), newResponse(3, "C")
        );
        when(warehouseSupplierServiceMapper.toWarehouseSupplierResponseList(any())).thenReturn(mappedList);

        PageResponse<WarehouseSupplierResponse> response = warehouseSupplierService.listSuppliers(query);

        assertEquals(1, response.page());
        assertEquals(5, response.size());
        assertEquals(11L, response.totalElements());
        assertEquals(3, response.totalPages());
        assertEquals(3, response.numberOfElements());
        assertFalse(response.first());
        assertFalse(response.last());
        verify(warehouseSupplierServiceMapper, times(1)).toWarehouseSupplierResponseList(any());
    }

    @Test
    void listSuppliers_emptyRepoResult_returnsEmptyPageWithFirstAndLastTrue() {
        when(supplierRepository.searchPaged(any())).thenReturn(List.of());
        when(supplierRepository.countSearch(any())).thenReturn(0L);
        when(warehouseSupplierServiceMapper.toWarehouseSupplierResponseList(any())).thenReturn(List.of());

        PageResponse<WarehouseSupplierResponse> response = warehouseSupplierService.listSuppliers(
            new ListWarehouseSuppliersQuery("NADA", null, 0, 20)
        );

        assertTrue(response.content().isEmpty());
        assertEquals(0, response.totalPages());
        assertTrue(response.first());
        assertTrue(response.last());
        assertTrue(response.empty());
    }

    @Test
    void listSuppliers_pageOverflow_emptyContentButLastTrue() {
        when(supplierRepository.searchPaged(any())).thenReturn(List.of());
        when(supplierRepository.countSearch(any())).thenReturn(2L);
        when(warehouseSupplierServiceMapper.toWarehouseSupplierResponseList(any())).thenReturn(List.of());

        PageResponse<WarehouseSupplierResponse> response = warehouseSupplierService.listSuppliers(
            new ListWarehouseSuppliersQuery(null, null, 99, 20)
        );

        assertTrue(response.content().isEmpty());
        assertEquals(1, response.totalPages());
        assertFalse(response.first());
        assertTrue(response.last());
    }

    private Supplier newSupplier(int id, String name) {
        Supplier s = new Supplier();
        s.id = id;
        s.name = name;
        s.isActive = true;
        return s;
    }

    private WarehouseSupplierResponse newResponse(int id, String name) {
        return new WarehouseSupplierResponse(id, name, null, null, null, true, null);
    }
}
