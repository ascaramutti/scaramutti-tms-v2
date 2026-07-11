package com.scaramutti.tms.warehouse.product.service;

import com.scaramutti.tms.auth.dto.UserResponse;
import com.scaramutti.tms.auth.security.CurrentUser;
import com.scaramutti.tms.shared.dto.PageResponse;
import com.scaramutti.tms.shared.entity.Product;
import com.scaramutti.tms.shared.entity.ProductCategory;
import com.scaramutti.tms.shared.entity.UnitOfMeasure;
import com.scaramutti.tms.shared.repository.ProductCategoryRepository;
import com.scaramutti.tms.shared.repository.ProductRepository;
import com.scaramutti.tms.shared.repository.ProductRepository.ProductStockView;
import com.scaramutti.tms.shared.repository.UnitOfMeasureRepository;
import com.scaramutti.tms.auth.service.UserLookup;
import com.scaramutti.tms.warehouse.WarehouseError;
import com.scaramutti.tms.warehouse.product.WarehouseProductEtag;
import com.scaramutti.tms.warehouse.product.dto.WarehouseProductResponse;
import com.scaramutti.tms.warehouse.product.dto.WarehouseProductResponse.CategoryRef;
import com.scaramutti.tms.warehouse.product.dto.WarehouseProductResponse.UnitOfMeasureRef;
import com.scaramutti.tms.warehouse.product.dto.WarehouseProductStockResponse;
import com.scaramutti.tms.warehouse.product.mapper.WarehouseProductServiceMapper;
import com.scaramutti.tms.warehouse.product.service.cmd.CreateWarehouseProductCommand;
import com.scaramutti.tms.warehouse.product.service.cmd.ListWarehouseProductsQuery;
import com.scaramutti.tms.warehouse.product.service.cmd.UpdateWarehouseProductCommand;
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
 * Productos de almacén: alta (RN-WH6/WH10/WH11) y listado paginado con stock.
 *
 * <p>Alta: un producto nuevo nace con stock 0 (su primera factura es su primer
 * movimiento), así que su lowStock se deriva de esa constante trivial; la
 * definición CANÓNICA de lowStock (RN-WH11) vive en la VIEW {@code product_stock}
 * y es la que consume el listado.
 *
 * <p>Listado: {@code searchPaged} hidrata las entities de la página y el service
 * las enriquece batch (categoría, unidad, stock/lowStock de la VIEW, createdBy)
 * sin N+1 por page size, mismo patrón que {@code ListQuotationsService}.
 *
 * <p>Duplicados (alta): pre-check happy path ({@code existsBy...}) + traducción de
 * la constraint en la race ({@code persistOrTranslateDuplicate}), mismo patrón que
 * {@code WarehouseSupplierService}. El guardián real es el índice único de BD.
 */
@ApplicationScoped
public class WarehouseProductService {

    private static final Logger LOG = Logger.getLogger(WarehouseProductService.class);

    private static final BigDecimal INITIAL_STOCK = BigDecimal.ZERO;

    @Inject ProductRepository productRepository;
    @Inject ProductCategoryRepository productCategoryRepository;
    @Inject UnitOfMeasureRepository unitOfMeasureRepository;
    @Inject UserLookup userLookup;
    @Inject CurrentUser currentUser;
    @Inject WarehouseProductCodeGeneratorService warehouseProductCodeGeneratorService;
    @Inject WarehouseProductServiceMapper warehouseProductServiceMapper;

