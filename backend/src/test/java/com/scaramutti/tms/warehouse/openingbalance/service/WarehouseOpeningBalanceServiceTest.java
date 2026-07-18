package com.scaramutti.tms.warehouse.openingbalance.service;

import com.scaramutti.tms.auth.dto.UserResponse;
import com.scaramutti.tms.auth.security.CurrentUser;
import com.scaramutti.tms.auth.service.UserLookup;
import com.scaramutti.tms.shared.dto.PageResponse;
import com.scaramutti.tms.shared.entity.OpeningBalance;
import com.scaramutti.tms.shared.entity.Product;
import com.scaramutti.tms.shared.entity.UnitOfMeasure;
import com.scaramutti.tms.shared.exception.ApiException;
import com.scaramutti.tms.shared.repository.OpeningBalanceRepository;
import com.scaramutti.tms.shared.repository.ProductRepository;
import com.scaramutti.tms.shared.repository.UnitOfMeasureRepository;
import com.scaramutti.tms.warehouse.openingbalance.mapper.WarehouseOpeningBalanceServiceMapper;
import com.scaramutti.tms.warehouse.openingbalance.service.cmd.CreateWarehouseOpeningBalanceCommand;
import com.scaramutti.tms.warehouse.openingbalance.service.cmd.ListWarehouseOpeningBalancesQuery;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mapstruct.factory.Mappers;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests del service. Cubre la validacion de la FK (WH-004), el pre-check
 * de duplicado (WH-009) y su traduccion en la race, el check de movimientos
 * previos (WH-011), el ensamblado del response y el listado batch. Los
 * integration tests (WarehouseOpeningBalanceResourceTest) cubren el UNIQUE y
 * el filtrado de movimientos anulados, que viven en SQL/la VIEW.
 */
@ExtendWith(MockitoExtension.class)
class WarehouseOpeningBalanceServiceTest {

    @Mock OpeningBalanceRepository openingBalanceRepository;
    @Mock ProductRepository productRepository;
    @Mock UnitOfMeasureRepository unitOfMeasureRepository;
    @Mock UserLookup userLookup;
    @Mock CurrentUser currentUser;
    // Spy sobre la impl REAL generada por MapStruct: el shaping del response se
    // ejercita de verdad; to*Entity se sigue stubeando con when() sobre el spy.
    @Spy WarehouseOpeningBalanceServiceMapper warehouseOpeningBalanceServiceMapper = Mappers.getMapper(WarehouseOpeningBalanceServiceMapper.class);
    @InjectMocks WarehouseOpeningBalanceService warehouseOpeningBalanceService;

    private static final int USER_ID = 7;
    private static final int PRODUCT_ID = 10;

    private CreateWarehouseOpeningBalanceCommand command(BigDecimal quantity) {
        return new CreateWarehouseOpeningBalanceCommand(PRODUCT_ID, quantity, "Conteo inicial");
    }

    private Product activeProduct() {
        Product product = new Product();
        product.id = PRODUCT_ID;
        product.code = "PRO-0001";
        product.name = "ZTEST_Filtro";
        product.unitOfMeasureId = 1;
        product.isActive = true;
        return product;
    }

    private UnitOfMeasure unitOfMeasure() {
        UnitOfMeasure unit = new UnitOfMeasure();
        unit.id = 1;
        unit.code = "UND";
        unit.name = "Unidad";
        unit.isActive = true;
        return unit;
    }

    private OpeningBalance entity(BigDecimal quantity) {
        OpeningBalance openingBalance = new OpeningBalance();
        openingBalance.id = 100;
        openingBalance.productId = PRODUCT_ID;
        openingBalance.quantity = quantity;
        openingBalance.observations = "Conteo inicial";
        openingBalance.registeredBy = USER_ID;
        openingBalance.registeredAt = OffsetDateTime.now();
        return openingBalance;
    }

    private UserResponse userResponse() {
        return new UserResponse(USER_ID, "admin", "Administrador", "Gerente", "admin", true);
    }

