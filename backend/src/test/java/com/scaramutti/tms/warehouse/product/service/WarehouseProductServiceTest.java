package com.scaramutti.tms.warehouse.product.service;

import com.scaramutti.tms.auth.dto.UserResponse;
import com.scaramutti.tms.auth.mapper.AuthServiceMapper;
import com.scaramutti.tms.auth.security.CurrentUser;
import com.scaramutti.tms.shared.entity.Product;
import com.scaramutti.tms.shared.entity.ProductCategory;
import com.scaramutti.tms.shared.entity.UnitOfMeasure;
import com.scaramutti.tms.shared.entity.User;
import com.scaramutti.tms.shared.exception.ApiException;
import com.scaramutti.tms.shared.repository.ProductCategoryRepository;
import com.scaramutti.tms.shared.repository.ProductRepository;
import com.scaramutti.tms.shared.repository.UnitOfMeasureRepository;
import com.scaramutti.tms.shared.repository.UserRepository;
import com.scaramutti.tms.warehouse.product.dto.WarehouseProductResponse;
import com.scaramutti.tms.warehouse.product.mapper.WarehouseProductServiceMapper;
import com.scaramutti.tms.warehouse.product.service.cmd.CreateWarehouseProductCommand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests del service. Cubre el flujo de createProduct: validacion de FKs
 * (WH-004), pre-check de identidad duplicada (WH-010), traduccion de la
 * constraint de identidad en la race, y el ensamblado del response (stock=0,
 * lowStock derivado, createdBy). El SKU lo autogenera SIEMPRE el backend. Los
 * integration tests (WarehouseProductsResourceTest) cubren la identidad
 * compuesta y el correlativo, que viven en SQL.
 */
@ExtendWith(MockitoExtension.class)
class WarehouseProductServiceTest {

    @Mock ProductRepository productRepository;
    @Mock ProductCategoryRepository productCategoryRepository;
    @Mock UnitOfMeasureRepository unitOfMeasureRepository;
    @Mock UserRepository userRepository;
    @Mock CurrentUser currentUser;
    @Mock WarehouseProductCodeGeneratorService warehouseProductCodeGeneratorService;
    @Mock WarehouseProductServiceMapper warehouseProductServiceMapper;
    @Mock AuthServiceMapper authServiceMapper;
    @InjectMocks WarehouseProductService warehouseProductService;

    private static final int USER_ID = 7;

    private CreateWarehouseProductCommand command(String brand, String partNumber, BigDecimal minStock) {
        return new CreateWarehouseProductCommand(
            "ZTEST_Filtro", 4, 1, brand, partNumber, Map.of(), minStock, null
        );
    }

    private ProductCategory activeCategory() {
        ProductCategory category = new ProductCategory();
        category.id = 4;
        category.name = "Filtros";
        category.isActive = true;
        return category;
    }

    private UnitOfMeasure activeUnit() {
        UnitOfMeasure unit = new UnitOfMeasure();
        unit.id = 1;
        unit.code = "UND";
        unit.name = "Unidad";
        unit.isActive = true;
        return unit;
    }

    private Product mappedEntity(String code, BigDecimal minStock) {
        Product product = new Product();
        product.id = 100;
        product.code = code;
        product.name = "ZTEST_Filtro";
        product.categoryId = 4;
        product.unitOfMeasureId = 1;
        product.attributes = Map.of();
        product.minStock = minStock;
        product.isActive = true;
        product.createdBy = USER_ID;
        product.createdAt = OffsetDateTime.now();
        product.updatedAt = OffsetDateTime.now();
        return product;
    }

    private User user() {
        User user = new User();
        user.id = USER_ID;
        return user;
    }

    private UserResponse userResponse() {
        return new UserResponse(USER_ID, "admin", "Administrador", "Gerente", "admin", true);
    }

    private void stubFksActive() {
        when(currentUser.requireId()).thenReturn(USER_ID);
        when(productCategoryRepository.findById(4)).thenReturn(activeCategory());
        when(unitOfMeasureRepository.findById(1)).thenReturn(activeUnit());
    }

    // ---------- happy path -------------------------------------------------------

    @Test
    void create_autogeneratesCode_persistsAndBuildsResponse() {
        CreateWarehouseProductCommand command = command("Bosch", "P1", new BigDecimal("4"));
        Product entity = mappedEntity("PRO-0005", new BigDecimal("4"));

        stubFksActive();
        when(productRepository.existsByIdentityIgnoreCase("ZTEST_Filtro", "Bosch", "P1")).thenReturn(false);
        when(warehouseProductCodeGeneratorService.nextCode()).thenReturn("PRO-0005");
        when(warehouseProductServiceMapper.toProductEntity(command, "PRO-0005", USER_ID)).thenReturn(entity);
        when(userRepository.findById(USER_ID)).thenReturn(user());
        when(authServiceMapper.toUserResponse(any(User.class))).thenReturn(userResponse());

        WarehouseProductResponse response = warehouseProductService.createProduct(command);

        assertEquals(100, response.id());
        assertEquals("PRO-0005", response.code());
        assertEquals(4, response.category().id());
        assertEquals("Filtros", response.category().name());
        assertEquals(1, response.unitOfMeasure().id());
        assertEquals("UND", response.unitOfMeasure().code());
        assertEquals(BigDecimal.ZERO, response.stock());
        assertTrue(response.lowStock());                 // 0 < 4
        assertSame(userResponse().id(), response.createdBy().id());
        verify(productRepository).persist(entity);
        verify(productRepository).flush();
    }

