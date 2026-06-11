package com.scaramutti.tms.shared.repository;

import com.scaramutti.tms.quotations.service.cmd.ListQuotationsQuery;
import com.scaramutti.tms.shared.entity.Quotation;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.persistence.Tuple;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Repositorio de cotizaciones. Aunque vive en `shared/repository/`, es
 * quotation-specific. Expone helpers especificos del modulo Quotations.
 *
 * Metodos:
 *  - acquireYearLock / acquireAntiDuplicateLock: advisory locks de la tx de CREATE.
 *  - maxCodeNumberForYear: parsea el numero del code para el generador.
 *  - findRecentByCreatedByAndClient: deteccion de duplicados recientes.
 *  - searchPaged / countSearch: listado paginado con multifiltro dinamico (GET /quotations).
 *    Native query con proyeccion {@link QuotationSummaryRow} (NO hidrata la entity:
 *    la fila trae columnas de JOINs — client.name/ruc, currency.code). El total y
 *    el itemsCount NO se calculan aca: el service los computa desde los items
 *    (batch-load) con {@code QuotationCalculatorService.calculateFromEntities}.
 */
@ApplicationScoped
public class QuotationRepository implements PanacheRepositoryBase<Quotation, Long> {

    /** Zona horaria del negocio (Peru). Los filtros de fecha se interpretan aca. */
    private static final ZoneId LIMA = ZoneId.of("America/Lima");

    @Inject
    EntityManager entityManager;

    /**
     * Adquiere un advisory lock por anio dentro de la tx actual. Se libera
     * automaticamente al commit/rollback. Serializa la generacion del code.
     */
    public void acquireYearLock(int year) {
        entityManager.createNativeQuery("SELECT pg_advisory_xact_lock(:year)")
            .setParameter("year", (long) year)
            .getSingleResult();
    }

    /**
     * Adquiere un advisory lock por (createdBy, clientId) dentro de la tx actual.
     * Cierra la ventana TOCTOU del anti-duplicado.
     */
    public void acquireAntiDuplicateLock(Integer createdBy, Integer clientId) {
        entityManager.createNativeQuery("SELECT pg_advisory_xact_lock(:k1, :k2)")
            .setParameter("k1", createdBy)
            .setParameter("k2", clientId)
            .getSingleResult();
    }

    /**
     * Devuelve el MAX(numero) parseado del code para un anio dado.
     * El code tiene formato `YYYY-NNNNN`; SUBSTRING(code, 6) extrae el numero.
     * Si no hay cotizaciones del anio, devuelve 0.
     */
    public int maxCodeNumberForYear(int year) {
        Object result = entityManager.createNativeQuery(
            "SELECT COALESCE(MAX(CAST(SUBSTRING(code FROM 6) AS INTEGER)), 0) " +
            "FROM cotizaciones.quotations WHERE code LIKE :prefix"
        ).setParameter("prefix", year + "-%").getSingleResult();
        return ((Number) result).intValue();
    }

    /**
     * Detecta duplicados recientes para proteccion anti doble-click backend-side.
     */
    public List<Quotation> findRecentByCreatedByAndClient(Integer createdBy, Integer clientId, int secondsWindow) {
        OffsetDateTime cutoff = OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(secondsWindow);
        return list("createdBy = ?1 AND clientId = ?2 AND createdAt >= ?3", createdBy, clientId, cutoff);
    }

    // ============== Listado paginado (GET /quotations) ======================

