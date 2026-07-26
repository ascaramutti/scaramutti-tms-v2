package com.scaramutti.tms.warehouse.withdrawal.service;

import com.scaramutti.tms.auth.dto.UserResponse;
import com.scaramutti.tms.auth.security.CurrentUser;
import com.scaramutti.tms.auth.service.UserLookup;
import com.scaramutti.tms.shared.entity.Product;
import com.scaramutti.tms.shared.entity.Tractor;
import com.scaramutti.tms.shared.entity.UnitOfMeasure;
import com.scaramutti.tms.shared.entity.Withdrawal;
import com.scaramutti.tms.shared.entity.Worker;
import com.scaramutti.tms.shared.exception.ApiException;
import com.scaramutti.tms.shared.repository.EscortVehicleRepository;
import com.scaramutti.tms.shared.repository.ProductRepository;
import com.scaramutti.tms.shared.repository.TractorRepository;
import com.scaramutti.tms.shared.repository.TrailerRepository;
import com.scaramutti.tms.shared.repository.UnitOfMeasureRepository;
import com.scaramutti.tms.shared.repository.WithdrawalRepository;
import com.scaramutti.tms.shared.repository.WorkerRepository;
import com.scaramutti.tms.warehouse.withdrawal.dto.WarehouseWithdrawalResponse;
import com.scaramutti.tms.warehouse.withdrawal.mapper.WarehouseWithdrawalServiceMapper;
import com.scaramutti.tms.warehouse.withdrawal.service.cmd.CreateWarehouseWithdrawalCommand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mapstruct.factory.Mappers;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests del service del retiro. Cubre el orden de validación (WH-005 → WH-004 →
 * lock/WH-001) y el happy path. El lock real y el efecto acumulado en el stock los cubren
 * los integration tests (WarehouseWithdrawalResourceTest).
 */
@ExtendWith(MockitoExtension.class)
class WarehouseWithdrawalServiceTest {

    @Mock WithdrawalRepository withdrawalRepository;
    @Mock ProductRepository productRepository;
    @Mock WorkerRepository workerRepository;
    @Mock TractorRepository tractorRepository;
    @Mock TrailerRepository trailerRepository;
    @Mock EscortVehicleRepository escortVehicleRepository;
    @Mock UnitOfMeasureRepository unitOfMeasureRepository;
    @Mock UserLookup userLookup;
    @Mock CurrentUser currentUser;
    // Spy sobre la impl REAL generada por MapStruct: el shaping del response se
    // ejercita de verdad; to*Entity se sigue stubeando con when() sobre el spy.
    @Spy WarehouseWithdrawalServiceMapper warehouseWithdrawalServiceMapper = Mappers.getMapper(WarehouseWithdrawalServiceMapper.class);
    @InjectMocks WarehouseWithdrawalService warehouseWithdrawalService;

    private static final int USER_ID = 7;
    private static final int PRODUCT_ID = 10;
    private static final int WORKER_ID = 8;
    private static final int UNIT_ID = 1;

    private CreateWarehouseWithdrawalCommand command(BigDecimal quantity) {
        return new CreateWarehouseWithdrawalCommand(PRODUCT_ID, quantity, WORKER_ID, null, null, null, "ZTEST retiro");
    }

    private Product activeProduct() {
        Product product = new Product();
        product.id = PRODUCT_ID;
        product.code = "PRO-0001";
        product.name = "ZTEST_Filtro";
        product.unitOfMeasureId = UNIT_ID;
        product.isActive = true;
        return product;
    }

    private UnitOfMeasure unit() {
        UnitOfMeasure u = new UnitOfMeasure();
        u.id = UNIT_ID;
        u.code = "UND";
        return u;
    }

    private Worker activeWorker() {
        Worker w = new Worker();
        w.id = WORKER_ID;
        w.firstName = "Juan";
        w.lastName = "Perez";
        w.position = "Mecanico";
        w.isActive = true;
        return w;
    }

    private Withdrawal entity(BigDecimal quantity) {
        Withdrawal w = new Withdrawal();
        w.id = 100;
        w.productId = PRODUCT_ID;
        w.quantity = quantity;
        w.receivedBy = WORKER_ID;
        w.registeredBy = USER_ID;
        w.status = "ACTIVE";
        w.withdrawnAt = OffsetDateTime.now();
        return w;
    }

    private UserResponse userResponse() {
        return new UserResponse(USER_ID, "admin", "Administrador", "Gerente", "admin", true);
    }

    private void stubStock(String stock) {
        when(productRepository.findStockByProductId(PRODUCT_ID))
            .thenReturn(new ProductRepository.ProductStockView(new BigDecimal(stock), false));
    }

    @Test
    void create_sufficientStock_persistsAndReturnsResponse() {
        Withdrawal entity = entity(new BigDecimal("3"));
        when(currentUser.requireId()).thenReturn(USER_ID);
        when(productRepository.findById(PRODUCT_ID)).thenReturn(activeProduct());
        when(unitOfMeasureRepository.findById(UNIT_ID)).thenReturn(unit());
        when(workerRepository.findById(WORKER_ID)).thenReturn(activeWorker());
        stubStock("10");
        when(warehouseWithdrawalServiceMapper.toWithdrawalEntity(command(new BigDecimal("3")), USER_ID)).thenReturn(entity);
        when(userLookup.require(USER_ID)).thenReturn(userResponse());

        WarehouseWithdrawalResponse response = warehouseWithdrawalService.createWithdrawal(command(new BigDecimal("3")));

        assertEquals(100, response.id());
        assertEquals(0, new BigDecimal("3").compareTo(response.quantity()));
        assertEquals("UND", response.product().unitCode());
        assertEquals(WORKER_ID, response.receivedBy().id());
        verify(productRepository).lockProductRow(PRODUCT_ID);
        verify(withdrawalRepository).persist(entity);
    }

