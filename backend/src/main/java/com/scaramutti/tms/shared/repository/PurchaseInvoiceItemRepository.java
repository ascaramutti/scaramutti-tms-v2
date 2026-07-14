package com.scaramutti.tms.shared.repository;

import com.scaramutti.tms.shared.entity.PurchaseInvoiceItem;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.persistence.Tuple;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Repositorio de ítems de entrada. Vive en {@code shared/repository/} por convención
 * del proyecto. La factura y sus ítems se persisten en la misma transacción (FK
 * plana, sin cascade JPA): el service persiste la cabecera, obtiene su id y persiste
 * cada ítem con este repo.
 */
@ApplicationScoped
public class PurchaseInvoiceItemRepository implements PanacheRepositoryBase<PurchaseInvoiceItem, Integer> {

    @Inject
    EntityManager entityManager;

    /**
     * {@code itemsCount} y {@code total} (= Σ quantity*unit_price) por factura, para
     * una página del listado. 1 query agregada sobre el set de ids (sin N+1 por page
     * size); indexado por {@code invoice_id}. Las facturas sin ítems no aparecen en el
     * mapa (el service las resuelve con 0/BigDecimal.ZERO).
     */
    public Map<Integer, InvoiceAggregate> aggregateByInvoiceIds(Collection<Integer> invoiceIds) {
        if (invoiceIds.isEmpty()) {
            return Map.of();
        }
        Query nativeQuery = entityManager.createNativeQuery(
            "SELECT invoice_id, COUNT(*) AS items_count, SUM(quantity * unit_price) AS total "
                + "FROM almacen.purchase_invoice_items WHERE invoice_id IN :ids GROUP BY invoice_id",
            Tuple.class
        ).setParameter("ids", invoiceIds);

        @SuppressWarnings("unchecked")
        List<Tuple> rows = nativeQuery.getResultList();
        Map<Integer, InvoiceAggregate> byInvoiceId = new LinkedHashMap<>();
        for (Tuple row : rows) {
            byInvoiceId.put(
                ((Number) row.get("invoice_id")).intValue(),
                new InvoiceAggregate(((Number) row.get("items_count")).intValue(), (BigDecimal) row.get("total"))
            );
        }
        return byInvoiceId;
    }

    /** Proyección del agregado por factura: cantidad de ítems + total derivado. */
    public record InvoiceAggregate(int itemsCount, BigDecimal total) {}
}
