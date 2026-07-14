package com.scaramutti.tms.warehouse.withdrawal.service;

import com.scaramutti.tms.auth.dto.UserResponse;
import com.scaramutti.tms.auth.security.CurrentUser;
import com.scaramutti.tms.auth.service.UserLookup;
import com.scaramutti.tms.shared.dto.PageResponse;
import com.scaramutti.tms.shared.dto.WorkerResponse;
import com.scaramutti.tms.shared.entity.EscortVehicle;
import com.scaramutti.tms.shared.entity.Product;
import com.scaramutti.tms.shared.entity.Tractor;
import com.scaramutti.tms.shared.entity.Trailer;
import com.scaramutti.tms.shared.entity.UnitOfMeasure;
import com.scaramutti.tms.shared.entity.Withdrawal;
import com.scaramutti.tms.shared.entity.Worker;
import com.scaramutti.tms.shared.repository.EscortVehicleRepository;
import com.scaramutti.tms.shared.repository.ProductRepository;
import com.scaramutti.tms.shared.repository.TractorRepository;
import com.scaramutti.tms.shared.repository.TrailerRepository;
import com.scaramutti.tms.shared.repository.UnitOfMeasureRepository;
import com.scaramutti.tms.shared.repository.WithdrawalRepository;
import com.scaramutti.tms.shared.repository.WorkerRepository;
import com.scaramutti.tms.warehouse.WarehouseError;
import com.scaramutti.tms.warehouse.model.FleetUnitKind;
import com.scaramutti.tms.warehouse.model.WarehouseRecordStatus;
import com.scaramutti.tms.warehouse.product.dto.WarehouseProductSummary;
import com.scaramutti.tms.warehouse.withdrawal.dto.FleetUnitRef;
import com.scaramutti.tms.warehouse.withdrawal.dto.WarehouseWithdrawalResponse;
import com.scaramutti.tms.warehouse.withdrawal.mapper.WarehouseWithdrawalServiceMapper;
import com.scaramutti.tms.warehouse.withdrawal.service.cmd.CreateWarehouseWithdrawalCommand;
import com.scaramutti.tms.warehouse.withdrawal.service.cmd.ListWarehouseWithdrawalsQuery;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Retiros de almacén (salidas de stock): alta transaccional. RN-WH2, la regla estrella:
 * {@code quantity ≤ stock disponible} se valida BAJO un lock de fila del producto
 * ({@code SELECT ... FOR UPDATE}) para que dos retiros concurrentes del mismo producto no
 * pasen ambos por una race: el chequeo de stock es serializado por el lock, no best-effort.
 *
 * <p>Orden de validación: WH-005 (a lo sumo una unidad, estructural y barato) → WH-004
 * (producto, trabajador y unidad, activos) → lock + WH-001 (stock insuficiente, con el
 * disponible en el detail) → persist. El retiro ACTIVE resta stock vía la VIEW.
 */
@ApplicationScoped
public class WarehouseWithdrawalService {

    @Inject WithdrawalRepository withdrawalRepository;
    @Inject ProductRepository productRepository;
    @Inject WorkerRepository workerRepository;
    @Inject TractorRepository tractorRepository;
    @Inject TrailerRepository trailerRepository;
    @Inject EscortVehicleRepository escortVehicleRepository;
    @Inject UnitOfMeasureRepository unitOfMeasureRepository;
    @Inject UserLookup userLookup;
    @Inject CurrentUser currentUser;
    @Inject WarehouseWithdrawalServiceMapper warehouseWithdrawalServiceMapper;

    @Transactional
    public WarehouseWithdrawalResponse createWithdrawal(CreateWarehouseWithdrawalCommand command) {
        Integer userId = currentUser.requireId();

        rejectMultipleFleetUnits(command);
        Product product = requireActiveProduct(command.productId());
        UnitOfMeasure unit = unitOfMeasureRepository.findById(product.unitOfMeasureId);
        Worker worker = requireActiveWorker(command.receivedByWorkerId());
        FleetUnitRef fleetUnit = requireActiveFleetUnit(
            command.tractorId(), command.trailerId(), command.escortVehicleId());

        // Lock del producto + validación de stock en la MISMA transacción (WH-001).
        productRepository.lockProductRow(command.productId());
        BigDecimal available = currentStock(command.productId());
        if (command.quantity().compareTo(available) > 0) {
            throw WarehouseError.WITHDRAWAL_INSUFFICIENT_STOCK.toException(
                "Stock insuficiente: solo hay " + available.toPlainString() + " " + unit.code
                    + " disponibles de " + product.name);
        }

        Withdrawal withdrawal = warehouseWithdrawalServiceMapper.toWithdrawalEntity(command, userId);
        withdrawalRepository.persist(withdrawal);

        // Retiro recién creado: ACTIVE, sin anular (cancelledBy null).
        return toResponse(withdrawal, product, unit, worker, fleetUnit, userLookup.require(userId), null);
    }

