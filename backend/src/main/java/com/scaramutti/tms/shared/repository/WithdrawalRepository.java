package com.scaramutti.tms.shared.repository;

import com.scaramutti.tms.shared.entity.Withdrawal;
import com.scaramutti.tms.shared.util.DateUtils;
import com.scaramutti.tms.warehouse.withdrawal.service.cmd.ListWarehouseWithdrawalsQuery;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Repositorio de retiros. Vive en {@code shared/repository/} por convención del proyecto. El
 * alta usa el {@code persist} de Panache; el listado paginado hidrata la entity y el service
 * enriquece batch (producto, trabajador, unidad de flota) sin N+1.
 */
@ApplicationScoped
public class WithdrawalRepository implements PanacheRepositoryBase<Withdrawal, Integer> {

    @Inject
    EntityManager entityManager;

    /** Página de retiros que matchean los filtros, ordenada por {@code withdrawn_at} DESC (desempate por id). */
    public List<Withdrawal> searchPaged(ListWarehouseWithdrawalsQuery query) {
        Map<String, Object> params = new HashMap<>();
        String sql = "SELECT * FROM almacen.withdrawals " + where(query, params)
            + " ORDER BY withdrawn_at DESC, id DESC LIMIT :pageSize OFFSET :pageOffset";

        Query nativeQuery = entityManager.createNativeQuery(sql, Withdrawal.class);
        params.forEach(nativeQuery::setParameter);
        nativeQuery.setParameter("pageSize", query.size());
        nativeQuery.setParameter("pageOffset", (long) query.page() * query.size());

        @SuppressWarnings("unchecked")
        List<Withdrawal> result = nativeQuery.getResultList();
        return result;
    }

    /** Total de retiros que matchean los filtros. Reusa el MISMO WHERE que searchPaged. */
    public long countSearch(ListWarehouseWithdrawalsQuery query) {
        Map<String, Object> params = new HashMap<>();
        String sql = "SELECT COUNT(*) FROM almacen.withdrawals " + where(query, params);

        Query nativeQuery = entityManager.createNativeQuery(sql);
        params.forEach(nativeQuery::setParameter);
        return ((Number) nativeQuery.getSingleResult()).longValue();
    }

    private String where(ListWarehouseWithdrawalsQuery query, Map<String, Object> params) {
        List<String> conditions = new ArrayList<>();
        if (query.productId() != null) {
            conditions.add("product_id = :productId");
            params.put("productId", query.productId());
        }
        if (query.receivedByWorkerId() != null) {
            conditions.add("received_by = :receivedBy");
            params.put("receivedBy", query.receivedByWorkerId());
        }
        if (query.tractorId() != null) {
            conditions.add("tractor_id = :tractorId");
            params.put("tractorId", query.tractorId());
        }
        if (query.trailerId() != null) {
            conditions.add("trailer_id = :trailerId");
            params.put("trailerId", query.trailerId());
        }
        if (query.escortVehicleId() != null) {
            conditions.add("escort_vehicle_id = :escortVehicleId");
            params.put("escortVehicleId", query.escortVehicleId());
        }
        if (query.status() != null) {
            conditions.add("status = :status");
            params.put("status", query.status().name());
        }
        if (query.dateFrom() != null) {
            conditions.add("withdrawn_at >= :dateFrom");
            params.put("dateFrom", query.dateFrom().atStartOfDay(DateUtils.LIMA).toOffsetDateTime());
        }
        if (query.dateTo() != null) {
            // dateTo inclusivo del dia completo -> < inicio del dia siguiente (Lima).
            conditions.add("withdrawn_at < :dateToExclusive");
            params.put("dateToExclusive", query.dateTo().plusDays(1).atStartOfDay(DateUtils.LIMA).toOffsetDateTime());
        }
        return conditions.isEmpty() ? "" : "WHERE " + String.join(" AND ", conditions);
    }
}