    /**
     * Listado paginado con stock (GET /warehouse/products). Read-only, sin
     * {@code @Transactional} (misma convención que ListQuotationsService).
     * 6 queries fijas (page + count + categorías + unidades + stock + users),
     * ninguna N+1 por page size.
     */
    public PageResponse<WarehouseProductResponse> listProducts(ListWarehouseProductsQuery query) {
        List<Product> products = productRepository.searchPaged(query);
        long totalElements = productRepository.countSearch(query);

        if (products.isEmpty()) {
            return PageResponse.of(List.of(), query.page(), query.size(), totalElements);
        }

        Map<Integer, ProductCategory> categoriesById = productCategoryRepository
            .list("id in ?1", products.stream().map(p -> p.categoryId).collect(Collectors.toSet()))
            .stream().collect(Collectors.toMap(category -> category.id, category -> category));
        Map<Integer, UnitOfMeasure> unitsById = unitOfMeasureRepository
            .list("id in ?1", products.stream().map(p -> p.unitOfMeasureId).collect(Collectors.toSet()))
            .stream().collect(Collectors.toMap(unit -> unit.id, unit -> unit));
        Map<Integer, ProductStockView> stockById = productRepository.findStockByProductIds(
            products.stream().map(p -> p.id).collect(Collectors.toSet()));
        Map<Integer, UserResponse> usersById = userLookup.requireAllById(
            products.stream().map(p -> p.createdBy).collect(Collectors.toSet()));

        List<WarehouseProductResponse> content = products.stream()
            .map(product -> toResponse(
                product,
                categoriesById.get(product.categoryId),
                unitsById.get(product.unitOfMeasureId),
                stockOf(stockById, product),
                usersById.get(product.createdBy)
            ))
            .toList();

        return PageResponse.of(content, query.page(), query.size(), totalElements);
    }

    /**
     * Stock/lowStock de un producto desde la VIEW. Fallback defensivo (stock 0,
     * lowStock recomputado) por si la fila de la VIEW faltara — no debería, es un
     * LEFT JOIN sobre todos los productos, pero evita un NPE si algo cambiara.
     */
    private ProductStockView stockOf(Map<Integer, ProductStockView> stockById, Product product) {
        ProductStockView stock = stockById.get(product.id);
        return stock != null
            ? stock
            : new ProductStockView(INITIAL_STOCK, INITIAL_STOCK.compareTo(product.minStock) < 0);
    }

    /**
     * Detalle de un producto (GET /warehouse/products/{id}), con stock actual de
     * la VIEW. Read-only, sin {@code @Transactional} (misma convención que
     * listProducts).
     */
    public WarehouseProductResponse getById(Integer id) {
        Product product = productRepository.findByIdOptional(id)
            .orElseThrow(WarehouseError.PRODUCT_NOT_FOUND::toException);

        ProductCategory category = productCategoryRepository.findById(product.categoryId);
        UnitOfMeasure unitOfMeasure = unitOfMeasureRepository.findById(product.unitOfMeasureId);
        ProductStockView stock = currentStockOf(product);

        return toResponse(product, category, unitOfMeasure, stock, userLookup.require(product.createdBy));
    }

    /**
     * Stock/lowStock actual de un producto individual desde la VIEW (GET by id y
     * PUT). Fallback defensivo igual que {@link #stockOf}, sin necesitar un mapa.
     */
    private ProductStockView currentStockOf(Product product) {
        ProductStockView stock = productRepository.findStockByProductId(product.id);
        return stock != null
            ? stock
            : new ProductStockView(INITIAL_STOCK, INITIAL_STOCK.compareTo(product.minStock) < 0);
    }

    @Transactional
    public WarehouseProductResponse createProduct(CreateWarehouseProductCommand createWarehouseProductCommand) {
        Integer userId = currentUser.requireId();

        ProductCategory category = requireActiveCategory(createWarehouseProductCommand.categoryId());
        UnitOfMeasure unitOfMeasure = requireActiveUnitOfMeasure(createWarehouseProductCommand.unitOfMeasureId());
        rejectDuplicateIdentity(createWarehouseProductCommand);

        String code = warehouseProductCodeGeneratorService.nextCode();
        Product product = warehouseProductServiceMapper.toProductEntity(
            createWarehouseProductCommand, code, userId
        );
        persistOrTranslateDuplicate(product);

        // Un producto recién creado nace con stock 0 (RN-WH6): su lowStock se
        // deriva de esa constante trivial. La definición canónica de lowStock
        // (RN-WH11) vive en la VIEW product_stock, que consume el listado.
        ProductStockView initialStock = new ProductStockView(
            INITIAL_STOCK, INITIAL_STOCK.compareTo(product.minStock) < 0);
        return toResponse(product, category, unitOfMeasure, initialStock, userLookup.require(userId));
    }