    /**
     * Busca cotizaciones paginadas aplicando los filtros opcionales (AND).
     * Orden FIJO: {@code created_at DESC, id DESC} (id como tie-breaker estable
     * para paginacion determinista). Devuelve una proyeccion chata
     * {@link QuotationSummaryRow} (cabecera + client.name/ruc + currency.code),
     * SIN totales (los computa el service desde los items).
     *
     * Native query (no Panache JPQL) por los JOINs a clients/currencies y para
     * proyectar columnas que no son campos de la entity. Patron analogo a
     * {@code ClientRepository.searchPaged} — con una diferencia: aca el filtro q
     * escapa los wildcards de LIKE ({@link #escapeLikeWildcards}); ClientRepository
     * aun NO lo hace (misma vulnerabilidad de sobre-match, deuda pendiente).
     */
    public List<QuotationSummaryRow> searchPaged(ListQuotationsQuery listQuotationsQuery) {
        Map<String, Object> params = new HashMap<>();
        String where = buildWhere(listQuotationsQuery, params);

        String sql = "SELECT qt.id, qt.code, qt.quotation_type, qt.status, "
            + "qt.client_id, cli.name AS client_name, cli.ruc AS client_ruc, "
            + "cur.code AS currency_code, qt.validity_days, "
            + "qt.origin, qt.destination, qt.created_at, qt.created_by "
            + "FROM cotizaciones.quotations qt "
            + "JOIN public.clients cli ON cli.id = qt.client_id "
            + "JOIN public.currencies cur ON cur.id = qt.currency_id "
            + where
            + " ORDER BY qt.created_at DESC, qt.id DESC "
            + "LIMIT :pageSize OFFSET :pageOffset";

        Query query = entityManager.createNativeQuery(sql, Tuple.class);
        params.forEach(query::setParameter);
        query.setParameter("pageSize", listQuotationsQuery.size());
        query.setParameter("pageOffset", (long) listQuotationsQuery.page() * listQuotationsQuery.size());

        @SuppressWarnings("unchecked")
        List<Tuple> rows = query.getResultList();
        return rows.stream().map(QuotationRepository::toSummaryRow).toList();
    }

    /**
     * Cuenta las cotizaciones que matchean los filtros. Reusa el MISMO
     * {@link #buildWhere} que searchPaged (sin ORDER/LIMIT) — evita drift del
     * predicado entre search y count cuando se agregan filtros.
     */
    public long countSearch(ListQuotationsQuery listQuotationsQuery) {
        Map<String, Object> params = new HashMap<>();
        String where = buildWhere(listQuotationsQuery, params);

        String sql = "SELECT COUNT(*) "
            + "FROM cotizaciones.quotations qt "
            + "JOIN public.clients cli ON cli.id = qt.client_id "
            + "JOIN public.currencies cur ON cur.id = qt.currency_id "
            + where;

        Query query = entityManager.createNativeQuery(sql);
        params.forEach(query::setParameter);
        return ((Number) query.getSingleResult()).longValue();
    }

    /**
     * Construye la clausula WHERE con los filtros opcionales y rellena el mapa
     * de named params. Side-effect deliberado para que search y count compartan
     * exactamente el mismo predicado. Cada condicion se agrega solo si su filtro
     * es no-null.
     */
    private String buildWhere(ListQuotationsQuery q, Map<String, Object> params) {
        List<String> conditions = new ArrayList<>();

        if (q.q() != null) {
            // ILIKE (case-insensitive) substring sobre los 5 campos buscables.
            // Los GIN trgm (code/origin/destination/client name/ruc) aceleran el wildcard.
            // Se escapan los metacaracteres de LIKE (% _ \) del input para que un
            // q="100%" o "A_B" busque esos literales y no sobre-matchee. ESCAPE '\'
            // declara el backslash como char de escape.
            conditions.add("(qt.code ILIKE :qLike ESCAPE '\\' OR cli.name ILIKE :qLike ESCAPE '\\' "
                + "OR cli.ruc ILIKE :qLike ESCAPE '\\' OR qt.origin ILIKE :qLike ESCAPE '\\' "
                + "OR qt.destination ILIKE :qLike ESCAPE '\\')");
            params.put("qLike", "%" + escapeLikeWildcards(q.q()) + "%");
        }
        if (q.status() != null) {
            conditions.add("qt.status = :status");
            params.put("status", q.status().name());
        }
        if (q.quotationType() != null) {
            conditions.add("qt.quotation_type = :quotationType");
            params.put("quotationType", q.quotationType().name());
        }
        if (q.clientId() != null) {
            conditions.add("qt.client_id = :clientId");
            params.put("clientId", q.clientId());
        }
        if (q.createdById() != null) {
            conditions.add("qt.created_by = :createdById");
            params.put("createdById", q.createdById());
        }
        if (q.currencyId() != null) {
            conditions.add("qt.currency_id = :currencyId");
            params.put("currencyId", q.currencyId());
        }
        if (q.cargoTypeId() != null) {
            // "al menos un item (root o hijo) con ese cargo type".
            conditions.add("EXISTS (SELECT 1 FROM cotizaciones.quotation_items it "
                + "WHERE it.quotation_id = qt.id AND it.cargo_type_id = :cargoTypeId)");
            params.put("cargoTypeId", q.cargoTypeId());
        }
        if (q.serviceTypeId() != null) {
            conditions.add("EXISTS (SELECT 1 FROM cotizaciones.quotation_items it "
                + "WHERE it.quotation_id = qt.id AND it.quotation_service_type_id = :serviceTypeId)");
            params.put("serviceTypeId", q.serviceTypeId());
        }
        if (q.dateFrom() != null) {
            // Inicio del dia en zona Lima → instante UTC para comparar contra created_at (timestamptz).
            conditions.add("qt.created_at >= :dateFrom");
            params.put("dateFrom", q.dateFrom().atStartOfDay(LIMA).toOffsetDateTime());
        }
        if (q.dateTo() != null) {
            // dateTo inclusivo del dia completo → < inicio del dia siguiente (Lima).
            conditions.add("qt.created_at < :dateToExclusive");
            params.put("dateToExclusive", q.dateTo().plusDays(1).atStartOfDay(LIMA).toOffsetDateTime());
        }

        return conditions.isEmpty() ? "" : "WHERE " + String.join(" AND ", conditions);
    }

