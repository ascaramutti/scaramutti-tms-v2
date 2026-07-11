package com.scaramutti.tms.shared.repository;

import com.scaramutti.tms.warehouse.kardex.service.cmd.GetWarehouseKardexQuery;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.persistence.Tuple;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Repositorio del kardex (GET /warehouse/products/{id}/kardex). Vive en
 * {@code shared/repository/} por convencion del proyecto (mismo criterio que
 * ProductRepository/QuotationRepository) aunque es especifico del modulo
 * Almacen — lee la VIEW {@code almacen.stock_movements} (sin entity JPA propia,
 * no hay una tabla 1:1 detras).
 *
 * <p>El saldo corrido (RN-WH13, CRITICO) se calcula con un window function
 * {@code SUM(...) OVER (... ROWS UNBOUNDED PRECEDING)} sobre la HISTORIA
 * COMPLETA del producto (sin filtro de fecha, sin paginacion) en un CTE; el
 * filtro de fecha y el LIMIT/OFFSET se aplican DESPUES, por fuera del window
 * function — asi el balance es correcto sin importar que pagina o rango de
 * fechas pida el caller.
 *
 * <p>Orden fijo: {@code movedAt DESC}, luego prioridad de tipo
 * (APERTURA &lt; ENTRADA &lt; SALIDA) DESC, luego {@code movement_seq} DESC —
 * el mismo criterio (pero ASC) que usa el {@code SUM() OVER} para acumular el
 * balance, así el balance mostrado en cada fila es exactamente el saldo
 * "hasta esa fila" en el orden que ve el usuario.
 */
@ApplicationScoped
public class WarehouseKardexRepository {

    /** Zona horaria del negocio (Peru), mismo criterio que QuotationRepository. */
    private static final ZoneId LIMA = ZoneId.of("America/Lima");

    private static final String TYPE_PRIORITY =
        "CASE movement_type WHEN 'APERTURA' THEN 0 WHEN 'ENTRADA' THEN 1 WHEN 'SALIDA' THEN 2 END";

    @Inject
    EntityManager entityManager;

    /**
     * Pagina de movimientos con el balance corrido ya calculado (RN-WH13).
     * El WHERE de fecha y el LIMIT/OFFSET se aplican sobre el resultado del
     * CTE {@code history}, que en si mismo no tiene filtro ni paginacion.
     */
    public List<KardexMovementRow> findPaged(GetWarehouseKardexQuery query) {
        Map<String, Object> params = new LinkedHashMap<>();
        String sql = historyCte(query, params)
            + "SELECT movement_type, ABS(quantity) AS quantity, balance, moved_at, source_id, registered_by "
            + "FROM history "
            + dateWhere(query, params)
            + "ORDER BY moved_at DESC, " + TYPE_PRIORITY + " DESC, movement_seq DESC "
            + "LIMIT :pageSize OFFSET :pageOffset";

        Query nativeQuery = entityManager.createNativeQuery(sql, Tuple.class);
        params.forEach(nativeQuery::setParameter);
        nativeQuery.setParameter("pageSize", query.size());
        nativeQuery.setParameter("pageOffset", (long) query.page() * query.size());

        @SuppressWarnings("unchecked")
        List<Tuple> rows = nativeQuery.getResultList();
        return rows.stream().map(WarehouseKardexRepository::toMovementRow).toList();
    }

    /**
     * Total de movimientos que matchean el filtro de fecha. COUNT directo
     * sobre la VIEW (sin window function: el balance no hace falta para contar).
     */
    public long countMatching(GetWarehouseKardexQuery query) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("productId", query.productId());
        String sql = "SELECT COUNT(*) FROM almacen.stock_movements WHERE product_id = :productId "
            + dateConditions(query, params, true);