    private void stubHappyPathUpTo(BigDecimal quantity, OpeningBalance entity) {
        when(currentUser.requireId()).thenReturn(USER_ID);
        when(productRepository.findById(PRODUCT_ID)).thenReturn(activeProduct());
        when(openingBalanceRepository.existsByProductId(PRODUCT_ID)).thenReturn(false);
        when(openingBalanceRepository.existsActiveMovementsForProduct(PRODUCT_ID)).thenReturn(false);
        when(warehouseOpeningBalanceServiceMapper.toOpeningBalanceEntity(command(quantity), USER_ID)).thenReturn(entity);
        when(unitOfMeasureRepository.findById(1)).thenReturn(unitOfMeasure());
        when(userLookup.require(USER_ID)).thenReturn(userResponse());
    }

    // ---------- happy path ----------------------------------------------------------

    @Test
    void create_persistsAndBuildsResponse() {
        OpeningBalance entity = entity(new BigDecimal("100"));
        stubHappyPathUpTo(new BigDecimal("100"), entity);

        var response = warehouseOpeningBalanceService.createOpeningBalance(command(new BigDecimal("100")));

        assertEquals(100, response.id());
        assertEquals(PRODUCT_ID, response.product().id());
        assertEquals("PRO-0001", response.product().code());
        assertEquals("UND", response.product().unitCode());
        assertEquals(new BigDecimal("100"), response.quantity());
        assertEquals("Conteo inicial", response.observations());
        assertEquals(USER_ID, response.registeredBy().id());
        verify(openingBalanceRepository).persist(entity);
        verify(openingBalanceRepository).flush();
    }

    @Test
    void create_withQuantityZero_isAccepted() {
        OpeningBalance entity = entity(BigDecimal.ZERO);
        stubHappyPathUpTo(BigDecimal.ZERO, entity);

        var response = warehouseOpeningBalanceService.createOpeningBalance(command(BigDecimal.ZERO));

        assertEquals(BigDecimal.ZERO, response.quantity());
        verify(openingBalanceRepository).persist(entity);
    }

    // ---------- FK (WH-004) -----------------------------------------------------------

    @Test
    void create_whenProductNotFound_throwsWH004_doesNotPersist() {
        when(currentUser.requireId()).thenReturn(USER_ID);
        when(productRepository.findById(PRODUCT_ID)).thenReturn(null);

        ApiException ex = assertThrows(ApiException.class,
            () -> warehouseOpeningBalanceService.createOpeningBalance(command(new BigDecimal("10"))));

        assertEquals("WH-004", ex.code());
        assertEquals(400, ex.status());
        verify(openingBalanceRepository, never()).persist(any(OpeningBalance.class));
    }

    @Test
    void create_whenProductInactive_throwsWH004_doesNotPersist() {
        Product inactive = activeProduct();
        inactive.isActive = false;
        when(currentUser.requireId()).thenReturn(USER_ID);
        when(productRepository.findById(PRODUCT_ID)).thenReturn(inactive);

        ApiException ex = assertThrows(ApiException.class,
            () -> warehouseOpeningBalanceService.createOpeningBalance(command(new BigDecimal("10"))));

        assertEquals("WH-004", ex.code());
        verify(openingBalanceRepository, never()).persist(any(OpeningBalance.class));
    }

    // ---------- duplicado pre-check (WH-009) -------------------------------------------

    @Test
    void create_whenOpeningBalanceAlreadyExists_throwsWH009_doesNotPersist() {
        when(currentUser.requireId()).thenReturn(USER_ID);
        when(productRepository.findById(PRODUCT_ID)).thenReturn(activeProduct());
        when(openingBalanceRepository.existsByProductId(PRODUCT_ID)).thenReturn(true);

        ApiException ex = assertThrows(ApiException.class,
            () -> warehouseOpeningBalanceService.createOpeningBalance(command(new BigDecimal("10"))));

        assertEquals("WH-009", ex.code());
        assertEquals(409, ex.status());
        verify(openingBalanceRepository, never()).persist(any(OpeningBalance.class));
    }

    // ---------- movimientos previos (WH-011) -------------------------------------------

