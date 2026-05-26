package com.scaramutti.tms.shared.repository;

import com.scaramutti.tms.shared.entity.QuotationItem;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Set;

/**
 * Repositorio de items de cotizacion. Mantenido aparte del repo principal
 * por SRP — cada repository encapsula acceso a una sola entity.
 *
 * `serviceTypeIdsForQuotations` se usa en la deteccion de duplicados
 * recientes (anti doble-click): se compara el set de serviceTypeIds del
 * request entrante con los de cotizaciones del mismo created_by + cliente
 * en los ultimos N segundos.
 */
@ApplicationScoped
public class QuotationItemRepository implements PanacheRepositoryBase<QuotationItem, Long> {

    /**
     * Items de una cotizacion ordenados por {@code itemNumber} ASC. Garantiza
     * orden estable para que el assembler arme la jerarquia visual (padres
     * antes que hijos del Integral). El campo {@code itemNumber} es snapshot
     * del request original (asignado por el backend si el caller lo omite).
     */
    public List<QuotationItem> findByQuotationId(Long quotationId) {
        return list("quotationId = ?1 ORDER BY itemNumber ASC", quotationId);
    }

    /**
     * Devuelve los ids de servicio (distinct, no null) de los items de una
     * lista de cotizaciones. Si la lista de quotationIds es vacia, devuelve
     * un set vacio.
     */
    public Set<Integer> serviceTypeIdsForQuotations(List<Long> quotationIds) {
        if (quotationIds == null || quotationIds.isEmpty()) {
            return Set.of();
        }
        List<Integer> ids = getEntityManager().createQuery(
            "SELECT DISTINCT qi.quotationServiceTypeId FROM QuotationItem qi " +
            "WHERE qi.quotationId IN :ids AND qi.quotationServiceTypeId IS NOT NULL",
            Integer.class
        ).setParameter("ids", quotationIds).getResultList();
        return Set.copyOf(ids);
    }
}
