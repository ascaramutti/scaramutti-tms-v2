package com.scaramutti.tms.warehouse.openingbalance.service;

import com.scaramutti.tms.auth.dto.UserResponse;
import com.scaramutti.tms.auth.security.CurrentUser;
import com.scaramutti.tms.auth.service.UserLookup;
import com.scaramutti.tms.shared.dto.PageResponse;
import com.scaramutti.tms.shared.entity.OpeningBalance;
import com.scaramutti.tms.shared.entity.Product;
import com.scaramutti.tms.shared.entity.UnitOfMeasure;
import com.scaramutti.tms.shared.repository.OpeningBalanceRepository;
import com.scaramutti.tms.shared.repository.ProductRepository;
import com.scaramutti.tms.shared.repository.UnitOfMeasureRepository;
import com.scaramutti.tms.warehouse.WarehouseError;
import com.scaramutti.tms.warehouse.openingbalance.dto.WarehouseOpeningBalanceResponse;
import com.scaramutti.tms.warehouse.openingbalance.mapper.WarehouseOpeningBalanceServiceMapper;
import com.scaramutti.tms.warehouse.openingbalance.service.cmd.CreateWarehouseOpeningBalanceCommand;
import com.scaramutti.tms.warehouse.openingbalance.service.cmd.ListWarehouseOpeningBalancesQuery;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.PersistenceException;
import jakarta.transaction.Transactional;
import org.hibernate.exception.ConstraintViolationException;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Aperturas de inventario de almacén: alta (WH-004/WH-009/WH-011) y listado
 * paginado. UNA apertura por producto e INMUTABLE (sin PUT/DELETE), primer
 * movimiento del kardex (APERTURA, ver VIEW {@code almacen.stock_movements}).
 *
 * <p>Alta, orden de validación: 400 WH-004 (producto activo) → 409 WH-009
 * (duplicado, happy path) → 409 WH-011 (movimientos previos) → persist
 * (traduciendo la race del UNIQUE a WH-009, mismo patrón que
 * {@code WarehouseProductService}).
 *
 * <p>Listado: {@code searchPaged} hidrata las entities de la página y el
 * service las enriquece batch (producto, unidad, registeredBy) sin N+1 por
 * page size, mismo patrón que {@code WarehouseProductService.listProducts}.
 */
@ApplicationScoped
public class WarehouseOpeningBalanceService {

    private static final Logger LOG = Logger.getLogger(WarehouseOpeningBalanceService.class);

    @Inject OpeningBalanceRepository openingBalanceRepository;
    @Inject ProductRepository productRepository;
    @Inject UnitOfMeasureRepository unitOfMeasureRepository;
    @Inject UserLookup userLookup;
    @Inject CurrentUser currentUser;
    @Inject WarehouseOpeningBalanceServiceMapper warehouseOpeningBalanceServiceMapper;

    @Transactional
    public WarehouseOpeningBalanceResponse createOpeningBalance(CreateWarehouseOpeningBalanceCommand command) {
        Integer userId = currentUser.requireId();

        Product product = requireActiveProduct(command.productId());
        rejectExistingOpeningBalance(command.productId());
        rejectExistingMovements(command.productId());

        OpeningBalance openingBalance = warehouseOpeningBalanceServiceMapper.toOpeningBalanceEntity(command, userId);
        persistOrTranslateDuplicate(openingBalance);

        UnitOfMeasure unitOfMeasure = unitOfMeasureRepository.findById(product.unitOfMeasureId);
        return warehouseOpeningBalanceServiceMapper.toWarehouseOpeningBalanceResponse(
            openingBalance, product, unitOfMeasure, userLookup.require(userId));
    }

    /**
     * Listado paginado (GET /warehouse/opening-balances). Read-only, sin
     * {@code @Transactional} (misma convención que listProducts). 5 queries
     * fijas (page + count + products + units + users), ninguna N+1 por page size.
     */
    public PageResponse<WarehouseOpeningBalanceResponse> listOpeningBalances(ListWarehouseOpeningBalancesQuery query) {
        List<OpeningBalance> openingBalances = openingBalanceRepository.searchPaged(query);
        long totalElements = openingBalanceRepository.countSearch(query);

        if (openingBalances.isEmpty()) {
            return PageResponse.of(List.of(), query.page(), query.size(), totalElements);
        }

        Map<Integer, Product> productsById = productRepository
            .list("id in ?1", openingBalances.stream().map(ob -> ob.productId).collect(Collectors.toSet()))
            .stream().collect(Collectors.toMap(product -> product.id, product -> product));
        Map<Integer, UnitOfMeasure> unitsById = unitOfMeasureRepository
            .list("id in ?1", productsById.values().stream().map(p -> p.unitOfMeasureId).collect(Collectors.toSet()))
            .stream().collect(Collectors.toMap(unit -> unit.id, unit -> unit));
        Map<Integer, UserResponse> usersById = userLookup.requireAllById(
            openingBalances.stream().map(ob -> ob.registeredBy).collect(Collectors.toSet()));

        List<WarehouseOpeningBalanceResponse> content = openingBalances.stream()
            .map(openingBalance -> {
                Product product = productsById.get(openingBalance.productId);
                return warehouseOpeningBalanceServiceMapper.toWarehouseOpeningBalanceResponse(
                    openingBalance, product, unitsById.get(product.unitOfMeasureId),
                    usersById.get(openingBalance.registeredBy)
                );
            })
            .toList();

        return PageResponse.of(content, query.page(), query.size(), totalElements);
    }

    // ---------- Validación de FK (WH-004) -------------------------------------

    private Product requireActiveProduct(Integer productId) {
        Product product = productRepository.findById(productId);
        if (product == null || !Boolean.TRUE.equals(product.isActive)) {
            throw WarehouseError.PRODUCT_NOT_FOUND_OR_INACTIVE.toException();
        }
        return product;
    }

    // ---------- Anti-duplicado (WH-009) ---------------------------------------

    private void rejectExistingOpeningBalance(Integer productId) {
        if (openingBalanceRepository.existsByProductId(productId)) {
            throw WarehouseError.OPENING_BALANCE_DUPLICATED.toException();
        }
    }

    // ---------- Producto ya tiene movimientos (WH-011) ------------------------

    private void rejectExistingMovements(Integer productId) {
        if (openingBalanceRepository.existsActiveMovementsForProduct(productId)) {
            throw WarehouseError.OPENING_BALANCE_HAS_MOVEMENTS.toException();
        }
    }

    /**
     * Cubre la race donde dos requests pasan {@code rejectExistingOpeningBalance}
     * simultáneamente para el mismo producto: el índice UNIQUE {@code product_id}
     * (V002) garantiza que Postgres rechaza el segundo INSERT.
     */
    private void persistOrTranslateDuplicate(OpeningBalance openingBalance) {
        try {
            openingBalanceRepository.persist(openingBalance);
            openingBalanceRepository.flush();
        } catch (PersistenceException ex) {
            ConstraintViolationException cve = extractConstraintViolation(ex);
            if (cve == null) {
                throw ex;
            }
            String constraintName = cve.getConstraintName();
            if (constraintName != null && constraintName.contains("product_id")) {
                LOG.warnf("Race condition: UNIQUE product_id violation [productId=%s]", openingBalance.productId);
                throw WarehouseError.OPENING_BALANCE_DUPLICATED.toException();
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
}
