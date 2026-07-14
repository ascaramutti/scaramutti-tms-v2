package com.scaramutti.tms.warehouse.purchaseinvoice.service;

import com.scaramutti.tms.auth.dto.UserResponse;
import com.scaramutti.tms.auth.security.CurrentUser;
import com.scaramutti.tms.auth.service.UserLookup;
import com.scaramutti.tms.shared.dto.PageResponse;
import com.scaramutti.tms.shared.entity.Currency;
import com.scaramutti.tms.shared.entity.Product;
import com.scaramutti.tms.shared.entity.PurchaseInvoice;
import com.scaramutti.tms.shared.entity.PurchaseInvoiceItem;
import com.scaramutti.tms.shared.entity.Supplier;
import com.scaramutti.tms.shared.entity.UnitOfMeasure;
import com.scaramutti.tms.shared.exception.ApiException;
import com.scaramutti.tms.shared.repository.CurrencyRepository;
import com.scaramutti.tms.shared.repository.ProductRepository;
import com.scaramutti.tms.shared.repository.PurchaseInvoiceItemRepository;
import com.scaramutti.tms.shared.repository.PurchaseInvoiceRepository;
import com.scaramutti.tms.shared.repository.AuditLogRepository;
import com.scaramutti.tms.shared.repository.SupplierRepository;
import com.scaramutti.tms.shared.repository.UnitOfMeasureRepository;
import com.scaramutti.tms.shared.util.Etag;
import com.scaramutti.tms.warehouse.purchaseinvoice.service.cmd.UpdateWarehousePurchaseInvoiceCommand;

import java.util.Optional;
import com.scaramutti.tms.warehouse.model.WarehouseRecordStatus;
import com.scaramutti.tms.warehouse.purchaseinvoice.dto.WarehousePurchaseInvoiceResponse;
import com.scaramutti.tms.warehouse.purchaseinvoice.mapper.WarehousePurchaseInvoiceServiceMapper;
import com.scaramutti.tms.warehouse.purchaseinvoice.service.cmd.CreateWarehouseInvoiceItemCommand;
import com.scaramutti.tms.warehouse.purchaseinvoice.service.cmd.CreateWarehousePurchaseInvoiceCommand;
import com.scaramutti.tms.warehouse.purchaseinvoice.service.cmd.ListWarehousePurchaseInvoicesQuery;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests del service transaccional. Cubre el orden de validación (WH-004
 * proveedor/moneda/producto → WH-002 duplicado), la traducción de la race del
 * UNIQUE parcial a WH-002, el ensamblado del response (subtotal/total derivados) y
 * el listado batch. Los integration tests (WarehousePurchaseInvoiceResourceTest)
 * cubren el UNIQUE real, la transaccionalidad y el efecto en el stock (VIEW).
 */
@ExtendWith(MockitoExtension.class)
class WarehousePurchaseInvoiceServiceTest {

    @Mock PurchaseInvoiceRepository purchaseInvoiceRepository;
    @Mock PurchaseInvoiceItemRepository purchaseInvoiceItemRepository;
    @Mock SupplierRepository supplierRepository;
    @Mock CurrencyRepository currencyRepository;
    @Mock ProductRepository productRepository;
    @Mock UnitOfMeasureRepository unitOfMeasureRepository;
    @Mock AuditLogRepository auditLogRepository;
    @Mock UserLookup userLookup;
    @Mock CurrentUser currentUser;
    @Mock WarehousePurchaseInvoiceServiceMapper warehousePurchaseInvoiceServiceMapper;
    @InjectMocks WarehousePurchaseInvoiceService warehousePurchaseInvoiceService;

    private static final int USER_ID = 7;
    private static final int SUPPLIER_ID = 4;
    private static final int CURRENCY_ID = 2;
    private static final int PRODUCT_ID = 10;
    private static final int UNIT_ID = 1;
    private static final int INVOICE_ID = 500;

    private CreateWarehouseInvoiceItemCommand itemCommand() {
        return new CreateWarehouseInvoiceItemCommand(PRODUCT_ID, new BigDecimal("10"), new BigDecimal("45.00"));
    }

