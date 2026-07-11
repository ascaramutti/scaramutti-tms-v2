package com.scaramutti.tms.warehouse.kardex.service;

import com.scaramutti.tms.auth.dto.UserResponse;
import com.scaramutti.tms.auth.service.UserLookup;
import com.scaramutti.tms.shared.dto.PageResponse;
import com.scaramutti.tms.shared.entity.Product;
import com.scaramutti.tms.shared.exception.ApiException;
import com.scaramutti.tms.shared.repository.ProductRepository;
import com.scaramutti.tms.shared.repository.WarehouseKardexRepository;
import com.scaramutti.tms.shared.repository.WarehouseKardexRepository.EntradaReferenceView;
import com.scaramutti.tms.shared.repository.WarehouseKardexRepository.KardexMovementRow;
import com.scaramutti.tms.shared.repository.WarehouseKardexRepository.SalidaReferenceView;
import com.scaramutti.tms.warehouse.kardex.dto.WarehouseKardexMovementResponse;
import com.scaramutti.tms.warehouse.kardex.model.WarehouseKardexMovementType;
import com.scaramutti.tms.warehouse.kardex.service.cmd.GetWarehouseKardexQuery;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests del service de kardex. Cubre: 404, delegacion del balance al SQL
 * (el service NO recalcula), mapeo quantity/balance 1:1 desde la fila, y la
 * composicion del reference por tipo (APERTURA constante sin lookup, ENTRADA/
 * SALIDA via los mapas batch). Los integration tests (WarehouseKardexResourceTest)
 * cubren el SQL real (RN-WH13, anulados, desempate, filtros de fecha).
 */
@ExtendWith(MockitoExtension.class)
class WarehouseKardexServiceTest {

    @Mock ProductRepository productRepository;
    @Mock WarehouseKardexRepository warehouseKardexRepository;
    @Mock UserLookup userLookup;
    @InjectMocks WarehouseKardexService warehouseKardexService;

    private static final int PRODUCT_ID = 100;
    private static final int USER_ID = 7;

    private GetWarehouseKardexQuery query() {
        return new GetWarehouseKardexQuery(PRODUCT_ID, null, null, 0, 20);
    }

    private void stubProductExists() {
        when(productRepository.findByIdOptional(PRODUCT_ID)).thenReturn(Optional.of(new Product()));
    }

    private UserResponse userResponse() {
        return new UserResponse(USER_ID, "admin", "Administrador", "Gerente", "admin", true);
    }

    // ---------- 404 ---------------------------------------------------------------

    @Test
    void getKardex_nonexistentProduct_throwsWH003_doesNotQueryMovements() {
        when(productRepository.findByIdOptional(PRODUCT_ID)).thenReturn(Optional.empty());

        ApiException ex = assertThrows(ApiException.class, () -> warehouseKardexService.getKardex(query()));

        assertEquals("WH-003", ex.code());
        assertEquals(404, ex.status());
        verify(warehouseKardexRepository, never()).findPaged(org.mockito.ArgumentMatchers.any());
    }

    // ---------- pagina vacia --------------------------------------------------------

    @Test
    void getKardex_noMovements_returnsEmptyPage_skipsBatchLookups() {
        stubProductExists();
        when(warehouseKardexRepository.findPaged(query())).thenReturn(List.of());
        when(warehouseKardexRepository.countMatching(query())).thenReturn(0L);

        PageResponse<WarehouseKardexMovementResponse> response = warehouseKardexService.getKardex(query());

        assertTrue(response.empty());
        assertEquals(0L, response.totalElements());
        verify(warehouseKardexRepository, never()).findEntradaReferences(org.mockito.ArgumentMatchers.any());
        verify(warehouseKardexRepository, never()).findSalidaReferences(org.mockito.ArgumentMatchers.any());
        verify(userLookup, never()).requireAllById(org.mockito.ArgumentMatchers.any());
    }

    // ---------- APERTURA: constante, sin lookup -------------------------------------

    @Test
    void getKardex_apertura_referenceIsConstant_noSourceLookup() {
        stubProductExists();
        KardexMovementRow row = new KardexMovementRow(
            "APERTURA", new BigDecimal("100"), new BigDecimal("100"), OffsetDateTime.now(), null, USER_ID);
        when(warehouseKardexRepository.findPaged(query())).thenReturn(List.of(row));
        when(warehouseKardexRepository.countMatching(query())).thenReturn(1L);
        when(warehouseKardexRepository.findEntradaReferences(List.of())).thenReturn(Map.of());
        when(warehouseKardexRepository.findSalidaReferences(List.of())).thenReturn(Map.of());
        when(userLookup.requireAllById(Set.of(USER_ID))).thenReturn(Map.of(USER_ID, userResponse()));

        PageResponse<WarehouseKardexMovementResponse> response = warehouseKardexService.getKardex(query());

        WarehouseKardexMovementResponse movement = response.content().get(0);
        assertEquals(WarehouseKardexMovementType.APERTURA, movement.movementType());
        assertEquals("Apertura de inventario", movement.reference());
        assertNull(movement.sourceId());
        assertEquals(new BigDecimal("100"), movement.balance());
        assertEquals(USER_ID, movement.registeredBy().id());
    }

