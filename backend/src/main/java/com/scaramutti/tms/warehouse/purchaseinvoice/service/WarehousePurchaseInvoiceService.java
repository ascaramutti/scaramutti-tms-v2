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
import com.scaramutti.tms.shared.repository.CurrencyRepository;
import com.scaramutti.tms.shared.repository.ProductRepository;
import com.scaramutti.tms.shared.repository.PurchaseInvoiceItemRepository;
import com.scaramutti.tms.shared.repository.PurchaseInvoiceRepository;
import com.scaramutti.tms.shared.repository.SupplierRepository;
import com.scaramutti.tms.shared.repository.UnitOfMeasureRepository;
import com.scaramutti.tms.warehouse.WarehouseError;
import com.scaramutti.tms.warehouse.model.WarehouseRecordStatus;
import com.scaramutti.tms.warehouse.product.dto.WarehouseProductSummary;
import com.scaramutti.tms.warehouse.purchaseinvoice.dto.WarehouseInvoiceCurrencyRef;
import com.scaramutti.tms.warehouse.purchaseinvoice.dto.WarehouseInvoiceItemResponse;
import com.scaramutti.tms.warehouse.purchaseinvoice.dto.WarehouseInvoiceSupplierRef;
import com.scaramutti.tms.warehouse.purchaseinvoice.dto.WarehouseInvoiceSupplierSummaryRef;
import com.scaramutti.tms.warehouse.purchaseinvoice.dto.WarehousePurchaseInvoiceResponse;
import com.scaramutti.tms.warehouse.purchaseinvoice.dto.WarehousePurchaseInvoiceSummary;
import com.scaramutti.tms.warehouse.purchaseinvoice.mapper.WarehousePurchaseInvoiceServiceMapper;
import com.scaramutti.tms.warehouse.purchaseinvoice.service.cmd.CreateWarehouseInvoiceItemCommand;
import com.scaramutti.tms.warehouse.purchaseinvoice.service.cmd.CreateWarehousePurchaseInvoiceCommand;
import com.scaramutti.tms.warehouse.purchaseinvoice.service.cmd.ListWarehousePurchaseInvoicesQuery;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.PersistenceException;
import jakarta.transaction.Transactional;
import org.hibernate.exception.ConstraintViolationException;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Entradas de almacén (facturas de compra): alta transaccional (WH-004/WH-002) y
 * listado paginado. Es el primer writer multi-entidad del módulo: la factura y sus
 * ítems se persisten en la MISMA transacción, así cada ítem suma stock (vía la VIEW
 * {@code stock_movements}) de forma atómica — si algo falla, no queda ni cabecera ni
 * ítems ni stock.
 *
 * <p>Alta, orden de validación: 400 WH-004 (proveedor, moneda y cada producto,
 * activos) → 409 WH-002 (nº duplicado entre ACTIVAS, happy path) → persist
 * (traduciendo la race del UNIQUE parcial a WH-002, mismo patrón que los otros
 * writers).
 *
 * <p>Listado: {@code searchPaged} hidrata las entities de la página y el service las
 * enriquece batch (proveedor, moneda, registeredBy, itemsCount/total) sin N+1 por
 * page size, mismo patrón que {@code WarehouseOpeningBalanceService}.
 */
@ApplicationScoped
public class WarehousePurchaseInvoiceService {

    private static final Logger LOG = Logger.getLogger(WarehousePurchaseInvoiceService.class);

    @Inject PurchaseInvoiceRepository purchaseInvoiceRepository;
    @Inject PurchaseInvoiceItemRepository purchaseInvoiceItemRepository;
    @Inject SupplierRepository supplierRepository;
    @Inject CurrencyRepository currencyRepository;
    @Inject ProductRepository productRepository;
    @Inject UnitOfMeasureRepository unitOfMeasureRepository;
    @Inject UserLookup userLookup;
    @Inject CurrentUser currentUser;
    @Inject WarehousePurchaseInvoiceServiceMapper warehousePurchaseInvoiceServiceMapper;

    @Transactional
    public WarehousePurchaseInvoiceResponse createPurchaseInvoice(CreateWarehousePurchaseInvoiceCommand command) {
        Integer userId = currentUser.requireId();

        Supplier supplier = requireActiveSupplier(command.supplierId());
        Currency currency = requireActiveCurrency(command.currencyId());
        Map<Integer, Product> productsById = requireActiveProducts(command.items());
        rejectDuplicateActive(command.supplierId(), command.invoiceNumber());

        PurchaseInvoice invoice = warehousePurchaseInvoiceServiceMapper.toPurchaseInvoiceEntity(command, userId);
        persistInvoiceOrTranslateDuplicate(invoice);

        List<PurchaseInvoiceItem> items = command.items().stream()
            .map(item -> warehousePurchaseInvoiceServiceMapper.toPurchaseInvoiceItemEntity(item, invoice.id))
            .toList();
        items.forEach(purchaseInvoiceItemRepository::persist);

        Map<Integer, UnitOfMeasure> unitsById = loadUnitsFor(productsById.values());
        return toResponse(invoice, supplier, currency, items, productsById, unitsById, userLookup.require(userId));
    }