    /**
     * Edición de catálogo (PUT /warehouse/products/{id}): nombre, categoría,
     * marca, nº de parte, atributos, minStock, observaciones, isActive. La
     * unidad de medida NO viaja (P-1, inmutable). Orden: 404 → 412 (If-Match) →
     * 400 WH-004 (categoría) → 409 WH-010 (identidad, happy path) → aplicar →
     * flush (traduciendo la race a WH-010) → reensamblar response.
     */
    @Transactional
    public WarehouseProductResponse updateProduct(Integer id, String ifMatch, UpdateWarehouseProductCommand command) {
        Product product = productRepository.findByIdOptional(id)
            .orElseThrow(WarehouseError.PRODUCT_NOT_FOUND::toException);

        WarehouseProductEtag.verify(ifMatch, product);

        ProductCategory category = requireActiveCategory(command.categoryId());
        rejectDuplicateIdentityExcludingSelf(command, id);

        warehouseProductServiceMapper.applyUpdate(product, command);
        flushOrTranslateDuplicate(product);

        // La unidad de medida es inmutable (P-1) y puede estar inactiva desde que
        // el producto la fijó al crear: se busca SIN el filtro isActive del alta.
        UnitOfMeasure unitOfMeasure = unitOfMeasureRepository.findById(product.unitOfMeasureId);
        ProductStockView stock = currentStockOf(product);
        return toResponse(product, category, unitOfMeasure, stock, userLookup.require(product.createdBy));
    }

    /**
     * Stock puntual de un producto (GET /warehouse/products/{id}/stock), para el
     * form de retiro. Read-only, sin {@code @Transactional}. La validación
     * AUTORITATIVA del retiro (409 WH-001, RN-WH2) sigue viviendo en su propia
     * transacción; esto es solo lectura para la UX.
     */
    public WarehouseProductStockResponse getStock(Integer id) {
        Product product = productRepository.findByIdOptional(id)
            .orElseThrow(WarehouseError.PRODUCT_NOT_FOUND::toException);
        ProductStockView stock = currentStockOf(product);
        return new WarehouseProductStockResponse(product.id, stock.stock(), product.minStock, stock.lowStock());
    }

    // ---------- Validación de FKs (WH-004) -----------------------------------

    private ProductCategory requireActiveCategory(Integer categoryId) {
        ProductCategory category = productCategoryRepository.findById(categoryId);
        if (category == null || !Boolean.TRUE.equals(category.isActive)) {
            throw WarehouseError.PRODUCT_CATEGORY_NOT_FOUND.toException();
        }
        return category;
    }

    private UnitOfMeasure requireActiveUnitOfMeasure(Integer unitOfMeasureId) {
        UnitOfMeasure unitOfMeasure = unitOfMeasureRepository.findById(unitOfMeasureId);
        if (unitOfMeasure == null || !Boolean.TRUE.equals(unitOfMeasure.isActive)) {
            throw WarehouseError.PRODUCT_UNIT_NOT_FOUND.toException();
        }
        return unitOfMeasure;
    }

    // ---------- Anti-duplicado de identidad (WH-010) -------------------------

    private void rejectDuplicateIdentity(CreateWarehouseProductCommand createWarehouseProductCommand) {
        if (productRepository.existsByIdentityIgnoreCase(
                createWarehouseProductCommand.name(),
                createWarehouseProductCommand.brand(),
                createWarehouseProductCommand.partNumber())) {
            throw WarehouseError.PRODUCT_IDENTITY_DUPLICATED.toException();
        }
    }