    @Test
    void create_quantityExceedsStock_throwsWH001_withAvailableInDetail_doesNotPersist() {
        when(currentUser.requireId()).thenReturn(USER_ID);
        when(productRepository.findById(PRODUCT_ID)).thenReturn(activeProduct());
        when(unitOfMeasureRepository.findById(UNIT_ID)).thenReturn(unit());
        when(workerRepository.findById(WORKER_ID)).thenReturn(activeWorker());
        stubStock("3");

        ApiException ex = assertThrows(ApiException.class,
            () -> warehouseWithdrawalService.createWithdrawal(command(new BigDecimal("5"))));

        assertEquals("WH-001", ex.code());
        assertEquals(409, ex.status());
        // El detail lleva el disponible + la unidad (lo que promete el nombre del test).
        assertTrue(ex.getMessage().contains("3"), "el detail debe indicar el stock disponible");
        assertTrue(ex.getMessage().contains("UND"), "el detail debe indicar la unidad de medida");
        verify(productRepository).lockProductRow(PRODUCT_ID);   // el lock se toma antes de rechazar
        verify(withdrawalRepository, never()).persist(any(Withdrawal.class));
    }

    @Test
    void create_quantityEqualsStock_succeeds() {
        Withdrawal entity = entity(new BigDecimal("5"));
        when(currentUser.requireId()).thenReturn(USER_ID);
        when(productRepository.findById(PRODUCT_ID)).thenReturn(activeProduct());
        when(unitOfMeasureRepository.findById(UNIT_ID)).thenReturn(unit());
        when(workerRepository.findById(WORKER_ID)).thenReturn(activeWorker());
        stubStock("5");
        when(warehouseWithdrawalServiceMapper.toWithdrawalEntity(command(new BigDecimal("5")), USER_ID)).thenReturn(entity);
        when(userLookup.require(USER_ID)).thenReturn(userResponse());

        warehouseWithdrawalService.createWithdrawal(command(new BigDecimal("5")));

        verify(withdrawalRepository).persist(entity);
    }

    @Test
    void create_twoFleetUnits_throwsWH005_beforeTouchingRepos() {
        CreateWarehouseWithdrawalCommand cmd = new CreateWarehouseWithdrawalCommand(
            PRODUCT_ID, new BigDecimal("1"), WORKER_ID, 5, 6, null, null);
        when(currentUser.requireId()).thenReturn(USER_ID);

        ApiException ex = assertThrows(ApiException.class,
            () -> warehouseWithdrawalService.createWithdrawal(cmd));

        assertEquals("WH-005", ex.code());
        assertEquals(400, ex.status());
        verify(productRepository, never()).lockProductRow(any());
        verify(withdrawalRepository, never()).persist(any(Withdrawal.class));
    }

    @Test
    void create_nonexistentProduct_throwsWH004() {
        when(currentUser.requireId()).thenReturn(USER_ID);
        when(productRepository.findById(PRODUCT_ID)).thenReturn(null);

        ApiException ex = assertThrows(ApiException.class,
            () -> warehouseWithdrawalService.createWithdrawal(command(new BigDecimal("1"))));

        assertEquals("WH-004", ex.code());
        verify(productRepository, never()).lockProductRow(any());
    }

    @Test
    void create_inactiveProduct_throwsWH004() {
        Product inactive = activeProduct();
        inactive.isActive = false;
        when(currentUser.requireId()).thenReturn(USER_ID);
        when(productRepository.findById(PRODUCT_ID)).thenReturn(inactive);

        ApiException ex = assertThrows(ApiException.class,
            () -> warehouseWithdrawalService.createWithdrawal(command(new BigDecimal("1"))));

        assertEquals("WH-004", ex.code());
    }

    @Test
    void create_inactiveWorker_throwsWH004() {
        Worker inactive = activeWorker();
        inactive.isActive = false;
        when(currentUser.requireId()).thenReturn(USER_ID);
        when(productRepository.findById(PRODUCT_ID)).thenReturn(activeProduct());
        when(unitOfMeasureRepository.findById(UNIT_ID)).thenReturn(unit());
        when(workerRepository.findById(WORKER_ID)).thenReturn(inactive);

        ApiException ex = assertThrows(ApiException.class,
            () -> warehouseWithdrawalService.createWithdrawal(command(new BigDecimal("1"))));

        assertEquals("WH-004", ex.code());
        verify(productRepository, never()).lockProductRow(any());
    }

    @Test
    void create_inactiveTractor_throwsWH004() {
        Tractor inactive = new Tractor();
        inactive.id = 5;
        inactive.plate = "ABC123";
        inactive.isActive = false;
        CreateWarehouseWithdrawalCommand cmd = new CreateWarehouseWithdrawalCommand(
            PRODUCT_ID, new BigDecimal("1"), WORKER_ID, 5, null, null, null);
        when(currentUser.requireId()).thenReturn(USER_ID);
        when(productRepository.findById(PRODUCT_ID)).thenReturn(activeProduct());
        when(unitOfMeasureRepository.findById(UNIT_ID)).thenReturn(unit());
        when(workerRepository.findById(WORKER_ID)).thenReturn(activeWorker());
        when(tractorRepository.findById(5)).thenReturn(inactive);

        ApiException ex = assertThrows(ApiException.class,
            () -> warehouseWithdrawalService.createWithdrawal(cmd));

        assertEquals("WH-004", ex.code());
        verify(productRepository, never()).lockProductRow(any());
    }
}