    /**
     * Listado paginado (GET /warehouse/purchase-invoices). Read-only, sin
     * {@code @Transactional} (misma convención que los otros listados). Queries fijas
     * (page + count + suppliers + currencies + users + agregado de ítems), ninguna
     * N+1 por page size.
     */
    public PageResponse<WarehousePurchaseInvoiceSummary> listPurchaseInvoices(ListWarehousePurchaseInvoicesQuery query) {
        List<PurchaseInvoice> invoices = purchaseInvoiceRepository.searchPaged(query);
        long totalElements = purchaseInvoiceRepository.countSearch(query);

        if (invoices.isEmpty()) {
            return PageResponse.of(List.of(), query.page(), query.size(), totalElements);
        }

        Map<Integer, Supplier> suppliersById = supplierRepository
            .list("id in ?1", invoices.stream().map(pi -> pi.supplierId).collect(Collectors.toSet()))
            .stream().collect(Collectors.toMap(supplier -> supplier.id, supplier -> supplier));
        Map<Integer, Currency> currenciesById = currencyRepository
            .list("id in ?1", invoices.stream().map(pi -> pi.currencyId).collect(Collectors.toSet()))
            .stream().collect(Collectors.toMap(currency -> currency.id, currency -> currency));
        Map<Integer, UserResponse> usersById = userLookup.requireAllById(
            invoices.stream().map(pi -> pi.registeredBy).collect(Collectors.toSet()));
        Map<Integer, PurchaseInvoiceItemRepository.InvoiceAggregate> aggregatesByInvoiceId =
            purchaseInvoiceItemRepository.aggregateByInvoiceIds(invoices.stream().map(pi -> pi.id).collect(Collectors.toSet()));

        List<WarehousePurchaseInvoiceSummary> content = invoices.stream()
            .map(invoice -> toSummary(
                invoice, suppliersById.get(invoice.supplierId), currenciesById.get(invoice.currencyId),
                usersById.get(invoice.registeredBy), aggregatesByInvoiceId.get(invoice.id)))
            .toList();

        return PageResponse.of(content, query.page(), query.size(), totalElements);
    }

    // ---------- Validación de FK (WH-004) -------------------------------------

    private Supplier requireActiveSupplier(Integer supplierId) {
        Supplier supplier = supplierRepository.findById(supplierId);
        if (supplier == null || !Boolean.TRUE.equals(supplier.isActive)) {
            throw WarehouseError.SUPPLIER_NOT_FOUND_OR_INACTIVE.toException();
        }
        return supplier;
    }

    private Currency requireActiveCurrency(Integer currencyId) {
        Currency currency = currencyRepository.findById(currencyId);
        if (currency == null || !Boolean.TRUE.equals(currency.isActive)) {
            throw WarehouseError.CURRENCY_NOT_FOUND_OR_INACTIVE.toException();
        }
        return currency;
    }

    /**
     * Valida en batch que cada {@code productId} de los ítems exista y esté activo
     * (WH-004), sin N+1. Devuelve el mapa id → Product para reusarlo al ensamblar el
     * response (evita releer los mismos productos).
     */
    private Map<Integer, Product> requireActiveProducts(List<CreateWarehouseInvoiceItemCommand> items) {
        Set<Integer> productIds = items.stream()
            .map(CreateWarehouseInvoiceItemCommand::productId)
            .collect(Collectors.toSet());
        Map<Integer, Product> productsById = productRepository
            .list("id in ?1", productIds)
            .stream().collect(Collectors.toMap(product -> product.id, product -> product));

        for (Integer productId : productIds) {
            Product product = productsById.get(productId);
            if (product == null || !Boolean.TRUE.equals(product.isActive)) {
                throw WarehouseError.PRODUCT_NOT_FOUND_OR_INACTIVE.toException();
            }
        }
        return productsById;
    }

    // ---------- Anti-duplicado (WH-002) ---------------------------------------

    private void rejectDuplicateActive(Integer supplierId, String invoiceNumber) {
        if (purchaseInvoiceRepository.existsActiveBySupplierAndNumber(supplierId, invoiceNumber)) {
            throw WarehouseError.PURCHASE_INVOICE_DUPLICATED_ACTIVE.toException();
        }
    }