    /**
     * Escapa los metacaracteres de LIKE/ILIKE ({@code \ % _}) para que el input
     * de busqueda se trate como literal. El backslash primero (es el char de
     * escape, no debe duplicarse despues). Usado con {@code ESCAPE '\'} en el SQL.
     */
    private static String escapeLikeWildcards(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    /**
     * Mapea una fila native (Tuple) a {@link QuotationSummaryRow} por indice
     * posicional (robusto frente al case del alias que devuelve Postgres).
     */
    private static QuotationSummaryRow toSummaryRow(Tuple t) {
        return new QuotationSummaryRow(
            ((Number) t.get(0)).longValue(),     // id
            (String) t.get(1),                    // code
            (String) t.get(2),                    // quotation_type
            (String) t.get(3),                    // status
            ((Number) t.get(4)).intValue(),       // client_id
            (String) t.get(5),                    // client_name
            (String) t.get(6),                    // client_ruc
            (String) t.get(7),                    // currency_code
            ((Number) t.get(8)).intValue(),       // validity_days
            (String) t.get(9),                    // origin
            (String) t.get(10),                   // destination
            toOffsetDateTime(t.get(11)),          // created_at
            ((Number) t.get(12)).intValue()       // created_by
        );
    }

    /**
     * Conversion defensiva del created_at native a OffsetDateTime. Hibernate/PG
     * puede devolver OffsetDateTime, Instant o Timestamp segun version/driver.
     */
    private static OffsetDateTime toOffsetDateTime(Object value) {
        if (value instanceof OffsetDateTime odt) return odt;
        if (value instanceof java.time.Instant inst) return inst.atOffset(ZoneOffset.UTC);
        if (value instanceof java.sql.Timestamp ts) return ts.toInstant().atOffset(ZoneOffset.UTC);
        throw new IllegalStateException("Unexpected created_at type: "
            + (value == null ? "null" : value.getClass().getName()));
    }

    /**
     * Proyeccion chata de una fila del listado. NO es entity — trae columnas de
     * JOINs (client.name/ruc, currency.code). El service la enriquece con
     * totalAmount/itemsCount (desde items batch), createdBy (UserResponse) e
     * isExpired (runtime).
     */
    public record QuotationSummaryRow(
        Long id,
        String code,
        String quotationType,
        String status,
        Integer clientId,
        String clientName,
        String clientRuc,
        String currencyCode,
        Integer validityDays,
        String origin,
        String destination,
        OffsetDateTime createdAt,
        Integer createdBy
    ) {}
}