    /**
     * Listado paginado (GET /warehouse/withdrawals). Read-only, sin {@code @Transactional}.
     * Queries fijas (page + count + productos/unidades + trabajadores + usuarios + las 3
     * flotas), ninguna N+1 por page size. {@code lastEdit} viaja null (aún no hay edición).
     */
    public PageResponse<WarehouseWithdrawalResponse> listWithdrawals(ListWarehouseWithdrawalsQuery query) {
        List<Withdrawal> withdrawals = withdrawalRepository.searchPaged(query);
        long totalElements = withdrawalRepository.countSearch(query);

        if (withdrawals.isEmpty()) {
            return PageResponse.of(List.of(), query.page(), query.size(), totalElements);
        }

        Map<Integer, Product> productsById = productRepository
            .list("id in ?1", withdrawals.stream().map(w -> w.productId).collect(Collectors.toSet()))
            .stream().collect(Collectors.toMap(product -> product.id, product -> product));
        Map<Integer, UnitOfMeasure> unitsById = unitOfMeasureRepository
            .list("id in ?1", productsById.values().stream().map(p -> p.unitOfMeasureId).collect(Collectors.toSet()))
            .stream().collect(Collectors.toMap(unit -> unit.id, unit -> unit));
        Map<Integer, Worker> workersById = workerRepository
            .list("id in ?1", withdrawals.stream().map(w -> w.receivedBy).collect(Collectors.toSet()))
            .stream().collect(Collectors.toMap(worker -> worker.id, worker -> worker));

        Set<Integer> userIds = new HashSet<>();
        withdrawals.forEach(w -> {
            userIds.add(w.registeredBy);
            if (w.cancelledBy != null) {
                userIds.add(w.cancelledBy);
            }
        });
        Map<Integer, UserResponse> usersById = userLookup.requireAllById(userIds);

        Set<Integer> tractorIds = idsOf(withdrawals, w -> w.tractorId);
        Set<Integer> trailerIds = idsOf(withdrawals, w -> w.trailerId);
        Set<Integer> escortIds = idsOf(withdrawals, w -> w.escortVehicleId);
        // Solo se consulta el subtipo que aparece en la página (evita 3 round-trips fijos).
        Map<Integer, Tractor> tractorsById = tractorIds.isEmpty() ? Map.of() : tractorRepository
            .list("id in ?1", tractorIds).stream().collect(Collectors.toMap(t -> t.id, t -> t));
        Map<Integer, Trailer> trailersById = trailerIds.isEmpty() ? Map.of() : trailerRepository
            .list("id in ?1", trailerIds).stream().collect(Collectors.toMap(t -> t.id, t -> t));
        Map<Integer, EscortVehicle> escortsById = escortIds.isEmpty() ? Map.of() : escortVehicleRepository
            .list("id in ?1", escortIds).stream().collect(Collectors.toMap(e -> e.id, e -> e));

        List<WarehouseWithdrawalResponse> content = withdrawals.stream()
            .map(withdrawal -> {
                Product product = productsById.get(withdrawal.productId);
                return toResponse(
                    withdrawal, product, unitsById.get(product.unitOfMeasureId),
                    workersById.get(withdrawal.receivedBy),
                    resolveFleetUnit(withdrawal, tractorsById, trailersById, escortsById),
                    usersById.get(withdrawal.registeredBy),
                    withdrawal.cancelledBy != null ? usersById.get(withdrawal.cancelledBy) : null);
            })
            .toList();

        return PageResponse.of(content, query.page(), query.size(), totalElements);
    }

    /** Ids no-null de un campo de flota de la página (vacío si ningún retiro usa ese subtipo). */
    private Set<Integer> idsOf(List<Withdrawal> withdrawals, java.util.function.Function<Withdrawal, Integer> field) {
        return withdrawals.stream().map(field).filter(java.util.Objects::nonNull).collect(Collectors.toSet());
    }