    /**
     * Cubre la race donde dos requests pasan {@code rejectDuplicateActive} a la vez
     * para el mismo proveedor+número: el índice UNIQUE parcial
     * {@code uq_purchase_invoices_active} (V002) garantiza que Postgres rechaza el
     * segundo INSERT ACTIVO. El {@code flush} fuerza el INSERT dentro de este try.
     */
    private void persistInvoiceOrTranslateDuplicate(PurchaseInvoice invoice) {
        try {
            purchaseInvoiceRepository.persist(invoice);
            purchaseInvoiceRepository.flush();
        } catch (PersistenceException ex) {
            ConstraintViolationException cve = extractConstraintViolation(ex);
            if (cve == null) {
                throw ex;
            }
            String constraintName = cve.getConstraintName();
            if (constraintName != null && constraintName.contains("purchase_invoices_active")) {
                LOG.warnf("Race condition: UNIQUE parcial violation [supplierId=%s, invoiceNumber=%s]",
                    invoice.supplierId, invoice.invoiceNumber);
                throw WarehouseError.PURCHASE_INVOICE_DUPLICATED_ACTIVE.toException();
            }
            LOG.errorf(ex, "Unhandled DB constraint violation [constraint=%s]", constraintName);
            throw ex;
        }
    }

    private ConstraintViolationException extractConstraintViolation(PersistenceException ex) {
        if (ex instanceof ConstraintViolationException cve) {
            return cve;
        }
        Throwable cause = ex.getCause();
        return (cause instanceof ConstraintViolationException cve) ? cve : null;
    }

    // ---------- Ensamblado del response ----------------------------------------

    private Map<Integer, UnitOfMeasure> loadUnitsFor(java.util.Collection<Product> products) {
        Set<Integer> unitIds = products.stream().map(product -> product.unitOfMeasureId).collect(Collectors.toSet());
        return unitOfMeasureRepository.list("id in ?1", unitIds)
            .stream().collect(Collectors.toMap(unit -> unit.id, unit -> unit));
    }

    private WarehousePurchaseInvoiceResponse toResponse(
        PurchaseInvoice invoice, Supplier supplier, Currency currency, List<PurchaseInvoiceItem> items,
        Map<Integer, Product> productsById, Map<Integer, UnitOfMeasure> unitsById, UserResponse registeredBy
    ) {
        List<WarehouseInvoiceItemResponse> itemResponses = items.stream()
            .map(item -> {
                Product product = productsById.get(item.productId);
                UnitOfMeasure unit = unitsById.get(product.unitOfMeasureId);
                return new WarehouseInvoiceItemResponse(
                    item.id,
                    new WarehouseProductSummary(product.id, product.code, product.name, unit.code),
                    item.quantity,
                    item.unitPrice,
                    item.quantity.multiply(item.unitPrice)
                );
            })
            .toList();
        BigDecimal total = itemResponses.stream()
            .map(WarehouseInvoiceItemResponse::subtotal)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        return new WarehousePurchaseInvoiceResponse(
            invoice.id,
            new WarehouseInvoiceSupplierRef(supplier.id, supplier.name, supplier.ruc),
            invoice.invoiceNumber,
            invoice.invoiceDate,
            invoice.guideNumber,
            new WarehouseInvoiceCurrencyRef(currency.id, currency.code, currency.symbol),
            invoice.observations,
            itemResponses,
            total,
            WarehouseRecordStatus.valueOf(invoice.status),
            invoice.cancelReason,
            null,
            invoice.cancelledAt,
            null,
            registeredBy,
            invoice.createdAt,
            invoice.updatedAt
        );
    }

    private WarehousePurchaseInvoiceSummary toSummary(
        PurchaseInvoice invoice, Supplier supplier, Currency currency, UserResponse registeredBy,
        PurchaseInvoiceItemRepository.InvoiceAggregate aggregate
    ) {
        int itemsCount = aggregate != null ? aggregate.itemsCount() : 0;
        BigDecimal total = aggregate != null ? aggregate.total() : BigDecimal.ZERO;
        return new WarehousePurchaseInvoiceSummary(
            invoice.id,
            new WarehouseInvoiceSupplierSummaryRef(supplier.id, supplier.name),
            invoice.invoiceNumber,
            invoice.invoiceDate,
            invoice.guideNumber,
            currency.code,
            itemsCount,
            total,
            WarehouseRecordStatus.valueOf(invoice.status),
            invoice.cancelReason,
            registeredBy,
            invoice.createdAt
        );
    }
}
