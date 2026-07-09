package com.scaramutti.tms.warehouse.product.service;

import com.scaramutti.tms.auth.dto.UserResponse;
import com.scaramutti.tms.auth.mapper.AuthServiceMapper;
import com.scaramutti.tms.auth.security.CurrentUser;
import com.scaramutti.tms.shared.entity.Product;
import com.scaramutti.tms.shared.entity.ProductCategory;
import com.scaramutti.tms.shared.entity.UnitOfMeasure;
import com.scaramutti.tms.shared.entity.User;
import com.scaramutti.tms.shared.exception.CommonError;
import com.scaramutti.tms.shared.repository.ProductCategoryRepository;
import com.scaramutti.tms.shared.repository.ProductRepository;
import com.scaramutti.tms.shared.repository.UnitOfMeasureRepository;
import com.scaramutti.tms.shared.repository.UserRepository;
import com.scaramutti.tms.warehouse.WarehouseError;
import com.scaramutti.tms.warehouse.product.dto.WarehouseProductResponse;
import com.scaramutti.tms.warehouse.product.dto.WarehouseProductResponse.CategoryRef;
import com.scaramutti.tms.warehouse.product.dto.WarehouseProductResponse.UnitOfMeasureRef;
import com.scaramutti.tms.warehouse.product.mapper.WarehouseProductServiceMapper;
import com.scaramutti.tms.warehouse.product.service.cmd.CreateWarehouseProductCommand;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.PersistenceException;
import jakarta.transaction.Transactional;
import org.hibernate.exception.ConstraintViolationException;
import org.jboss.logging.Logger;

import java.math.BigDecimal;

/**
 * Alta de producto (RN-WH6/WH10/WH11). Un producto nuevo nace con stock 0 (su
 * primera factura es su primer movimiento), así que stock/lowStock del response
 * se derivan en código sin tocar la VIEW {@code product_stock}.
 *
 * Duplicados: pre-check happy path ({@code existsBy...}) + traducción de la
 * constraint en la race ({@code persistOrTranslateDuplicate}), mismo patrón que
 * {@code WarehouseSupplierService}. El guardián real es el índice único de BD.
 */
@ApplicationScoped
public class WarehouseProductService {

    private static final Logger LOG = Logger.getLogger(WarehouseProductService.class);

    private static final BigDecimal INITIAL_STOCK = BigDecimal.ZERO;

    @Inject ProductRepository productRepository;
    @Inject ProductCategoryRepository productCategoryRepository;
    @Inject UnitOfMeasureRepository unitOfMeasureRepository;
    @Inject UserRepository userRepository;
    @Inject CurrentUser currentUser;
    @Inject WarehouseProductCodeGeneratorService warehouseProductCodeGeneratorService;
    @Inject WarehouseProductServiceMapper warehouseProductServiceMapper;
    @Inject AuthServiceMapper authServiceMapper;

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

        return buildResponse(product, category, unitOfMeasure, loadCreatedByUser(userId));
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
    }

    private ConstraintViolationException extractConstraintViolation(PersistenceException ex) {
        if (ex instanceof ConstraintViolationException cve) {
            return cve;
        }
        Throwable cause = ex.getCause();
        return (cause instanceof ConstraintViolationException cve) ? cve : null;
    }

    // ---------- Ensamblado del response --------------------------------------

    private UserResponse loadCreatedByUser(Integer userId) {
        User user = userRepository.findById(userId);
        if (user == null) {
            LOG.errorf("Orphan FK in product CREATE path: user not found, userId=%s", userId);
            throw CommonError.INTERNAL_ERROR.toException(
                "El producto referencia un usuario inexistente (createdBy id=" + userId + "). Reporte a soporte."
            );
        }
        return authServiceMapper.toUserResponse(user);
    }

    private WarehouseProductResponse buildResponse(
        Product product, ProductCategory category, UnitOfMeasure unitOfMeasure, UserResponse createdBy
    ) {
        boolean lowStock = INITIAL_STOCK.compareTo(product.minStock) < 0;
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
            INITIAL_STOCK,
            lowStock,
            createdBy,
            product.createdAt,
            product.updatedAt
        );
    }
}