    @Test
    void create_whenProductHasActiveMovements_throwsWH011_doesNotPersist() {
        when(currentUser.requireId()).thenReturn(USER_ID);
        when(productRepository.findById(PRODUCT_ID)).thenReturn(activeProduct());
        when(openingBalanceRepository.existsByProductId(PRODUCT_ID)).thenReturn(false);
        when(openingBalanceRepository.existsActiveMovementsForProduct(PRODUCT_ID)).thenReturn(true);

        ApiException ex = assertThrows(ApiException.class,
            () -> warehouseOpeningBalanceService.createOpeningBalance(command(new BigDecimal("10"))));

        assertEquals("WH-011", ex.code());
        assertEquals(409, ex.status());
        verify(openingBalanceRepository, never()).persist(any(OpeningBalance.class));
    }

    // ---------- race condition: traduccion del UNIQUE (WH-009) -------------------------

    @Test
    void create_whenFlushThrowsConstraintViolationOnProductId_translatesToWH009() {
        OpeningBalance entity = entity(new BigDecimal("10"));
        when(currentUser.requireId()).thenReturn(USER_ID);
        when(productRepository.findById(PRODUCT_ID)).thenReturn(activeProduct());
        when(openingBalanceRepository.existsByProductId(PRODUCT_ID)).thenReturn(false);
        when(openingBalanceRepository.existsActiveMovementsForProduct(PRODUCT_ID)).thenReturn(false);
        when(warehouseOpeningBalanceServiceMapper.toOpeningBalanceEntity(command(new BigDecimal("10")), USER_ID)).thenReturn(entity);
        doThrow(wrapConstraint("opening_balances_product_id_key")).when(openingBalanceRepository).flush();

        ApiException ex = assertThrows(ApiException.class,
            () -> warehouseOpeningBalanceService.createOpeningBalance(command(new BigDecimal("10"))));

        assertEquals("WH-009", ex.code());
    }

    private jakarta.persistence.PersistenceException wrapConstraint(String constraintName) {
        org.hibernate.exception.ConstraintViolationException hibernateCve =
            new org.hibernate.exception.ConstraintViolationException(
                "Postgres UNIQUE violation",
                new java.sql.SQLException("violates unique constraint \"" + constraintName + "\""),
                constraintName
            );
        return new jakarta.persistence.PersistenceException(hibernateCve);
    }

    // ---------- listado ----------------------------------------------------------------

    @Test
    void list_mapsAndEnrichesBatch() {
        ListWarehouseOpeningBalancesQuery query = new ListWarehouseOpeningBalancesQuery(null, 0, 20);
        OpeningBalance entity = entity(new BigDecimal("100"));
        when(openingBalanceRepository.searchPaged(query)).thenReturn(List.of(entity));
        when(openingBalanceRepository.countSearch(query)).thenReturn(1L);
        when(productRepository.list("id in ?1", java.util.Set.of(PRODUCT_ID))).thenReturn(List.of(activeProduct()));
        when(unitOfMeasureRepository.list("id in ?1", java.util.Set.of(1))).thenReturn(List.of(unitOfMeasure()));
        when(userLookup.requireAllById(java.util.Set.of(USER_ID))).thenReturn(Map.of(USER_ID, userResponse()));

        PageResponse<?> response = warehouseOpeningBalanceService.listOpeningBalances(query);

        assertEquals(1, response.content().size());
        assertEquals(1L, response.totalElements());
    }

    @Test
    void list_empty_returnsEmptyPageWithoutFurtherLookups() {
        ListWarehouseOpeningBalancesQuery query = new ListWarehouseOpeningBalancesQuery(PRODUCT_ID, 0, 20);
        when(openingBalanceRepository.searchPaged(query)).thenReturn(List.of());
        when(openingBalanceRepository.countSearch(query)).thenReturn(0L);

        PageResponse<?> response = warehouseOpeningBalanceService.listOpeningBalances(query);

        assertTrue(response.content().isEmpty());
        assertEquals(0L, response.totalElements());
        verify(productRepository, never()).list(any(String.class), any(java.util.Collection.class));
    }
}