    @Test
    void create_withMinStockZero_lowStockFalse() {
        CreateWarehouseProductCommand command = command(null, null, BigDecimal.ZERO);
        Product entity = mappedEntity("PRO-0006", BigDecimal.ZERO);

        stubFksActive();
        when(productRepository.existsByIdentityIgnoreCase(any(), any(), any())).thenReturn(false);
        when(warehouseProductCodeGeneratorService.nextCode()).thenReturn("PRO-0006");
        when(warehouseProductServiceMapper.toProductEntity(command, "PRO-0006", USER_ID)).thenReturn(entity);
        when(userRepository.findById(USER_ID)).thenReturn(user());
        when(authServiceMapper.toUserResponse(any(User.class))).thenReturn(userResponse());

        WarehouseProductResponse response = warehouseProductService.createProduct(command);

        assertFalse(response.lowStock());                // 0 < 0 es false (estricto)
    }

    // ---------- FKs (WH-004) -----------------------------------------------------

    @Test
    void create_whenCategoryNotFound_throwsWH004_doesNotPersist() {
        CreateWarehouseProductCommand command = command(null, null, BigDecimal.ZERO);
        when(currentUser.requireId()).thenReturn(USER_ID);
        when(productCategoryRepository.findById(4)).thenReturn(null);

        ApiException ex = assertThrows(ApiException.class, () -> warehouseProductService.createProduct(command));
        assertEquals("WH-004", ex.code());
        assertEquals(400, ex.status());
        verify(productRepository, never()).persist(any(Product.class));
    }

    @Test
    void create_whenCategoryInactive_throwsWH004() {
        CreateWarehouseProductCommand command = command(null, null, BigDecimal.ZERO);
        ProductCategory inactive = activeCategory();
        inactive.isActive = false;
        when(currentUser.requireId()).thenReturn(USER_ID);
        when(productCategoryRepository.findById(4)).thenReturn(inactive);

        ApiException ex = assertThrows(ApiException.class, () -> warehouseProductService.createProduct(command));
        assertEquals("WH-004", ex.code());
        verify(productRepository, never()).persist(any(Product.class));
    }

    @Test
    void create_whenUnitNotFound_throwsWH004() {
        CreateWarehouseProductCommand command = command(null, null, BigDecimal.ZERO);
        when(currentUser.requireId()).thenReturn(USER_ID);
        when(productCategoryRepository.findById(4)).thenReturn(activeCategory());
        when(unitOfMeasureRepository.findById(1)).thenReturn(null);

        ApiException ex = assertThrows(ApiException.class, () -> warehouseProductService.createProduct(command));
        assertEquals("WH-004", ex.code());
        verify(productRepository, never()).persist(any(Product.class));
    }

    @Test
    void create_whenUnitInactive_throwsWH004() {
        CreateWarehouseProductCommand command = command(null, null, BigDecimal.ZERO);
        UnitOfMeasure inactive = activeUnit();
        inactive.isActive = false;
        when(currentUser.requireId()).thenReturn(USER_ID);
        when(productCategoryRepository.findById(4)).thenReturn(activeCategory());
        when(unitOfMeasureRepository.findById(1)).thenReturn(inactive);

        ApiException ex = assertThrows(ApiException.class, () -> warehouseProductService.createProduct(command));
        assertEquals("WH-004", ex.code());
        verify(productRepository, never()).persist(any(Product.class));
    }

    // ---------- duplicado de identidad pre-check (WH-010) ------------------------

    @Test
    void create_whenIdentityDuplicatePreCheck_throwsWH010_doesNotPersist() {
        CreateWarehouseProductCommand command = command("Bosch", "P1", BigDecimal.ZERO);
        stubFksActive();
        when(productRepository.existsByIdentityIgnoreCase("ZTEST_Filtro", "Bosch", "P1")).thenReturn(true);

        ApiException ex = assertThrows(ApiException.class, () -> warehouseProductService.createProduct(command));
        assertEquals("WH-010", ex.code());
        assertEquals(409, ex.status());
        verify(productRepository, never()).persist(any(Product.class));
    }

    // ---------- race condition: traduccion de la constraint de identidad (WH-010) -

    @Test
    void create_whenFlushThrowsConstraintViolationOnIdentity_translatesToWH010() {
        CreateWarehouseProductCommand command = command("Bosch", "P1", BigDecimal.ZERO);
        Product entity = mappedEntity("PRO-0008", BigDecimal.ZERO);
        stubFksActive();
        when(productRepository.existsByIdentityIgnoreCase(any(), any(), any())).thenReturn(false);
        when(warehouseProductCodeGeneratorService.nextCode()).thenReturn("PRO-0008");
        when(warehouseProductServiceMapper.toProductEntity(command, "PRO-0008", USER_ID)).thenReturn(entity);
        doThrow(wrapConstraint("uq_products_identity")).when(productRepository).flush();

        ApiException ex = assertThrows(ApiException.class, () -> warehouseProductService.createProduct(command));
        assertEquals("WH-010", ex.code());
    }

    // ---------- FK huerfana de usuario (COM-500) ---------------------------------

    @Test
    void create_whenAuthenticatedUserNotFound_throwsInternalError() {
        CreateWarehouseProductCommand command = command(null, null, BigDecimal.ZERO);
        Product entity = mappedEntity("PRO-0009", BigDecimal.ZERO);
        stubFksActive();
        when(productRepository.existsByIdentityIgnoreCase(any(), any(), any())).thenReturn(false);
        when(warehouseProductCodeGeneratorService.nextCode()).thenReturn("PRO-0009");
        when(warehouseProductServiceMapper.toProductEntity(command, "PRO-0009", USER_ID)).thenReturn(entity);
        when(userRepository.findById(USER_ID)).thenReturn(null);

        ApiException ex = assertThrows(ApiException.class, () -> warehouseProductService.createProduct(command));
        assertEquals("COM-500", ex.code());
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
}