    private FleetUnitRef resolveFleetUnit(Withdrawal withdrawal, Map<Integer, Tractor> tractors,
                                          Map<Integer, Trailer> trailers, Map<Integer, EscortVehicle> escorts) {
        if (withdrawal.tractorId != null) {
            Tractor t = tractors.get(withdrawal.tractorId);
            return t != null ? new FleetUnitRef(FleetUnitKind.TRACTOR, t.id, t.plate) : null;
        }
        if (withdrawal.trailerId != null) {
            Trailer t = trailers.get(withdrawal.trailerId);
            return t != null ? new FleetUnitRef(FleetUnitKind.TRAILER, t.id, t.plate) : null;
        }
        if (withdrawal.escortVehicleId != null) {
            EscortVehicle e = escorts.get(withdrawal.escortVehicleId);
            return e != null ? new FleetUnitRef(FleetUnitKind.ESCORT, e.id, e.plate) : null;
        }
        return null;
    }

    // ---------- WH-005 (a lo sumo una unidad de flota) ------------------------

    private void rejectMultipleFleetUnits(CreateWarehouseWithdrawalCommand command) {
        int units = (command.tractorId() != null ? 1 : 0)
            + (command.trailerId() != null ? 1 : 0)
            + (command.escortVehicleId() != null ? 1 : 0);
        if (units > 1) {
            throw WarehouseError.WITHDRAWAL_MULTIPLE_FLEET_UNITS.toException();
        }
    }

    // ---------- WH-004 (FK activas) -------------------------------------------

    private Product requireActiveProduct(Integer productId) {
        Product product = productRepository.findById(productId);
        if (product == null || !Boolean.TRUE.equals(product.isActive)) {
            throw WarehouseError.PRODUCT_NOT_FOUND_OR_INACTIVE.toException();
        }
        return product;
    }

    private Worker requireActiveWorker(Integer workerId) {
        Worker worker = workerRepository.findById(workerId);
        if (worker == null || !Boolean.TRUE.equals(worker.isActive)) {
            throw WarehouseError.WORKER_NOT_FOUND_OR_INACTIVE.toException();
        }
        return worker;
    }

    /** Resuelve la unidad destino por el subtipo presente (a lo sumo uno, ya validado). null = sin unidad. */
    private FleetUnitRef requireActiveFleetUnit(Integer tractorId, Integer trailerId, Integer escortVehicleId) {
        if (tractorId != null) {
            Tractor tractor = tractorRepository.findById(tractorId);
            if (tractor == null || !Boolean.TRUE.equals(tractor.isActive)) {
                throw WarehouseError.FLEET_UNIT_NOT_FOUND_OR_INACTIVE.toException();
            }
            return new FleetUnitRef(FleetUnitKind.TRACTOR, tractor.id, tractor.plate);
        }
        if (trailerId != null) {
            Trailer trailer = trailerRepository.findById(trailerId);
            if (trailer == null || !Boolean.TRUE.equals(trailer.isActive)) {
                throw WarehouseError.FLEET_UNIT_NOT_FOUND_OR_INACTIVE.toException();
            }
            return new FleetUnitRef(FleetUnitKind.TRAILER, trailer.id, trailer.plate);
        }
        if (escortVehicleId != null) {
            EscortVehicle escort = escortVehicleRepository.findById(escortVehicleId);
            if (escort == null || !Boolean.TRUE.equals(escort.isActive)) {
                throw WarehouseError.FLEET_UNIT_NOT_FOUND_OR_INACTIVE.toException();
            }
            return new FleetUnitRef(FleetUnitKind.ESCORT, escort.id, escort.plate);
        }
        return null;
    }

    /** Stock actual del producto (VIEW product_stock); null defensivo → 0 (producto sin movimientos). */
    private BigDecimal currentStock(Integer productId) {
        ProductRepository.ProductStockView view = productRepository.findStockByProductId(productId);
        return view != null ? view.stock() : BigDecimal.ZERO;
    }

    // ---------- Ensamblado del response ----------------------------------------

    private WarehouseWithdrawalResponse toResponse(
        Withdrawal withdrawal, Product product, UnitOfMeasure unit, Worker worker,
        FleetUnitRef fleetUnit, UserResponse registeredBy, UserResponse cancelledBy
    ) {
        return new WarehouseWithdrawalResponse(
            withdrawal.id,
            new WarehouseProductSummary(product.id, product.code, product.name, unit.code),
            withdrawal.quantity,
            withdrawal.withdrawnAt,
            new WorkerResponse(worker.id, worker.fullName(), worker.position, worker.isActive),
            fleetUnit,
            withdrawal.observations,
            WarehouseRecordStatus.valueOf(withdrawal.status),
            withdrawal.cancelReason,
            cancelledBy,
            withdrawal.cancelledAt,
            // lastEdit null: aún no hay edición de retiros. La versión (ETag) es withdrawnAt
            // (la tabla no tiene updated_at; el retiro recién creado no se editó).
            null,
            registeredBy,
            withdrawal.withdrawnAt
        );
    }
}
