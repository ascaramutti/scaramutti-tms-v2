package com.scaramutti.tms.warehouse.purchaseinvoice.service;

import com.scaramutti.tms.auth.dto.UserResponse;
import com.scaramutti.tms.auth.security.CurrentUser;
import com.scaramutti.tms.auth.service.UserLookup;
import com.scaramutti.tms.shared.dto.PageResponse;
import com.scaramutti.tms.shared.entity.AuditLog;
import com.scaramutti.tms.shared.entity.Currency;
import com.scaramutti.tms.shared.entity.Product;
import com.scaramutti.tms.shared.entity.PurchaseInvoice;
import com.scaramutti.tms.shared.entity.PurchaseInvoiceItem;
import com.scaramutti.tms.shared.entity.Supplier;
import com.scaramutti.tms.shared.entity.UnitOfMeasure;
import com.scaramutti.tms.shared.repository.AuditLogRepository;
import com.scaramutti.tms.shared.repository.CurrencyRepository;
import com.scaramutti.tms.shared.repository.ProductRepository;
import com.scaramutti.tms.shared.repository.PurchaseInvoiceItemRepository;
import com.scaramutti.tms.shared.repository.PurchaseInvoiceRepository;
import com.scaramutti.tms.shared.repository.SupplierRepository;
import com.scaramutti.tms.shared.repository.UnitOfMeasureRepository;
import com.scaramutti.tms.shared.util.DateUtils;
import com.scaramutti.tms.shared.util.Etag;
import com.scaramutti.tms.warehouse.WarehouseError;
import com.scaramutti.tms.warehouse.model.AuditChangeType;
import com.scaramutti.tms.warehouse.model.AuditEntityType;
import com.scaramutti.tms.warehouse.model.WarehouseRecordStatus;
import com.scaramutti.tms.warehouse.product.dto.WarehouseProductSummary;
import com.scaramutti.tms.warehouse.purchaseinvoice.dto.WarehouseEditTrace;
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
import com.scaramutti.tms.warehouse.purchaseinvoice.service.cmd.UpdateWarehousePurchaseInvoiceCommand;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.PersistenceException;
import jakarta.transaction.Transactional;
import org.hibernate.exception.ConstraintViolationException;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
    @Inject AuditLogRepository auditLogRepository;
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
        // Factura recién creada: nunca editada (lastEdit null) ni anulada (cancelledBy null).
        return toResponse(invoice, supplier, currency, items, productsById, unitsById,
            userLookup.require(userId), null, null);
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
        purchaseInvoiceRepository.persist(invoice);
        flushInvoiceOrTranslateDuplicate(invoice);
    }

    /**
     * Fuerza el INSERT/UPDATE dentro del try y traduce la violación del UNIQUE parcial a
     * WH-002 (lo usa el alta tras el persist y la edición al renombrar el número).
     */
    private void flushInvoiceOrTranslateDuplicate(PurchaseInvoice invoice) {
        try {
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
        Map<Integer, Product> productsById, Map<Integer, UnitOfMeasure> unitsById, UserResponse registeredBy,
        WarehouseEditTrace lastEdit, UserResponse cancelledBy
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
            cancelledBy,
            invoice.cancelledAt,
            lastEdit,
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

    // ========== A9: detalle, edición y anulación ==============================

    /** Detalle de una entrada (GET /{id}). Read-only. 404 WH-003 si no existe. */
    public WarehousePurchaseInvoiceResponse getPurchaseInvoice(Integer id) {
        return assembleResponse(loadInvoiceOrThrow(id));
    }

    /**
     * Edición de una entrada (PUT /{id}). Orden de validación (calca A5 + A8):
     * 404 WH-003 → WH-008 (anulada) → 412 If-Match → 400 WH-004 (moneda/productos) →
     * 409 WH-002 (nº duplicado activo, excluyéndose) → 409 WH-006 (guarda de stock) →
     * reemplazo de ítems + mutación de cabecera + FIELD_EDIT por campo cambiado.
     */
    @Transactional
    public WarehousePurchaseInvoiceResponse updatePurchaseInvoice(UpdateWarehousePurchaseInvoiceCommand command) {
        Integer userId = currentUser.requireId();
        PurchaseInvoice invoice = loadInvoiceOrThrow(command.invoiceId());
        rejectIfCancelled(invoice);
        Etag.verify(command.ifMatch(), invoice.updatedAt);

        Currency newCurrency = requireActiveCurrency(command.currencyId());
        requireActiveProducts(command.items());
        if (purchaseInvoiceRepository.existsActiveBySupplierAndNumberExcludingId(
                invoice.supplierId, command.invoiceNumber(), invoice.id)) {
            throw WarehouseError.PURCHASE_INVOICE_DUPLICATED_ACTIVE.toException();
        }
        guardEditKeepsStockNonNegative(invoice, command.items());

        // Snapshot viejo para el diff de auditoría (ANTES de mutar / borrar ítems).
        String oldNumber = invoice.invoiceNumber;
        LocalDate oldDate = invoice.invoiceDate;
        String oldGuide = invoice.guideNumber;
        String oldObs = invoice.observations;
        String oldCurrencyCode = currencyRepository.findById(invoice.currencyId).code;
        String oldItemsCanonical = canonicalItemsFromEntities(
            purchaseInvoiceItemRepository.list("invoiceId = ?1", invoice.id));

        // Reemplazo de ítems (delete + insert en la misma tx). El flush del delete corre
        // antes de los inserts nuevos.
        purchaseInvoiceItemRepository.deleteByInvoiceId(invoice.id);
        purchaseInvoiceItemRepository.flush();
        command.items().forEach(item -> purchaseInvoiceItemRepository.persist(
            warehousePurchaseInvoiceServiceMapper.toPurchaseInvoiceItemEntity(item, invoice.id)));

        // Mutación de cabecera. Se bumpea updatedAt explícitamente: cada PUT reemplaza los
        // ítems (un cambio de estado de la factura), pero si SOLO cambiaron los ítems la
        // fila de cabecera no queda "dirty" y el @PreUpdate no correría → el ETag no
        // reflejaría la edición y dos ediciones de ítems concurrentes compartirían versión.
        invoice.invoiceNumber = command.invoiceNumber();
        invoice.invoiceDate = command.invoiceDate();
        invoice.guideNumber = command.guideNumber();
        invoice.currencyId = command.currencyId();
        invoice.observations = command.observations();
        invoice.updatedAt = DateUtils.nowUtcMicros();
        flushInvoiceOrTranslateDuplicate(invoice);

        String reason = command.reason();
        logFieldEdit(invoice.id, "invoiceNumber", "Número de factura", oldNumber, command.invoiceNumber(), reason, userId);
        logFieldEdit(invoice.id, "invoiceDate", "Fecha de factura", str(oldDate), str(command.invoiceDate()), reason, userId);
        logFieldEdit(invoice.id, "guideNumber", "Número de guía", oldGuide, command.guideNumber(), reason, userId);
        logFieldEdit(invoice.id, "currency", "Moneda", oldCurrencyCode, newCurrency.code, reason, userId);
        logFieldEdit(invoice.id, "observations", "Observaciones", oldObs, command.observations(), reason, userId);
        logFieldEdit(invoice.id, "items", "Ítems", oldItemsCanonical, canonicalItemsFromCommands(command.items()), reason, userId);

        return assembleResponse(invoice);
    }

    // ---------- Carga / precondiciones ----------------------------------------

    private PurchaseInvoice loadInvoiceOrThrow(Integer id) {
        return purchaseInvoiceRepository.findByIdOptional(id)
            .orElseThrow(WarehouseError.PURCHASE_INVOICE_NOT_FOUND::toException);
    }

    private void rejectIfCancelled(PurchaseInvoice invoice) {
        if (WarehouseRecordStatus.CANCELLED.name().equals(invoice.status)) {
            throw WarehouseError.PURCHASE_INVOICE_ALREADY_CANCELLED.toException();
        }
    }

    // ---------- Guardas de stock (WH-006 / WH-007) ----------------------------

    /**
     * WH-006: tras reemplazar los ítems, ningún producto afectado (unión de viejos y
     * nuevos) puede quedar con stock negativo. La VIEW {@code product_stock} ya incluye la
     * apertura y los movimientos ACTIVE (incluida esta factura), así que
     * {@code stock_después = stock_actual − contribución_vieja + contribución_nueva}
     * (la apertura cuenta como stock disponible, ratificado por el dueño 2026-07-14).
     */
    private void guardEditKeepsStockNonNegative(PurchaseInvoice invoice, List<CreateWarehouseInvoiceItemCommand> newItems) {
        Map<Integer, BigDecimal> oldContrib = purchaseInvoiceItemRepository.sumQuantityByProductForInvoice(invoice.id);
        Map<Integer, BigDecimal> newContrib = contributionByProduct(newItems);
        Set<Integer> affected = new HashSet<>(oldContrib.keySet());
        affected.addAll(newContrib.keySet());

        Map<Integer, ProductRepository.ProductStockView> stockById = productRepository.findStockByProductIds(affected);
        for (Integer productId : affected) {
            BigDecimal after = currentStockOrZero(stockById, productId)
                .subtract(oldContrib.getOrDefault(productId, BigDecimal.ZERO))
                .add(newContrib.getOrDefault(productId, BigDecimal.ZERO));
            if (after.signum() < 0) {
                throw stockGuardException(WarehouseError.PURCHASE_INVOICE_EDIT_NEGATIVE_STOCK, productId, after);
            }
        }
    }

    private Map<Integer, BigDecimal> contributionByProduct(List<CreateWarehouseInvoiceItemCommand> items) {
        Map<Integer, BigDecimal> byProduct = new LinkedHashMap<>();
        for (CreateWarehouseInvoiceItemCommand item : items) {
            byProduct.merge(item.productId(), item.quantity(), BigDecimal::add);
        }
        return byProduct;
    }

    private BigDecimal currentStockOrZero(Map<Integer, ProductRepository.ProductStockView> stockById, Integer productId) {
        ProductRepository.ProductStockView view = stockById.get(productId);
        return view != null ? view.stock() : BigDecimal.ZERO;
    }

    /** Arma la excepción de la guarda nombrando el producto y el stock resultante. */
    private RuntimeException stockGuardException(WarehouseError error, Integer productId, BigDecimal resultingStock) {
        Product product = productRepository.findById(productId);
        String name = product != null ? product.name : ("#" + productId);
        return error.toException(
            "El stock de '" + name + "' quedaría en " + resultingStock.toPlainString() + ".");
    }

    // ---------- Auditoría (audit_logs) ----------------------------------------

    private void logFieldEdit(Integer invoiceId, String fieldName, String fieldLabel,
                              String oldValue, String newValue, String reason, Integer userId) {
        if (Objects.equals(oldValue, newValue)) {
            return;
        }
        AuditLog log = new AuditLog();
        log.entityType = AuditEntityType.PURCHASE_INVOICE.name();
        log.entityId = invoiceId;
        log.changeType = AuditChangeType.FIELD_EDIT.name();
        log.fieldName = fieldName;
        log.fieldLabel = fieldLabel;
        log.oldValue = oldValue;
        log.newValue = newValue;
        log.reason = reason;
        log.changedBy = userId;
        auditLogRepository.persist(log);
    }

    private WarehouseEditTrace loadLastEdit(Integer invoiceId) {
        return auditLogRepository.findLastFieldEdit(AuditEntityType.PURCHASE_INVOICE, invoiceId)
            .map(log -> new WarehouseEditTrace(userLookup.require(log.changedBy), log.loggedAt, log.reason))
            .orElse(null);
    }

    private String str(LocalDate date) {
        return date != null ? date.toString() : null;
    }

    /** Representación canónica y comparable de los ítems (ordenada), para el diff de auditoría. */
    private String canonicalItemsFromEntities(List<PurchaseInvoiceItem> items) {
        return items.stream()
            .map(item -> canonicalItem(item.productId, item.quantity, item.unitPrice))
            .sorted().collect(Collectors.joining(","));
    }

    private String canonicalItemsFromCommands(List<CreateWarehouseInvoiceItemCommand> items) {
        return items.stream()
            .map(item -> canonicalItem(item.productId(), item.quantity(), item.unitPrice()))
            .sorted().collect(Collectors.joining(","));
    }

    private String canonicalItem(Integer productId, BigDecimal quantity, BigDecimal unitPrice) {
        return productId + ":" + quantity.stripTrailingZeros().toPlainString()
            + "@" + unitPrice.stripTrailingZeros().toPlainString();
    }

    // ---------- Ensamblado del detalle (GET/PUT/cancel) -----------------------

    /** Carga los ítems de la factura y arma el response completo (incluye lastEdit + anulación). */
    private WarehousePurchaseInvoiceResponse assembleResponse(PurchaseInvoice invoice) {
        List<PurchaseInvoiceItem> items = purchaseInvoiceItemRepository.list(
            "invoiceId = ?1", Sort.by("id"), invoice.id);
        Supplier supplier = supplierRepository.findById(invoice.supplierId);
        Currency currency = currencyRepository.findById(invoice.currencyId);
        Map<Integer, Product> productsById = productRepository
            .list("id in ?1", items.stream().map(item -> item.productId).collect(Collectors.toSet()))
            .stream().collect(Collectors.toMap(product -> product.id, product -> product));
        Map<Integer, UnitOfMeasure> unitsById = loadUnitsFor(productsById.values());
        UserResponse registeredBy = userLookup.require(invoice.registeredBy);
        WarehouseEditTrace lastEdit = loadLastEdit(invoice.id);
        UserResponse cancelledBy = invoice.cancelledBy != null ? userLookup.require(invoice.cancelledBy) : null;

        return toResponse(invoice, supplier, currency, items, productsById, unitsById, registeredBy, lastEdit, cancelledBy);
    }
}
