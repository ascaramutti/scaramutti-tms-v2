package com.scaramutti.tms.warehouse.supplier.service;

import com.scaramutti.tms.shared.dto.PageResponse;
import com.scaramutti.tms.shared.entity.Supplier;
import com.scaramutti.tms.shared.exception.ApiException;
import com.scaramutti.tms.shared.repository.SupplierRepository;
import com.scaramutti.tms.warehouse.supplier.dto.WarehouseSupplierResponse;
import com.scaramutti.tms.warehouse.supplier.mapper.WarehouseSupplierServiceMapper;
import com.scaramutti.tms.warehouse.supplier.service.cmd.CreateWarehouseSupplierCommand;
import com.scaramutti.tms.warehouse.supplier.service.cmd.ListWarehouseSuppliersQuery;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
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

    // ---------- createSupplier — validacion + duplicados + race condition -------

    private CreateWarehouseSupplierCommand sampleCommand() {
        return new CreateWarehouseSupplierCommand("ZTEST_Repuestos Diesel", "20512345678", "987654321", "Juan Ramos");
    }

    @Test
    void createSupplier_withValidCommand_persistsEntityAndReturnsResponse() {
        CreateWarehouseSupplierCommand command = sampleCommand();
        Supplier mappedEntity = new Supplier();
        mappedEntity.name = command.name();
        WarehouseSupplierResponse expectedResponse =
            new WarehouseSupplierResponse(1, command.name(), command.ruc(), command.phone(), command.contactName(), true, null);

        when(supplierRepository.existsByNameIgnoreCase(command.name())).thenReturn(false);
        when(supplierRepository.existsByRucIgnoreCase(command.ruc())).thenReturn(false);
        when(warehouseSupplierServiceMapper.toSupplierEntity(command)).thenReturn(mappedEntity);
        when(warehouseSupplierServiceMapper.toWarehouseSupplierResponse(mappedEntity)).thenReturn(expectedResponse);

        WarehouseSupplierResponse actual = warehouseSupplierService.createSupplier(command);

        assertSame(expectedResponse, actual);
        verify(supplierRepository).persist(mappedEntity);
        verify(supplierRepository).flush();
    }

    @Test
    void createSupplier_whenNameDuplicate_throwsApiExceptionWH010_andDoesNotPersist() {
        CreateWarehouseSupplierCommand command = sampleCommand();
        when(supplierRepository.existsByNameIgnoreCase(command.name())).thenReturn(true);

        ApiException ex = assertThrows(ApiException.class, () -> warehouseSupplierService.createSupplier(command));
        assertEquals("WH-010", ex.code());
        assertEquals(409, ex.status());

        verify(supplierRepository, never()).persist(any(Supplier.class));
        // name se chequea primero: si ya choca, ni siquiera consulta ruc.
        verify(supplierRepository, never()).existsByRucIgnoreCase(any());
    }

    @Test
    void createSupplier_whenRucDuplicate_throwsApiExceptionWH010_andDoesNotPersist() {
        CreateWarehouseSupplierCommand command = sampleCommand();
        when(supplierRepository.existsByNameIgnoreCase(command.name())).thenReturn(false);
        when(supplierRepository.existsByRucIgnoreCase(command.ruc())).thenReturn(true);

        ApiException ex = assertThrows(ApiException.class, () -> warehouseSupplierService.createSupplier(command));
        assertEquals("WH-010", ex.code());

        verify(supplierRepository, never()).persist(any(Supplier.class));
    }

    @Test
    void createSupplier_withNullRuc_skipsRucDuplicateCheck_persistsSuccessfully() {
        CreateWarehouseSupplierCommand command = new CreateWarehouseSupplierCommand("ZTEST_SinRuc", null, null, null);
        Supplier mappedEntity = new Supplier();
        WarehouseSupplierResponse mappedResponse = new WarehouseSupplierResponse(1, command.name(), null, null, null, true, null);

        when(supplierRepository.existsByNameIgnoreCase(command.name())).thenReturn(false);
        when(warehouseSupplierServiceMapper.toSupplierEntity(command)).thenReturn(mappedEntity);
        when(warehouseSupplierServiceMapper.toWarehouseSupplierResponse(mappedEntity)).thenReturn(mappedResponse);

        warehouseSupplierService.createSupplier(command);

        verify(supplierRepository, never()).existsByRucIgnoreCase(any());
        verify(supplierRepository).persist(mappedEntity);
    }

    @Test
    void createSupplier_withNullName_throwsValidationFailedCOM001() {
        CreateWarehouseSupplierCommand command = new CreateWarehouseSupplierCommand(null, null, null, null);

        ApiException ex = assertThrows(ApiException.class, () -> warehouseSupplierService.createSupplier(command));
        assertEquals("COM-001", ex.code());
        assertEquals(400, ex.status());

        verify(supplierRepository, never()).persist(any(Supplier.class));
    }

    @Test
    void createSupplier_withEmptyName_throwsValidationFailedCOM001() {
        CreateWarehouseSupplierCommand command = new CreateWarehouseSupplierCommand("", null, null, null);

        ApiException ex = assertThrows(ApiException.class, () -> warehouseSupplierService.createSupplier(command));
        assertEquals("COM-001", ex.code());

        verify(supplierRepository, never()).persist(any(Supplier.class));
    }

    @Test
    void createSupplier_whenFlushThrowsConstraintViolationOnName_translatesToWH010() {
        CreateWarehouseSupplierCommand command = sampleCommand();
        Supplier mappedEntity = new Supplier();

        when(supplierRepository.existsByNameIgnoreCase(command.name())).thenReturn(false);
        when(supplierRepository.existsByRucIgnoreCase(command.ruc())).thenReturn(false);
        when(warehouseSupplierServiceMapper.toSupplierEntity(command)).thenReturn(mappedEntity);

        org.hibernate.exception.ConstraintViolationException hibernateCve =
            new org.hibernate.exception.ConstraintViolationException(
                "Postgres UNIQUE violation",
                new java.sql.SQLException("violates unique constraint \"uq_suppliers_name_ci\""),
                "uq_suppliers_name_ci"
            );
        jakarta.persistence.PersistenceException jpaWrapper =
            new jakarta.persistence.PersistenceException(hibernateCve);
        doThrow(jpaWrapper).when(supplierRepository).flush();

        ApiException ex = assertThrows(ApiException.class, () -> warehouseSupplierService.createSupplier(command));
        assertEquals("WH-010", ex.code());
    }

    @Test
    void createSupplier_whenFlushThrowsConstraintViolationOnRuc_translatesToWH010() {
        CreateWarehouseSupplierCommand command = sampleCommand();
        Supplier mappedEntity = new Supplier();

        when(supplierRepository.existsByNameIgnoreCase(command.name())).thenReturn(false);
        when(supplierRepository.existsByRucIgnoreCase(command.ruc())).thenReturn(false);
        when(warehouseSupplierServiceMapper.toSupplierEntity(command)).thenReturn(mappedEntity);

        org.hibernate.exception.ConstraintViolationException hibernateCve =
            new org.hibernate.exception.ConstraintViolationException(
                "Postgres UNIQUE violation",
                new java.sql.SQLException("violates unique constraint \"uq_suppliers_ruc_ci\""),
                "uq_suppliers_ruc_ci"
            );
        jakarta.persistence.PersistenceException jpaWrapper =
            new jakarta.persistence.PersistenceException(hibernateCve);
        doThrow(jpaWrapper).when(supplierRepository).flush();

        ApiException ex = assertThrows(ApiException.class, () -> warehouseSupplierService.createSupplier(command));
        assertEquals("WH-010", ex.code());
    }

    @Test
    void createSupplier_existsByNameIgnoreCase_calledBeforePersist() {
        CreateWarehouseSupplierCommand command = sampleCommand();
        Supplier mappedEntity = new Supplier();

        when(supplierRepository.existsByNameIgnoreCase(command.name())).thenReturn(false);
        when(supplierRepository.existsByRucIgnoreCase(command.ruc())).thenReturn(false);
        when(warehouseSupplierServiceMapper.toSupplierEntity(command)).thenReturn(mappedEntity);

        warehouseSupplierService.createSupplier(command);

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(supplierRepository);
        order.verify(supplierRepository).existsByNameIgnoreCase(command.name());
        order.verify(supplierRepository).existsByRucIgnoreCase(command.ruc());
        order.verify(supplierRepository).persist(any(Supplier.class));
        order.verify(supplierRepository).flush();
    }
}
