package com.scaramutti.tms.shared.repository;

import com.scaramutti.tms.shared.entity.PurchaseInvoice;
import com.scaramutti.tms.shared.util.MultiWordSearch;
import com.scaramutti.tms.warehouse.purchaseinvoice.service.cmd.ListWarehousePurchaseInvoicesQuery;
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
 * Repositorio de entradas (facturas de compra). Vive en {@code shared/repository/}
 * por convención del proyecto (mismo criterio que ProductRepository/SupplierRepository).
 *
 * <p>Unicidad respaldada en BD: índice UNIQUE PARCIAL {@code uq_purchase_invoices_active}
 * sobre {@code (supplier_id, invoice_number) WHERE status='ACTIVE'} (V002, RN-WH5). El
 * {@code existsActiveBySupplierAndNumber} cubre el happy path; la race la traduce el
 * catch del service.
 */
@ApplicationScoped
public class PurchaseInvoiceRepository implements PanacheRepositoryBase<PurchaseInvoice, Integer> {

    @Inject
    EntityManager entityManager;

    /**
     * Pre-check happy path del UNIQUE parcial (WH-002): ¿ya hay una factura ACTIVA
     * con ese proveedor y número? Una anulada NO cuenta (el índice es parcial), así
     * que su número queda libre para la corregida (RN-WH5).
     */
    public boolean existsActiveBySupplierAndNumber(Integer supplierId, String invoiceNumber) {
        return count("supplierId = ?1 and invoiceNumber = ?2 and status = ?3",
            supplierId, invoiceNumber, "ACTIVE") > 0;
    }

    // ---------- Listado paginado (GET /warehouse/purchase-invoices) -------------

    /**
     * Página de facturas que matchean los filtros, ordenada por {@code created_at}
     * DESC (más reciente primero). Hidrata la ENTITY ({@code SELECT pi.*}); los datos
     * de presentación que no están en la tabla (nombre de proveedor, code de moneda,
     * registeredBy, itemsCount/total) los batch-loadea el service, sin N+1.
     *
     * <p>{@code q} (multi-palabra, RN-WH14 vía {@link MultiWordSearch}): cada palabra
     * debe matchear en ALGÚN campo (nº de factura, nº de guía o nombre del proveedor)
     * — AND de ORs, ILIKE case-insensitive. El nombre del proveedor está en otra
     * tabla, por eso el JOIN a {@code almacen.suppliers} (solo cuando hay {@code q}).
     */
    public List<PurchaseInvoice> searchPaged(ListWarehousePurchaseInvoicesQuery query) {
        Map<String, Object> params = new HashMap<>();
        String sql = "SELECT pi.* " + fromAndWhere(query, params)
            + " ORDER BY pi.created_at DESC LIMIT :pageSize OFFSET :pageOffset";

        Query nativeQuery = entityManager.createNativeQuery(sql, PurchaseInvoice.class);
        params.forEach(nativeQuery::setParameter);
        nativeQuery.setParameter("pageSize", query.size());
        nativeQuery.setParameter("pageOffset", (long) query.page() * query.size());

        @SuppressWarnings("unchecked")
        List<PurchaseInvoice> result = nativeQuery.getResultList();
        return result;
    }

    /** Total de facturas que matchean los filtros. Reusa el MISMO FROM+WHERE que searchPaged. */
    public long countSearch(ListWarehousePurchaseInvoicesQuery query) {
        Map<String, Object> params = new HashMap<>();
        String sql = "SELECT COUNT(*) " + fromAndWhere(query, params);

        Query nativeQuery = entityManager.createNativeQuery(sql);
        params.forEach(nativeQuery::setParameter);
        return ((Number) nativeQuery.getSingleResult()).longValue();
    }

    private String fromAndWhere(ListWarehousePurchaseInvoicesQuery query, Map<String, Object> params) {
        boolean needsSupplierJoin = query.q() != null;
        String from = "FROM almacen.purchase_invoices pi "
            + (needsSupplierJoin ? "JOIN almacen.suppliers s ON s.id = pi.supplier_id " : "");

        List<String> conditions = new ArrayList<>();
        if (query.q() != null) {
            conditions.addAll(MultiWordSearch.conditions(
                query.q(), List.of("pi.invoice_number", "pi.guide_number", "s.name"), "qTok", params));
        }
        if (query.supplierId() != null) {
            conditions.add("pi.supplier_id = :supplierId");
            params.put("supplierId", query.supplierId());
        }
        if (query.status() != null) {
            conditions.add("pi.status = :status");
            params.put("status", query.status().name());
        }
        if (query.dateFrom() != null) {
            conditions.add("pi.invoice_date >= :dateFrom");
            params.put("dateFrom", query.dateFrom());
        }
        if (query.dateTo() != null) {
            conditions.add("pi.invoice_date <= :dateTo");
            params.put("dateTo", query.dateTo());
        }
        String where = conditions.isEmpty() ? "" : "WHERE " + String.join(" AND ", conditions);
        return from + where;
    }
}