    private CreateWarehousePurchaseInvoiceCommand command() {
        return new CreateWarehousePurchaseInvoiceCommand(
            SUPPLIER_ID, "F001-00123", LocalDate.parse("2026-07-02"), null, CURRENCY_ID, null,
            List.of(itemCommand()));
    }

    private Supplier activeSupplier() {
        Supplier supplier = new Supplier();
        supplier.id = SUPPLIER_ID;
        supplier.name = "ZTEST_Proveedor";
        supplier.ruc = "20512345678";
        supplier.isActive = true;
        return supplier;
    }

    private Currency activeCurrency() {
        Currency currency = new Currency();
        currency.id = CURRENCY_ID;
        currency.code = "PEN";
        currency.symbol = "S/";
        currency.isActive = true;
        return currency;
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

    private UnitOfMeasure unitOfMeasure() {
        UnitOfMeasure unit = new UnitOfMeasure();
        unit.id = UNIT_ID;
        unit.code = "UND";
        unit.name = "Unidad";
        unit.isActive = true;
        return unit;
    }

    private PurchaseInvoice invoiceEntity() {
        PurchaseInvoice invoice = new PurchaseInvoice();
        invoice.id = INVOICE_ID;
        invoice.supplierId = SUPPLIER_ID;
        invoice.invoiceNumber = "F001-00123";
        invoice.invoiceDate = LocalDate.parse("2026-07-02");
        invoice.currencyId = CURRENCY_ID;
        invoice.registeredBy = USER_ID;
        invoice.status = "ACTIVE";
        invoice.createdAt = OffsetDateTime.now();
        invoice.updatedAt = invoice.createdAt;
        return invoice;
    }

    private PurchaseInvoiceItem itemEntity() {
        PurchaseInvoiceItem item = new PurchaseInvoiceItem();
        item.id = 900;
        item.invoiceId = INVOICE_ID;
        item.productId = PRODUCT_ID;
        item.quantity = new BigDecimal("10");
        item.unitPrice = new BigDecimal("45.00");
        return item;
    }

    private UserResponse userResponse() {
        return new UserResponse(USER_ID, "admin", "Administrador", "Gerente", "admin", true);
    }

    private void stubHappyPath(CreateWarehousePurchaseInvoiceCommand cmd, PurchaseInvoice invoice, PurchaseInvoiceItem item) {
        when(currentUser.requireId()).thenReturn(USER_ID);
        when(supplierRepository.findById(SUPPLIER_ID)).thenReturn(activeSupplier());
        when(currencyRepository.findById(CURRENCY_ID)).thenReturn(activeCurrency());
        when(productRepository.list("id in ?1", Set.of(PRODUCT_ID))).thenReturn(List.of(activeProduct()));
        when(purchaseInvoiceRepository.existsActiveBySupplierAndNumber(SUPPLIER_ID, "F001-00123")).thenReturn(false);
        when(warehousePurchaseInvoiceServiceMapper.toPurchaseInvoiceEntity(cmd, USER_ID)).thenReturn(invoice);
        when(warehousePurchaseInvoiceServiceMapper.toPurchaseInvoiceItemEntity(cmd.items().get(0), INVOICE_ID)).thenReturn(item);
        when(unitOfMeasureRepository.list("id in ?1", Set.of(UNIT_ID))).thenReturn(List.of(unitOfMeasure()));
        when(userLookup.require(USER_ID)).thenReturn(userResponse());
    }

    // ---------- happy path ----------------------------------------------------------

    @Test
    void create_persistsInvoiceAndItems_buildsResponseWithDerivedTotals() {
        CreateWarehousePurchaseInvoiceCommand cmd = command();
        PurchaseInvoice invoice = invoiceEntity();
        PurchaseInvoiceItem item = itemEntity();
        stubHappyPath(cmd, invoice, item);

        WarehousePurchaseInvoiceResponse response = warehousePurchaseInvoiceService.createPurchaseInvoice(cmd);

        assertEquals(INVOICE_ID, response.id());
        assertEquals(SUPPLIER_ID, response.supplier().id());
        assertEquals("PEN", response.currency().code());
        assertEquals(WarehouseRecordStatus.ACTIVE, response.status());
        assertEquals(1, response.items().size());
        assertEquals(0, new BigDecimal("450.00").compareTo(response.items().get(0).subtotal()));
        assertEquals(0, new BigDecimal("450.00").compareTo(response.total()));
        assertEquals("UND", response.items().get(0).product().unitCode());
        verify(purchaseInvoiceRepository).persist(invoice);
        verify(purchaseInvoiceRepository).flush();
        verify(purchaseInvoiceItemRepository).persist(item);
    }

    // ---------- FK (WH-004) -----------------------------------------------------------

    @Test
    void create_whenSupplierNotFound_throwsWH004_doesNotPersist() {
        when(currentUser.requireId()).thenReturn(USER_ID);
        when(supplierRepository.findById(SUPPLIER_ID)).thenReturn(null);

        ApiException ex = assertThrows(ApiException.class,
            () -> warehousePurchaseInvoiceService.createPurchaseInvoice(command()));

        assertEquals("WH-004", ex.code());
        assertEquals(400, ex.status());
        verify(purchaseInvoiceRepository, never()).persist(any(PurchaseInvoice.class));
    }

    @Test
    void create_whenSupplierInactive_throwsWH004() {
        Supplier inactive = activeSupplier();
        inactive.isActive = false;
        when(currentUser.requireId()).thenReturn(USER_ID);
        when(supplierRepository.findById(SUPPLIER_ID)).thenReturn(inactive);

        ApiException ex = assertThrows(ApiException.class,
            () -> warehousePurchaseInvoiceService.createPurchaseInvoice(command()));

        assertEquals("WH-004", ex.code());
        verify(purchaseInvoiceRepository, never()).persist(any(PurchaseInvoice.class));
    }

    @Test
    void create_whenCurrencyNotFound_throwsWH004() {
        when(currentUser.requireId()).thenReturn(USER_ID);
        when(supplierRepository.findById(SUPPLIER_ID)).thenReturn(activeSupplier());
        when(currencyRepository.findById(CURRENCY_ID)).thenReturn(null);

        ApiException ex = assertThrows(ApiException.class,
            () -> warehousePurchaseInvoiceService.createPurchaseInvoice(command()));

        assertEquals("WH-004", ex.code());
        verify(purchaseInvoiceRepository, never()).persist(any(PurchaseInvoice.class));
    }

    @Test
    void create_whenProductInItemNotFound_throwsWH004_doesNotPersist() {
        when(currentUser.requireId()).thenReturn(USER_ID);
        when(supplierRepository.findById(SUPPLIER_ID)).thenReturn(activeSupplier());
        when(currencyRepository.findById(CURRENCY_ID)).thenReturn(activeCurrency());
        when(productRepository.list("id in ?1", Set.of(PRODUCT_ID))).thenReturn(List.of());

        ApiException ex = assertThrows(ApiException.class,
            () -> warehousePurchaseInvoiceService.createPurchaseInvoice(command()));

        assertEquals("WH-004", ex.code());
        verify(purchaseInvoiceRepository, never()).persist(any(PurchaseInvoice.class));
        verify(purchaseInvoiceItemRepository, never()).persist(any(PurchaseInvoiceItem.class));
    }

    @Test
    void create_whenProductInItemInactive_throwsWH004() {
        Product inactive = activeProduct();
        inactive.isActive = false;
        when(currentUser.requireId()).thenReturn(USER_ID);
        when(supplierRepository.findById(SUPPLIER_ID)).thenReturn(activeSupplier());
        when(currencyRepository.findById(CURRENCY_ID)).thenReturn(activeCurrency());
        when(productRepository.list("id in ?1", Set.of(PRODUCT_ID))).thenReturn(List.of(inactive));

        ApiException ex = assertThrows(ApiException.class,
            () -> warehousePurchaseInvoiceService.createPurchaseInvoice(command()));

        assertEquals("WH-004", ex.code());
        verify(purchaseInvoiceRepository, never()).persist(any(PurchaseInvoice.class));
    }

    // ---------- duplicado pre-check (WH-002) -------------------------------------------

    @Test
    void create_whenDuplicateActive_throwsWH002_doesNotPersist() {
        when(currentUser.requireId()).thenReturn(USER_ID);
        when(supplierRepository.findById(SUPPLIER_ID)).thenReturn(activeSupplier());
        when(currencyRepository.findById(CURRENCY_ID)).thenReturn(activeCurrency());
        when(productRepository.list("id in ?1", Set.of(PRODUCT_ID))).thenReturn(List.of(activeProduct()));
        when(purchaseInvoiceRepository.existsActiveBySupplierAndNumber(SUPPLIER_ID, "F001-00123")).thenReturn(true);

        ApiException ex = assertThrows(ApiException.class,
            () -> warehousePurchaseInvoiceService.createPurchaseInvoice(command()));

        assertEquals("WH-002", ex.code());
        assertEquals(409, ex.status());
        verify(purchaseInvoiceRepository, never()).persist(any(PurchaseInvoice.class));
    }

    // ---------- race condition: traducción del UNIQUE parcial (WH-002) -----------------

    @Test
    void create_whenFlushThrowsConstraintViolationOnActiveIndex_translatesToWH002() {
        CreateWarehousePurchaseInvoiceCommand cmd = command();
        PurchaseInvoice invoice = invoiceEntity();
        when(currentUser.requireId()).thenReturn(USER_ID);
        when(supplierRepository.findById(SUPPLIER_ID)).thenReturn(activeSupplier());
        when(currencyRepository.findById(CURRENCY_ID)).thenReturn(activeCurrency());
        when(productRepository.list("id in ?1", Set.of(PRODUCT_ID))).thenReturn(List.of(activeProduct()));
        when(purchaseInvoiceRepository.existsActiveBySupplierAndNumber(SUPPLIER_ID, "F001-00123")).thenReturn(false);
        when(warehousePurchaseInvoiceServiceMapper.toPurchaseInvoiceEntity(cmd, USER_ID)).thenReturn(invoice);
        doThrow(wrapConstraint("uq_purchase_invoices_active")).when(purchaseInvoiceRepository).flush();

        ApiException ex = assertThrows(ApiException.class,
            () -> warehousePurchaseInvoiceService.createPurchaseInvoice(cmd));

        assertEquals("WH-002", ex.code());
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
        ListWarehousePurchaseInvoicesQuery query =
            new ListWarehousePurchaseInvoicesQuery(null, null, null, null, null, 0, 20);
        PurchaseInvoice invoice = invoiceEntity();
        when(purchaseInvoiceRepository.searchPaged(query)).thenReturn(List.of(invoice));
        when(purchaseInvoiceRepository.countSearch(query)).thenReturn(1L);
        when(supplierRepository.list("id in ?1", Set.of(SUPPLIER_ID))).thenReturn(List.of(activeSupplier()));
        when(currencyRepository.list("id in ?1", Set.of(CURRENCY_ID))).thenReturn(List.of(activeCurrency()));
        when(userLookup.requireAllById(Set.of(USER_ID))).thenReturn(Map.of(USER_ID, userResponse()));
        when(purchaseInvoiceItemRepository.aggregateByInvoiceIds(Set.of(INVOICE_ID)))
            .thenReturn(Map.of(INVOICE_ID,
                new PurchaseInvoiceItemRepository.InvoiceAggregate(2, new BigDecimal("894.00"))));

        PageResponse<?> response = warehousePurchaseInvoiceService.listPurchaseInvoices(query);

        assertEquals(1, response.content().size());
        assertEquals(1L, response.totalElements());
    }

    @Test
    void list_empty_returnsEmptyPageWithoutFurtherLookups() {
        ListWarehousePurchaseInvoicesQuery query =
            new ListWarehousePurchaseInvoicesQuery(null, SUPPLIER_ID, null, null, null, 0, 20);
        when(purchaseInvoiceRepository.searchPaged(query)).thenReturn(List.of());
        when(purchaseInvoiceRepository.countSearch(query)).thenReturn(0L);

        PageResponse<?> response = warehousePurchaseInvoiceService.listPurchaseInvoices(query);

        assertTrue(response.content().isEmpty());
        assertEquals(0L, response.totalElements());
        verify(supplierRepository, never()).list(any(String.class), any(java.util.Collection.class));
    }

    // ========== A9: get / update / cancel =====================================

    private PurchaseInvoice cancelledInvoice() {
        PurchaseInvoice invoice = invoiceEntity();
        invoice.status = "CANCELLED";
        return invoice;
    }

    private UpdateWarehousePurchaseInvoiceCommand updateCommand(String ifMatch, BigDecimal quantity) {
        return new UpdateWarehousePurchaseInvoiceCommand(
            INVOICE_ID, ifMatch, "F001-00123", LocalDate.parse("2026-07-05"), null, CURRENCY_ID, null,
            List.of(new CreateWarehouseInvoiceItemCommand(PRODUCT_ID, quantity, new BigDecimal("1.00"))),
            "Motivo de edición suficientemente largo");
    }

    private com.scaramutti.tms.shared.exception.ApiException assertApi(Runnable action) {
        return assertThrows(com.scaramutti.tms.shared.exception.ApiException.class, action::run);
    }

    // ---------- GET ----------

    @Test
    void get_nonexistentId_throwsWH003() {
        when(purchaseInvoiceRepository.findByIdOptional(INVOICE_ID)).thenReturn(Optional.empty());

        var ex = assertApi(() -> warehousePurchaseInvoiceService.getPurchaseInvoice(INVOICE_ID));

        assertEquals("WH-003", ex.code());
        assertEquals(404, ex.status());
    }

    // ---------- UPDATE ----------

    @Test
    void update_nonexistentId_throwsWH003() {
        when(currentUser.requireId()).thenReturn(USER_ID);
        when(purchaseInvoiceRepository.findByIdOptional(INVOICE_ID)).thenReturn(Optional.empty());

        var ex = assertApi(() -> warehousePurchaseInvoiceService.updatePurchaseInvoice(
            updateCommand("\"x\"", new BigDecimal("1"))));

        assertEquals("WH-003", ex.code());
        verify(purchaseInvoiceItemRepository, never()).deleteByInvoiceId(any());
    }

    @Test
    void update_cancelledInvoice_throwsWH008_beforeIfMatch() {
        when(currentUser.requireId()).thenReturn(USER_ID);
        when(purchaseInvoiceRepository.findByIdOptional(INVOICE_ID)).thenReturn(Optional.of(cancelledInvoice()));

        var ex = assertApi(() -> warehousePurchaseInvoiceService.updatePurchaseInvoice(
            updateCommand("\"stale\"", new BigDecimal("1"))));

        assertEquals("WH-008", ex.code());
        assertEquals(409, ex.status());
    }

    @Test
    void update_staleIfMatch_throwsCOM004() {
        when(currentUser.requireId()).thenReturn(USER_ID);
        when(purchaseInvoiceRepository.findByIdOptional(INVOICE_ID)).thenReturn(Optional.of(invoiceEntity()));

        var ex = assertApi(() -> warehousePurchaseInvoiceService.updatePurchaseInvoice(
            updateCommand("\"not-the-current-version\"", new BigDecimal("1"))));

        assertEquals("COM-004", ex.code());
        assertEquals(412, ex.status());
        verify(purchaseInvoiceItemRepository, never()).deleteByInvoiceId(any());
    }

    @Test
    void update_currencyNotFound_throwsWH004() {
        PurchaseInvoice invoice = invoiceEntity();
        when(currentUser.requireId()).thenReturn(USER_ID);
        when(purchaseInvoiceRepository.findByIdOptional(INVOICE_ID)).thenReturn(Optional.of(invoice));
        when(currencyRepository.findById(CURRENCY_ID)).thenReturn(null);

        var ex = assertApi(() -> warehousePurchaseInvoiceService.updatePurchaseInvoice(
            updateCommand(Etag.of(invoice.updatedAt), new BigDecimal("1"))));

        assertEquals("WH-004", ex.code());
    }

    @Test
    void update_productNotFound_throwsWH004() {
        PurchaseInvoice invoice = invoiceEntity();
        when(currentUser.requireId()).thenReturn(USER_ID);
        when(purchaseInvoiceRepository.findByIdOptional(INVOICE_ID)).thenReturn(Optional.of(invoice));
        when(currencyRepository.findById(CURRENCY_ID)).thenReturn(activeCurrency());
        when(productRepository.list("id in ?1", Set.of(PRODUCT_ID))).thenReturn(List.of());

        var ex = assertApi(() -> warehousePurchaseInvoiceService.updatePurchaseInvoice(
            updateCommand(Etag.of(invoice.updatedAt), new BigDecimal("1"))));

        assertEquals("WH-004", ex.code());
        verify(purchaseInvoiceItemRepository, never()).deleteByInvoiceId(any());
    }

    @Test
    void update_duplicateNumberExcludingSelf_throwsWH002() {
        PurchaseInvoice invoice = invoiceEntity();
        when(currentUser.requireId()).thenReturn(USER_ID);
        when(purchaseInvoiceRepository.findByIdOptional(INVOICE_ID)).thenReturn(Optional.of(invoice));
        when(currencyRepository.findById(CURRENCY_ID)).thenReturn(activeCurrency());
        when(productRepository.list("id in ?1", Set.of(PRODUCT_ID))).thenReturn(List.of(activeProduct()));
        when(purchaseInvoiceRepository.existsActiveBySupplierAndNumberExcludingId(SUPPLIER_ID, "F001-00123", INVOICE_ID))
            .thenReturn(true);

        var ex = assertApi(() -> warehousePurchaseInvoiceService.updatePurchaseInvoice(
            updateCommand(Etag.of(invoice.updatedAt), new BigDecimal("1"))));

        assertEquals("WH-002", ex.code());
        verify(purchaseInvoiceItemRepository, never()).deleteByInvoiceId(any());
    }

    @Test
    void update_stockGuardNegative_throwsWH006_doesNotMutate() {
        PurchaseInvoice invoice = invoiceEntity();
        when(currentUser.requireId()).thenReturn(USER_ID);
        when(purchaseInvoiceRepository.findByIdOptional(INVOICE_ID)).thenReturn(Optional.of(invoice));
        when(currencyRepository.findById(CURRENCY_ID)).thenReturn(activeCurrency());
        when(productRepository.list("id in ?1", Set.of(PRODUCT_ID))).thenReturn(List.of(activeProduct()));
        when(purchaseInvoiceRepository.existsActiveBySupplierAndNumberExcludingId(SUPPLIER_ID, "F001-00123", INVOICE_ID))
            .thenReturn(false);
        // Contribución vieja 10, stock actual 1; bajar a 8 → 1 - 10 + 8 = -1 < 0
        when(purchaseInvoiceItemRepository.sumQuantityByProductForInvoice(INVOICE_ID))
            .thenReturn(Map.of(PRODUCT_ID, new BigDecimal("10")));
        when(productRepository.findStockByProductIds(anyCollection()))
            .thenReturn(Map.of(PRODUCT_ID, new ProductRepository.ProductStockView(new BigDecimal("1"), false)));
        when(productRepository.findById(PRODUCT_ID)).thenReturn(activeProduct());

        var ex = assertApi(() -> warehousePurchaseInvoiceService.updatePurchaseInvoice(
            updateCommand(Etag.of(invoice.updatedAt), new BigDecimal("8"))));

        assertEquals("WH-006", ex.code());
        assertEquals(409, ex.status());
        verify(purchaseInvoiceItemRepository, never()).deleteByInvoiceId(any());
        verify(auditLogRepository, never()).persist(any(com.scaramutti.tms.shared.entity.AuditLog.class));
    }
}
