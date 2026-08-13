package com.scaramutti.tms.warehouse.withdrawal.service;

import com.scaramutti.tms.auth.dto.UserResponse;
import com.scaramutti.tms.auth.security.CurrentUser;
import com.scaramutti.tms.auth.service.UserLookup;
import com.scaramutti.tms.shared.dto.PageResponse;
import com.scaramutti.tms.shared.entity.AuditLog;
import com.scaramutti.tms.shared.entity.EscortVehicle;
import com.scaramutti.tms.shared.entity.Product;
import com.scaramutti.tms.shared.entity.Tractor;
import com.scaramutti.tms.shared.entity.Trailer;
import com.scaramutti.tms.shared.entity.UnitOfMeasure;
import com.scaramutti.tms.shared.entity.Withdrawal;
import com.scaramutti.tms.shared.entity.Worker;
import com.scaramutti.tms.shared.repository.AuditLogRepository;
import com.scaramutti.tms.shared.repository.EscortVehicleRepository;
import com.scaramutti.tms.shared.repository.ProductRepository;
import com.scaramutti.tms.shared.repository.TractorRepository;
import com.scaramutti.tms.shared.repository.TrailerRepository;
import com.scaramutti.tms.shared.repository.UnitOfMeasureRepository;
import com.scaramutti.tms.shared.repository.WithdrawalRepository;
import com.scaramutti.tms.shared.repository.WorkerRepository;
import com.scaramutti.tms.shared.util.DateUtils;
import com.scaramutti.tms.shared.util.Etag;
import com.scaramutti.tms.sharedcatalogs.fleetunit.dto.FleetUnitRef;
import com.scaramutti.tms.warehouse.WarehouseError;
import com.scaramutti.tms.warehouse.model.AuditChangeType;
import com.scaramutti.tms.warehouse.model.AuditEntityType;
import com.scaramutti.tms.warehouse.model.FleetUnitKind;
import com.scaramutti.tms.warehouse.model.WarehouseRecordStatus;
import com.scaramutti.tms.warehouse.purchaseinvoice.dto.WarehouseEditTrace;
import com.scaramutti.tms.warehouse.withdrawal.dto.WarehouseWithdrawalResponse;
import com.scaramutti.tms.warehouse.withdrawal.mapper.WarehouseWithdrawalServiceMapper;
import com.scaramutti.tms.warehouse.withdrawal.service.cmd.CreateWarehouseWithdrawalCommand;
import com.scaramutti.tms.warehouse.withdrawal.service.cmd.ListWarehouseWithdrawalsQuery;
import com.scaramutti.tms.warehouse.withdrawal.service.cmd.UpdateWarehouseWithdrawalCommand;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
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
    @Inject AuditLogRepository auditLogRepository;
    @Inject UserLookup userLookup;
    @Inject CurrentUser currentUser;
    @Inject WarehouseWithdrawalServiceMapper warehouseWithdrawalServiceMapper;

    @Transactional
    public WarehouseWithdrawalResponse createWithdrawal(CreateWarehouseWithdrawalCommand command) {
        Integer userId = currentUser.requireId();

        rejectMultipleFleetUnits(command.tractorId(), command.trailerId(), command.escortVehicleId());
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

        // Retiro recién creado: ACTIVE, sin anular (cancelledBy null) ni editar (lastEdit null).
        return warehouseWithdrawalServiceMapper.toWarehouseWithdrawalResponse(
            withdrawal, product, unit, worker, fleetUnit, userLookup.require(userId), null, null);
    }

    /**
     * Listado paginado (GET /warehouse/withdrawals). Read-only, sin {@code @Transactional}.
     * Queries fijas (page + count + productos/unidades + trabajadores + usuarios + las 3
     * flotas), ninguna N+1 por page size. {@code lastEdit} viaja null en el listado (el rastro
     * de edición se resuelve en el detalle, sin una query de auditoría por fila).
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
                return warehouseWithdrawalServiceMapper.toWarehouseWithdrawalResponse(
                    withdrawal, product, unitsById.get(product.unitOfMeasureId),
                    workersById.get(withdrawal.receivedBy),
                    resolveFleetUnit(withdrawal, tractorsById, trailersById, escortsById),
                    usersById.get(withdrawal.registeredBy),
                    withdrawal.cancelledBy != null ? usersById.get(withdrawal.cancelledBy) : null,
                    // lastEdit no viaja en el listado (evita una query de auditoría por fila); el
                    // rastro de edición se ve en el detalle (GET /{id}).
                    null);
            })
            .toList();

        return PageResponse.of(content, query.page(), query.size(), totalElements);
    }

    /** Ids no-null de un campo de flota de la página (vacío si ningún retiro usa ese subtipo). */
    private Set<Integer> idsOf(List<Withdrawal> withdrawals, Function<Withdrawal, Integer> field) {
        return withdrawals.stream().map(field).filter(Objects::nonNull).collect(Collectors.toSet());
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

    private void rejectMultipleFleetUnits(Integer tractorId, Integer trailerId, Integer escortVehicleId) {
        int units = (tractorId != null ? 1 : 0)
            + (trailerId != null ? 1 : 0)
            + (escortVehicleId != null ? 1 : 0);
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

    // ========== Detalle (GET /{id}) ===========================================

    /** Detalle de un retiro (GET /{id}). Read-only. 404 WH-003 si no existe. */
    public WarehouseWithdrawalResponse getWithdrawal(Integer id) {
        return assembleWarehouseWithdrawalResponse(loadWithdrawalOrThrow(id));
    }

    private Withdrawal loadWithdrawalOrThrow(Integer id) {
        return withdrawalRepository.findByIdOptional(id)
            .orElseThrow(WarehouseError.WITHDRAWAL_NOT_FOUND::toException);
    }

    /**
     * Arma el detalle completo de un retiro (lo comparten GET/PUT/cancel). El producto, la
     * unidad y el trabajador se leen sin chequear activo: el detalle refleja el retiro tal
     * como quedó registrado, aunque alguna FK se haya inactivado después. {@code lastEdit}
     * sale del último FIELD_EDIT en {@code almacen.audit_logs}; los campos de anulación se
     * pueblan si el retiro está anulado.
     */
    private WarehouseWithdrawalResponse assembleWarehouseWithdrawalResponse(Withdrawal withdrawal) {
        Product product = productRepository.findById(withdrawal.productId);
        UnitOfMeasure unit = unitOfMeasureRepository.findById(product.unitOfMeasureId);
        Worker worker = workerRepository.findById(withdrawal.receivedBy);
        FleetUnitRef fleetUnit = resolveFleetUnit(withdrawal);
        UserResponse registeredBy = userLookup.require(withdrawal.registeredBy);
        UserResponse cancelledBy = withdrawal.cancelledBy != null ? userLookup.require(withdrawal.cancelledBy) : null;
        WarehouseEditTrace lastEdit = loadLastEdit(withdrawal.id);
        return warehouseWithdrawalServiceMapper.toWarehouseWithdrawalResponse(
            withdrawal, product, unit, worker, fleetUnit, registeredBy, cancelledBy, lastEdit);
    }

    /** Resuelve la unidad de flota de UN retiro (subtipo presente), sin chequear activo. null si no tiene. */
    private FleetUnitRef resolveFleetUnit(Withdrawal withdrawal) {
        if (withdrawal.tractorId != null) {
            Tractor t = tractorRepository.findById(withdrawal.tractorId);
            return t != null ? new FleetUnitRef(FleetUnitKind.TRACTOR, t.id, t.plate) : null;
        }
        if (withdrawal.trailerId != null) {
            Trailer t = trailerRepository.findById(withdrawal.trailerId);
            return t != null ? new FleetUnitRef(FleetUnitKind.TRAILER, t.id, t.plate) : null;
        }
        if (withdrawal.escortVehicleId != null) {
            EscortVehicle e = escortVehicleRepository.findById(withdrawal.escortVehicleId);
            return e != null ? new FleetUnitRef(FleetUnitKind.ESCORT, e.id, e.plate) : null;
        }
        return null;
    }

    /** Último FIELD_EDIT del retiro para el {@code lastEdit} del detalle. null si nunca se editó. */
    private WarehouseEditTrace loadLastEdit(Integer withdrawalId) {
        return auditLogRepository.findLastFieldEdit(AuditEntityType.WITHDRAWAL, withdrawalId)
            .map(log -> new WarehouseEditTrace(userLookup.require(log.changedBy), log.loggedAt, log.reason))
            .orElse(null);
    }

    // ========== Edición (PUT /{id}) ===========================================

    /**
     * Edición de un retiro (PUT /{id}). El producto es inmutable (no viaja en el body).
     * Orden de validación: 404 WH-003 (no existe) -> 409 WH-008 (anulado) -> 412 If-Match ->
     * 400 WH-005 (a lo sumo una unidad) -> 400 WH-004 (trabajador y unidad de flota activos) ->
     * 409 WH-001 (solo si sube la cantidad, con el lock del producto) -> mutación + bump de
     * updatedAt + una fila FIELD_EDIT por campo cambiado.
     */
    @Transactional
    public WarehouseWithdrawalResponse updateWithdrawal(UpdateWarehouseWithdrawalCommand command) {
        Integer userId = currentUser.requireId();
        Withdrawal withdrawal = loadWithdrawalOrThrow(command.withdrawalId());
        rejectIfCancelled(withdrawal);
        Etag.verify(command.ifMatch(), withdrawal.updatedAt);

        rejectMultipleFleetUnits(command.tractorId(), command.trailerId(), command.escortVehicleId());
        Worker newWorker = requireActiveWorker(command.receivedByWorkerId());
        FleetUnitRef newFleetUnit = requireActiveFleetUnit(
            command.tractorId(), command.trailerId(), command.escortVehicleId());
        guardEditKeepsStockNonNegative(withdrawal, command.quantity());

        // Snapshot de los valores viejos para el diff de auditoría (ANTES de mutar).
        String oldQuantity = quantityLabel(withdrawal.quantity);
        Worker oldWorker = workerRepository.findById(withdrawal.receivedBy);
        String oldFleetUnit = fleetUnitLabel(resolveFleetUnit(withdrawal));
        String oldObservations = withdrawal.observations;

        withdrawal.quantity = command.quantity();
        withdrawal.receivedBy = command.receivedByWorkerId();
        withdrawal.tractorId = command.tractorId();
        withdrawal.trailerId = command.trailerId();
        withdrawal.escortVehicleId = command.escortVehicleId();
        withdrawal.observations = command.observations();
        withdrawal.updatedAt = DateUtils.nowUtcMicros();
        withdrawalRepository.flush();

        String reason = command.reason();
        logFieldEdit(withdrawal.id, "quantity", "Cantidad", oldQuantity, quantityLabel(command.quantity()), reason, userId);
        logFieldEdit(withdrawal.id, "receivedBy", "Quién recibe", workerLabel(oldWorker), workerLabel(newWorker), reason, userId);
        logFieldEdit(withdrawal.id, "fleetUnit", "Unidad de flota", oldFleetUnit, fleetUnitLabel(newFleetUnit), reason, userId);
        logFieldEdit(withdrawal.id, "observations", "Observaciones", oldObservations, command.observations(), reason, userId);

        return assembleWarehouseWithdrawalResponse(withdrawal);
    }

    private void rejectIfCancelled(Withdrawal withdrawal) {
        if (WarehouseRecordStatus.CANCELLED.name().equals(withdrawal.status)) {
            throw WarehouseError.WITHDRAWAL_ALREADY_CANCELLED.toException();
        }
    }

    // ========== Anulación (POST /{id}/cancel) =================================

    /**
     * Anulación de un retiro (POST /{id}/cancel). SIEMPRE segura: el stock vuelve solo porque
     * las VIEWs excluyen los anulados, asi que no lleva guarda de stock (a diferencia de la
     * anulación de entradas). Orden: 404 WH-003 (no existe) -> 409 WH-008 (ya anulado) -> 412
     * If-Match -> status CANCELLED + motivo/quién/cuándo + bump de updatedAt + fila CANCELLED en
     * audit_logs. Nada se borra (RN-WH3).
     */
    @Transactional
    public WarehouseWithdrawalResponse cancelWithdrawal(Integer id, String ifMatch, String reason) {
        Integer userId = currentUser.requireId();
        Withdrawal withdrawal = loadWithdrawalOrThrow(id);
        rejectIfCancelled(withdrawal);
        Etag.verify(ifMatch, withdrawal.updatedAt);

        OffsetDateTime now = DateUtils.nowUtcMicros();
        withdrawal.status = WarehouseRecordStatus.CANCELLED.name();
        withdrawal.cancelReason = reason;
        withdrawal.cancelledBy = userId;
        withdrawal.cancelledAt = now;
        withdrawal.updatedAt = now;
        withdrawalRepository.flush();

        writeCancelLog(withdrawal.id, reason, userId);
        return assembleWarehouseWithdrawalResponse(withdrawal);
    }

    private void writeCancelLog(Integer withdrawalId, String reason, Integer userId) {
        AuditLog log = new AuditLog();
        log.entityType = AuditEntityType.WITHDRAWAL.name();
        log.entityId = withdrawalId;
        log.changeType = AuditChangeType.CANCELLED.name();
        log.reason = reason;
        log.changedBy = userId;
        auditLogRepository.persist(log);
    }

    /**
     * WH-001 en la edición: subir la cantidad valida contra el disponible SIN contar este
     * retiro; bajarla o dejarla igual siempre es segura (devuelve stock) y no toca el lock.
     * Al subir, se lockea la fila del producto en la transacción y se compara contra
     * {@code stock_actual + cantidad_vieja} (el disponible si este retiro no existiera), el
     * mismo lock que serializa el alta (RN-WH2).
     */
    private void guardEditKeepsStockNonNegative(Withdrawal withdrawal, BigDecimal newQuantity) {
        if (newQuantity.compareTo(withdrawal.quantity) <= 0) {
            return;
        }
        productRepository.lockProductRow(withdrawal.productId);
        BigDecimal availableWithoutThis = currentStock(withdrawal.productId).add(withdrawal.quantity);
        if (newQuantity.compareTo(availableWithoutThis) > 0) {
            Product product = productRepository.findById(withdrawal.productId);
            UnitOfMeasure unit = unitOfMeasureRepository.findById(product.unitOfMeasureId);
            throw WarehouseError.WITHDRAWAL_INSUFFICIENT_STOCK.toException(
                "Stock insuficiente: solo hay " + availableWithoutThis.toPlainString() + " " + unit.code
                    + " disponibles de " + product.name + " (sin contar este retiro)");
        }
    }

    /** Una fila FIELD_EDIT por campo efectivamente cambiado (Objects.equals decide el no-op). */
    private void logFieldEdit(Integer withdrawalId, String fieldName, String fieldLabel,
                              String oldValue, String newValue, String reason, Integer userId) {
        if (Objects.equals(oldValue, newValue)) {
            return;
        }
        AuditLog log = new AuditLog();
        log.entityType = AuditEntityType.WITHDRAWAL.name();
        log.entityId = withdrawalId;
        log.changeType = AuditChangeType.FIELD_EDIT.name();
        log.fieldName = fieldName;
        log.fieldLabel = fieldLabel;
        log.oldValue = oldValue;
        log.newValue = newValue;
        log.reason = reason;
        log.changedBy = userId;
        auditLogRepository.persist(log);
    }

    /** Etiqueta de la cantidad para el diff de auditoría: sin ceros de cola ni notación científica. */
    private String quantityLabel(BigDecimal quantity) {
        return quantity.stripTrailingZeros().toPlainString();
    }

    /**
     * Etiqueta del trabajador para el diff de auditoría: nombre + id ("Juan Perez (#8)"). Lleva el
     * id para que el cambio se detecte aunque dos trabajadores compartan nombre (el nombre solo no
     * es único).
     */
    private String workerLabel(Worker worker) {
        return worker.fullName() + " (#" + worker.id + ")";
    }

    /** Etiqueta legible de la unidad de flota para el diff de auditoría ("TRACTOR ABC-123"); null si no tiene. */
    private String fleetUnitLabel(FleetUnitRef ref) {
        return ref == null ? null : ref.kind() + " " + ref.plate();
    }
}
