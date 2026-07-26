package com.scaramutti.tms.shared.repository;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;

import java.time.OffsetDateTime;

/**
 * Repositorio del strip de KPIs de almacen (GET /warehouse/stats). Vive en
 * {@code shared/repository/} por convencion del proyecto (mismo criterio que
 * {@link WarehouseKardexRepository}), aunque es especifico del modulo Almacen:
 * lee tablas ({@code products}, {@code purchase_invoices}, {@code withdrawals})
 * y la VIEW {@code almacen.product_stock}, sin entity 1:1 detras.
 *
 * <p>Los 4 contadores salen en UNA sola query (subqueries escalares, sin filas):
 * es lo mas barato para un strip de dashboard. El rango de mes calendario lo
 * calcula y pasa el service (bordes en America/Lima).
 */
@ApplicationScoped
public class WarehouseStatsRepository {

    @Inject
    EntityManager entityManager;

    /**
     * Los 4 KPIs del strip. {@code monthStart}/{@code monthEndExclusive} son los
     * bordes del mes calendario en curso (America/Lima, semiabierto
     * {@code [inicio, inicioMesSiguiente)}) que el service ya calculo sobre las
     * columnas {@code timestamptz} {@code created_at}/{@code withdrawn_at}.
     */
    public WarehouseStatsRow getStats(OffsetDateTime monthStart, OffsetDateTime monthEndExclusive) {
        Tuple row = (Tuple) entityManager.createNativeQuery(
            "SELECT "
            + "(SELECT COUNT(*) FROM almacen.products WHERE is_active) AS active_products, "
            + "(SELECT COUNT(*) FROM almacen.product_stock ps "
            + "   JOIN almacen.products p ON p.id = ps.product_id "
            + "  WHERE p.is_active AND ps.low_stock) AS low_stock_count, "
            + "(SELECT COUNT(*) FROM almacen.purchase_invoices "
            + "  WHERE status = 'ACTIVE' AND created_at >= :monthStart AND created_at < :monthEndExclusive) AS entries_this_month, "
            + "(SELECT COUNT(*) FROM almacen.withdrawals "
            + "  WHERE status = 'ACTIVE' AND withdrawn_at >= :monthStart AND withdrawn_at < :monthEndExclusive) AS withdrawals_this_month",
            Tuple.class)
            .setParameter("monthStart", monthStart)
            .setParameter("monthEndExclusive", monthEndExclusive)
            .getSingleResult();

        return new WarehouseStatsRow(
            ((Number) row.get(0)).intValue(),
            ((Number) row.get(1)).intValue(),
            ((Number) row.get(2)).intValue(),
            ((Number) row.get(3)).intValue()
        );
    }

    /** Proyeccion de los 4 contadores del strip (mismos campos que el response). */
    public record WarehouseStatsRow(
        int activeProducts,
        int lowStockCount,
        int entriesThisMonth,
        int withdrawalsThisMonth
    ) {}
}