    // ---------- ENTRADA: factura + proveedor ----------------------------------------

    @Test
    void getKardex_entrada_referenceComposesInvoiceAndSupplier() {
        stubProductExists();
        KardexMovementRow row = new KardexMovementRow(
            "ENTRADA", new BigDecimal("50"), new BigDecimal("150"), OffsetDateTime.now(), 55, USER_ID);
        when(warehouseKardexRepository.findPaged(query())).thenReturn(List.of(row));
        when(warehouseKardexRepository.countMatching(query())).thenReturn(1L);
        when(warehouseKardexRepository.findEntradaReferences(List.of(55)))
            .thenReturn(Map.of(55, new EntradaReferenceView("F001-123", "Repuestos SAC")));
        when(warehouseKardexRepository.findSalidaReferences(List.of())).thenReturn(Map.of());
        when(userLookup.requireAllById(Set.of(USER_ID))).thenReturn(Map.of(USER_ID, userResponse()));

        PageResponse<WarehouseKardexMovementResponse> response = warehouseKardexService.getKardex(query());

        WarehouseKardexMovementResponse movement = response.content().get(0);
        assertEquals(WarehouseKardexMovementType.ENTRADA, movement.movementType());
        assertEquals("Factura F001-123 · Repuestos SAC", movement.reference());
        assertEquals(55, movement.sourceId());
        assertEquals(new BigDecimal("150"), movement.balance()); // delegado al repo, no recalculado
    }

    // ---------- SALIDA: con y sin unidad ---------------------------------------------

    @Test
    void getKardex_salida_withPlate_referenceIncludesPlate() {
        stubProductExists();
        KardexMovementRow row = new KardexMovementRow(
            "SALIDA", new BigDecimal("10"), new BigDecimal("40"), OffsetDateTime.now(), 77, USER_ID);
        when(warehouseKardexRepository.findPaged(query())).thenReturn(List.of(row));
        when(warehouseKardexRepository.countMatching(query())).thenReturn(1L);
        when(warehouseKardexRepository.findEntradaReferences(List.of())).thenReturn(Map.of());
        when(warehouseKardexRepository.findSalidaReferences(List.of(77)))
            .thenReturn(Map.of(77, new SalidaReferenceView("Carlos Quispe", "Z90001")));
        when(userLookup.requireAllById(Set.of(USER_ID))).thenReturn(Map.of(USER_ID, userResponse()));

        PageResponse<WarehouseKardexMovementResponse> response = warehouseKardexService.getKardex(query());

        assertEquals("Retiro · recibe Carlos Quispe · Z90001", response.content().get(0).reference());
    }

    @Test
    void getKardex_salida_withoutPlate_referenceOmitsPlateGracefully() {
        stubProductExists();
        KardexMovementRow row = new KardexMovementRow(
            "SALIDA", new BigDecimal("5"), new BigDecimal("45"), OffsetDateTime.now(), 88, USER_ID);
        when(warehouseKardexRepository.findPaged(query())).thenReturn(List.of(row));
        when(warehouseKardexRepository.countMatching(query())).thenReturn(1L);
        when(warehouseKardexRepository.findEntradaReferences(List.of())).thenReturn(Map.of());
        when(warehouseKardexRepository.findSalidaReferences(List.of(88)))
            .thenReturn(Map.of(88, new SalidaReferenceView("Rosa Diaz", null)));
        when(userLookup.requireAllById(Set.of(USER_ID))).thenReturn(Map.of(USER_ID, userResponse()));

        PageResponse<WarehouseKardexMovementResponse> response = warehouseKardexService.getKardex(query());

        assertEquals("Retiro · recibe Rosa Diaz", response.content().get(0).reference());
    }

    // ---------- quantity siempre positiva (el repo ya la devuelve ABS; el service la pasa tal cual) --

    @Test
    void getKardex_quantityFromRow_isPassedThroughUnmodified() {
        stubProductExists();
        KardexMovementRow row = new KardexMovementRow(
            "SALIDA", new BigDecimal("30"), new BigDecimal("70"), OffsetDateTime.now(), 99, USER_ID);
        when(warehouseKardexRepository.findPaged(query())).thenReturn(List.of(row));
        when(warehouseKardexRepository.countMatching(query())).thenReturn(1L);
        when(warehouseKardexRepository.findEntradaReferences(List.of())).thenReturn(Map.of());
        when(warehouseKardexRepository.findSalidaReferences(List.of(99)))
            .thenReturn(Map.of(99, new SalidaReferenceView("Ana Vega", null)));
        when(userLookup.requireAllById(Set.of(USER_ID))).thenReturn(Map.of(USER_ID, userResponse()));

        WarehouseKardexMovementResponse movement = warehouseKardexService.getKardex(query()).content().get(0);

        assertEquals(new BigDecimal("30"), movement.quantity());
        assertEquals(new BigDecimal("70"), movement.balance());
    }
}
