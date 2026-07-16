package com.scaramutti.tms.shared.repository;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.persistence.Tuple;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Repositorio del reporte de almacen (GET /warehouse/reports). Vive en
 * {@code shared/repository/} por convencion del proyecto (mismo criterio que
 * {@link WarehouseKardexRepository}), aunque es especifico del modulo Almacen:
 * agrega sobre {@code withdrawals}/{@code purchase_invoices} y sus catalogos,
 * sin entity 1:1 detras. Ningun agregado se persiste.
 *
 * <p>RN-WH7 (bi-moneda SIN conversion): {@code amount_pen}/{@code amount_usd}
 * acumulan por separado con {@code FILTER (WHERE currency = ...)}. Las salidas
 * (BY_UNIT/BY_PERIOD/BY_PRODUCT) se valorizan con el ULTIMO precio de compra
 * ACTIVO del producto y su moneda ({@link #LAST_PRICE_CTE}); un producto sin
 * compra activa aporta 0 a ambas monedas pero SIGUE contando (LEFT JOIN, el
 * FILTER por moneda nula no suma nada). BY_SUPPLIER valoriza con los propios
 * items de cada factura, en la moneda de la factura.
 *
 * <p>Los bordes del rango (retiros: {@code fromInclusive}/{@code toExclusive}
 * semiabierto en America/Lima) los calcula el service, igual que el kardex.
 */
@ApplicationScoped
public class WarehouseReportRepository {

    /**
     * Ultimo precio de compra por producto (con su moneda): el item de la factura
     * ACTIVA mas reciente ({@code invoice_date} DESC, desempate por registro).
     * {@code DISTINCT ON} deja una fila por producto. CTE compartido por los 3
     * cortes de consumo.
     */
    private static final String LAST_PRICE_CTE =
        "WITH last_price AS ( "
        + "SELECT DISTINCT ON (pii.product_id) pii.product_id, pii.unit_price, c.code AS currency_code "
        + "FROM almacen.purchase_invoice_items pii "
        + "JOIN almacen.purchase_invoices pi ON pi.id = pii.invoice_id AND pi.status = 'ACTIVE' "
        + "JOIN public.currencies c ON c.id = pi.currency_id "
        + "ORDER BY pii.product_id, pi.invoice_date DESC, pi.created_at DESC, pii.id DESC "
        + ") ";

    /** Acumuladores bi-moneda de una salida valorizada al ultimo precio de compra. */
    private static final String WITHDRAWAL_AMOUNTS =
        "COALESCE(SUM(w.quantity * lp.unit_price) FILTER (WHERE lp.currency_code = 'PEN'), 0) AS amount_pen, "
        + "COALESCE(SUM(w.quantity * lp.unit_price) FILTER (WHERE lp.currency_code = 'USD'), 0) AS amount_usd ";

    /** WHERE del rango de retiros ACTIVE (semiabierto [fromInclusive, toExclusive)). */
    private static final String WITHDRAWAL_RANGE =
        "WHERE w.status = 'ACTIVE' AND w.withdrawn_at >= :fromInclusive AND w.withdrawn_at < :toExclusive ";

    @Inject
    EntityManager entityManager;

    /**
     * BY_UNIT: consumo por unidad de flota. La unidad es disyunta (a lo sumo una,
     * CHECK): el CASE compone la etiqueta es-PE; sin unidad va a "Sin unidad
     * asignada". {@code count} = numero de retiros. Orden por monto total DESC.
     */
    public List<ReportRowView> findByUnit(OffsetDateTime fromInclusive, OffsetDateTime toExclusive) {
        String sql = LAST_PRICE_CTE
            + "SELECT * FROM ( "
            + "SELECT CASE "
            + "  WHEN w.tractor_id IS NOT NULL THEN 'Tracto ' || t.plate "
            + "  WHEN w.trailer_id IS NOT NULL THEN 'Carreta ' || tr.plate "
            + "  WHEN w.escort_vehicle_id IS NOT NULL THEN 'Escolta ' || ev.plate "
            + "  ELSE 'Sin unidad asignada' END AS label, "
            + "NULL::text AS detail, COUNT(*) AS cnt, " + WITHDRAWAL_AMOUNTS
            + "FROM almacen.withdrawals w "
            + "LEFT JOIN last_price lp ON lp.product_id = w.product_id "
            + "LEFT JOIN public.tractors t ON t.id = w.tractor_id "
            + "LEFT JOIN public.trailers tr ON tr.id = w.trailer_id "
            + "LEFT JOIN public.escort_vehicles ev ON ev.id = w.escort_vehicle_id "
            + WITHDRAWAL_RANGE
            + "GROUP BY label "
            + ") agg "
            + "ORDER BY (amount_pen + amount_usd) DESC, label ASC";
        return runWithdrawalQuery(sql, fromInclusive, toExclusive);
    }

    /**
     * BY_PERIOD: consumo por semana ISO (lunes como clave, en zona Lima). El
     * {@code date_trunc('week', ...)} sobre la hora local Lima da el lunes de la
     * semana. {@code count} = numero de retiros. Orden cronologico ASC.
     */
    public List<ReportRowView> findByPeriod(OffsetDateTime fromInclusive, OffsetDateTime toExclusive) {
        String sql = LAST_PRICE_CTE
            + "SELECT 'Semana del ' || to_char(week_start, 'DD/MM') AS label, "
            + "to_char(week_start, 'YYYY-MM-DD') AS detail, COUNT(*) AS cnt, "
            + "COALESCE(SUM(amount_pen), 0) AS amount_pen, COALESCE(SUM(amount_usd), 0) AS amount_usd "
            + "FROM ( "
            + "  SELECT date_trunc('week', w.withdrawn_at AT TIME ZONE 'America/Lima')::date AS week_start, "
            + "  CASE WHEN lp.currency_code = 'PEN' THEN w.quantity * lp.unit_price ELSE 0 END AS amount_pen, "
            + "  CASE WHEN lp.currency_code = 'USD' THEN w.quantity * lp.unit_price ELSE 0 END AS amount_usd "
            + "  FROM almacen.withdrawals w "
            + "  LEFT JOIN last_price lp ON lp.product_id = w.product_id "
            + WITHDRAWAL_RANGE
            + ") weekly "
            + "GROUP BY week_start "
            + "ORDER BY week_start ASC";
        return runWithdrawalQuery(sql, fromInclusive, toExclusive);
    }

    /**
     * BY_PRODUCT: consumo por producto. {@code count} = SUMA de cantidades
     * retiradas (unidades), NO numero de movimientos (RN del contrato).
     * {@code detail} = unidad de medida. Orden por monto total DESC.
     */
    public List<ReportRowView> findByProduct(OffsetDateTime fromInclusive, OffsetDateTime toExclusive) {
        String sql = LAST_PRICE_CTE
            + "SELECT * FROM ( "
            + "SELECT p.name AS label, uom.name AS detail, SUM(w.quantity) AS cnt, " + WITHDRAWAL_AMOUNTS
            + "FROM almacen.withdrawals w "
            + "JOIN almacen.products p ON p.id = w.product_id "
            + "JOIN almacen.units_of_measure uom ON uom.id = p.unit_of_measure_id "
            + "LEFT JOIN last_price lp ON lp.product_id = w.product_id "
            + WITHDRAWAL_RANGE
            + "GROUP BY p.id, p.name, uom.name "
            + ") agg "
            + "ORDER BY (amount_pen + amount_usd) DESC, label ASC";
        return runWithdrawalQuery(sql, fromInclusive, toExclusive);
    }

    /**
     * BY_SUPPLIER: compras por proveedor. Base distinta: facturas ACTIVE por
     * {@code invoice_date} (DATE, sin zona, ambos extremos inclusivos).
     * {@code count} = numero de facturas (DISTINCT); el monto suma los items en
     * la moneda de cada factura. Orden por monto total DESC.
     */
    public List<ReportRowView> findBySupplier(LocalDate dateFrom, LocalDate dateTo) {
        String sql =
            "SELECT * FROM ( "
            + "SELECT s.name AS label, NULL::text AS detail, COUNT(DISTINCT pi.id) AS cnt, "
            + "COALESCE(SUM(pii.quantity * pii.unit_price) FILTER (WHERE c.code = 'PEN'), 0) AS amount_pen, "
            + "COALESCE(SUM(pii.quantity * pii.unit_price) FILTER (WHERE c.code = 'USD'), 0) AS amount_usd "
            + "FROM almacen.purchase_invoices pi "
            + "JOIN almacen.suppliers s ON s.id = pi.supplier_id "
            + "JOIN almacen.purchase_invoice_items pii ON pii.invoice_id = pi.id "
            + "JOIN public.currencies c ON c.id = pi.currency_id "
            + "WHERE pi.status = 'ACTIVE' AND pi.invoice_date >= :dateFrom AND pi.invoice_date <= :dateTo "
            + "GROUP BY s.id, s.name "
            + ") agg "
            + "ORDER BY (amount_pen + amount_usd) DESC, label ASC";

        Query query = entityManager.createNativeQuery(sql, Tuple.class)
            .setParameter("dateFrom", dateFrom)
            .setParameter("dateTo", dateTo);
        return toRowViews(query);
    }

    private List<ReportRowView> runWithdrawalQuery(String sql, OffsetDateTime fromInclusive, OffsetDateTime toExclusive) {
        Query query = entityManager.createNativeQuery(sql, Tuple.class)
            .setParameter("fromInclusive", fromInclusive)
            .setParameter("toExclusive", toExclusive);
        return toRowViews(query);
    }

    private List<ReportRowView> toRowViews(Query query) {
        @SuppressWarnings("unchecked")
        List<Tuple> rows = query.getResultList();
        return rows.stream()
            .map(t -> new ReportRowView(
                (String) t.get(0),
                (String) t.get(1),
                toBigDecimal(t.get(2)),
                toBigDecimal(t.get(3)),
                toBigDecimal(t.get(4))))
            .toList();
    }

    /**
     * Normaliza el tipo numerico crudo: {@code COUNT(*)} llega como {@code Long},
     * {@code SUM(numeric)} como {@code BigDecimal}. Se unifica a {@code BigDecimal}.
     */
    private static BigDecimal toBigDecimal(Object value) {
        if (value instanceof BigDecimal bd) return bd;
        if (value instanceof Number n) return BigDecimal.valueOf(n.longValue());
        throw new IllegalStateException("Unexpected numeric type: "
            + (value == null ? "null" : value.getClass().getName()));
    }

    /** Proyeccion uniforme de una fila agregada, para los 4 cortes. */
    public record ReportRowView(
        String label,
        String detail,
        BigDecimal count,
        BigDecimal amountPEN,
        BigDecimal amountUSD
    ) {}
}