        Query nativeQuery = entityManager.createNativeQuery(sql);
        params.forEach(nativeQuery::setParameter);
        return ((Number) nativeQuery.getSingleResult()).longValue();
    }

    /**
     * CTE compartido por findPaged: la SUMA corrida sobre la historia COMPLETA
     * del producto, SIN filtro de fecha ni paginacion (RN-WH13). El orden del
     * window function es ASC (cronologico) — el balance se acumula "hacia
     * adelante" en el tiempo, aunque la pagina final se muestre DESC.
     */
    private String historyCte(GetWarehouseKardexQuery query, Map<String, Object> params) {
        params.put("productId", query.productId());
        return "WITH history AS ( "
            + "SELECT movement_type, quantity, moved_at, registered_by, source_id, movement_seq, "
            + "SUM(quantity) OVER (ORDER BY moved_at ASC, " + TYPE_PRIORITY + " ASC, movement_seq ASC "
            + "ROWS UNBOUNDED PRECEDING) AS balance "
            + "FROM almacen.stock_movements WHERE product_id = :productId"
            + ") ";
    }

    private String dateWhere(GetWarehouseKardexQuery query, Map<String, Object> params) {
        String conditions = dateConditions(query, params, false);
        return conditions.isBlank() ? "" : "WHERE " + conditions.trim() + " ";
    }

    /**
     * Condiciones de fecha compartidas por findPaged (WHERE tras el CTE) y
     * countMatching (WHERE directo sobre la VIEW): mismo predicado, para que
     * el conteo nunca desalinee del contenido paginado.
     *
     * @param leadingAnd true cuando el caller ya abrio el WHERE con
     *                   {@code product_id = :productId} y necesita el
     *                   {@code AND} inicial (countMatching); false cuando el
     *                   caller arma su propio WHERE desde cero (findPaged).
     */
    private String dateConditions(GetWarehouseKardexQuery query, Map<String, Object> params, boolean leadingAnd) {
        List<String> conditions = new ArrayList<>();
        if (query.dateFrom() != null) {
            conditions.add("moved_at >= :dateFrom");
            params.put("dateFrom", query.dateFrom().atStartOfDay(LIMA).toOffsetDateTime());
        }
        if (query.dateTo() != null) {
            // dateTo inclusivo del dia completo -> < inicio del dia siguiente (Lima).
            conditions.add("moved_at < :dateToExclusive");
            params.put("dateToExclusive", query.dateTo().plusDays(1).atStartOfDay(LIMA).toOffsetDateTime());
        }
        if (conditions.isEmpty()) {
            return "";
        }
        String joined = String.join(" AND ", conditions);
        return leadingAnd ? " AND " + joined : joined;
    }

    // ---------- reference (es-PE): ENTRADA/SALIDA batch lookups -----------------

    /**
     * Nº de factura + proveedor de un lote de facturas (source_id de las filas
     * ENTRADA), para componer el {@code reference} "Factura X · Proveedor".
     * 1 query, sin N+1.
     */
    public Map<Integer, EntradaReferenceView> findEntradaReferences(Collection<Integer> invoiceIds) {
        if (invoiceIds.isEmpty()) {
            return Map.of();
        }
        Query nativeQuery = entityManager.createNativeQuery(
            "SELECT pi.id, pi.invoice_number, s.name "
            + "FROM almacen.purchase_invoices pi "
            + "JOIN almacen.suppliers s ON s.id = pi.supplier_id "
            + "WHERE pi.id IN :ids",
            Tuple.class
        ).setParameter("ids", invoiceIds);

        @SuppressWarnings("unchecked")
        List<Tuple> rows = nativeQuery.getResultList();
        Map<Integer, EntradaReferenceView> byId = new LinkedHashMap<>();
        for (Tuple row : rows) {
            byId.put(
                ((Number) row.get(0)).intValue(),
                new EntradaReferenceView((String) row.get(1), (String) row.get(2))
            );
        }
        return byId;
    }

    /**
     * Trabajador que recibio + placa de la unidad (si hubiera) de un lote de
     * retiros (source_id de las filas SALIDA), para componer el {@code reference}
     * "Retiro · recibe X · placa". La unidad es OPCIONAL (RN-WH2): los 3 FK
     * pueden ser null, en cuyo caso {@code plate} viene null y el service omite
     * el segmento. {@code COALESCE} entre los 3 LEFT JOIN porque son subtipos
     * disyuntos (a lo sumo uno no-null, CHECK chk_withdrawals_one_unit). 1 query,
     * sin N+1.
     */
    public Map<Integer, SalidaReferenceView> findSalidaReferences(Collection<Integer> withdrawalIds) {
        if (withdrawalIds.isEmpty()) {
            return Map.of();
        }
        Query nativeQuery = entityManager.createNativeQuery(
            "SELECT w.id, wk.first_name, wk.last_name, "
            + "COALESCE(t.plate, tr.plate, ev.plate) AS plate "
            + "FROM almacen.withdrawals w "
            + "JOIN public.workers wk ON wk.id = w.received_by "
            + "LEFT JOIN public.tractors t ON t.id = w.tractor_id "
            + "LEFT JOIN public.trailers tr ON tr.id = w.trailer_id "
            + "LEFT JOIN public.escort_vehicles ev ON ev.id = w.escort_vehicle_id "
            + "WHERE w.id IN :ids",
            Tuple.class
        ).setParameter("ids", withdrawalIds);

        @SuppressWarnings("unchecked")
        List<Tuple> rows = nativeQuery.getResultList();
        Map<Integer, SalidaReferenceView> byId = new LinkedHashMap<>();
        for (Tuple row : rows) {
            String fullName = row.get(1) + " " + row.get(2);
            byId.put(
                ((Number) row.get(0)).intValue(),
                new SalidaReferenceView(fullName, (String) row.get(3))
            );
        }
        return byId;
    }

    // ---------- mapeo de filas ----------------------------------------------------

    private static KardexMovementRow toMovementRow(Tuple t) {
        return new KardexMovementRow(
            (String) t.get(0),                    // movement_type
            (BigDecimal) t.get(1),                // quantity (ya ABS)
            (BigDecimal) t.get(2),                // balance
            toOffsetDateTime(t.get(3)),           // moved_at
            t.get(4) == null ? null : ((Number) t.get(4)).intValue(), // source_id
            ((Number) t.get(5)).intValue()        // registered_by
        );
    }

    /**
     * Conversion defensiva del moved_at native a OffsetDateTime (mismo criterio
     * que QuotationRepository.toOffsetDateTime): Hibernate/PG puede devolver
     * OffsetDateTime, Instant o Timestamp segun version/driver.
     */
    private static OffsetDateTime toOffsetDateTime(Object value) {
        if (value instanceof OffsetDateTime odt) return odt;
        if (value instanceof java.time.Instant inst) return inst.atOffset(ZoneOffset.UTC);
        if (value instanceof java.sql.Timestamp ts) return ts.toInstant().atOffset(ZoneOffset.UTC);
        throw new IllegalStateException("Unexpected moved_at type: "
            + (value == null ? "null" : value.getClass().getName()));
    }

    /** Proyeccion de una fila del CTE history, ya con quantity ABS y balance corrido. */
    public record KardexMovementRow(
        String movementType,
        BigDecimal quantity,
        BigDecimal balance,
        OffsetDateTime movedAt,
        Integer sourceId,
        Integer registeredBy
    ) {}

    /** Nº de factura + nombre de proveedor, para el reference de una fila ENTRADA. */
    public record EntradaReferenceView(String invoiceNumber, String supplierName) {}

    /** Nombre completo de quien recibe + placa (null si el retiro no tuvo unidad), para SALIDA. */
    public record SalidaReferenceView(String workerFullName, String plate) {}
}