    /**
     * Igual que {@link #rejectDuplicateIdentity} pero EXCLUYENDO el propio id
     * (PUT): editar un producto sin cambiar su identidad no debe chocar contra
     * si mismo.
     */
    private void rejectDuplicateIdentityExcludingSelf(UpdateWarehouseProductCommand command, Integer id) {
        if (productRepository.existsByIdentityIgnoreCaseExcludingId(
                command.name(), command.brand(), command.partNumber(), id)) {
            throw WarehouseError.PRODUCT_IDENTITY_DUPLICATED.toException();
        }
    }

    /**
     * Cubre la race donde dos requests pasan rejectDuplicateIdentity simultáneamente
     * con la misma identidad: el índice único uq_products_identity (V002) garantiza
     * que Postgres rechaza el segundo INSERT. El `code` es system-owned (correlativo
     * bajo advisory lock), nunca colisiona, así que no necesita traducción.
     */
    private void persistOrTranslateDuplicate(Product product) {
        try {
            productRepository.persist(product);
            productRepository.flush();
        } catch (PersistenceException ex) {
            translateDuplicateOrRethrow(ex, product);
        }
    }

    /**
     * Camino de update: la entity ya está gestionada (no hace falta persist),
     * solo flush para forzar el UPDATE dentro de la tx y capturar la race de
     * identidad, con la MISMA traducción que el alta.
     */
    private void flushOrTranslateDuplicate(Product product) {
        try {
            productRepository.flush();
        } catch (PersistenceException ex) {
            translateDuplicateOrRethrow(ex, product);
        }
    }

    /**
     * Cuerpo compartido de traducción de la constraint de identidad, extraído de
     * {@code persistOrTranslateDuplicate} (create) y {@code flushOrTranslateDuplicate}
     * (update): mismo catch, mismo criterio (constraint "identity" → WH-010),
     * mismo re-throw para cualquier otra violación no contemplada.
     */
    private void translateDuplicateOrRethrow(PersistenceException ex, Product product) {
        ConstraintViolationException cve = extractConstraintViolation(ex);
        if (cve == null) {
            throw ex;
        }
        String constraintName = cve.getConstraintName();
        if (constraintName != null && constraintName.contains("identity")) {
            LOG.warnf("Race condition: UNIQUE identity violation [name=%s brand=%s partNumber=%s]",
                product.name, product.brand, product.partNumber);
            throw WarehouseError.PRODUCT_IDENTITY_DUPLICATED.toException();
        }
        LOG.errorf(ex, "Unhandled DB constraint violation [constraint=%s]", constraintName);
        throw ex;
    }

    private ConstraintViolationException extractConstraintViolation(PersistenceException ex) {
        if (ex instanceof ConstraintViolationException cve) {
            return cve;
        }
        Throwable cause = ex.getCause();
        return (cause instanceof ConstraintViolationException cve) ? cve : null;
    }

    // ---------- Ensamblado del response --------------------------------------

    /**
     * Arma el response combinando la entity con sus refs (categoría, unidad),
     * el stock/lowStock de la VIEW y el createdBy. Compartido por el alta (stock
     * inicial 0) y el listado (stock real de la VIEW) — una sola forma del DTO.
     */
    private WarehouseProductResponse toResponse(
        Product product, ProductCategory category, UnitOfMeasure unitOfMeasure,
        ProductStockView stock, UserResponse createdBy
    ) {
        return new WarehouseProductResponse(
            product.id,
            product.code,
            product.name,
            new CategoryRef(category.id, category.name),
            new UnitOfMeasureRef(unitOfMeasure.id, unitOfMeasure.code, unitOfMeasure.name),
            product.brand,
            product.partNumber,
            product.attributes,
            product.minStock,
            product.observations,
            product.isActive,
            stock.stock(),
            stock.lowStock(),
            createdBy,
            product.createdAt,
            product.updatedAt
        );
    }
}
