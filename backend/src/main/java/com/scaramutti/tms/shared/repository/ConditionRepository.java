package com.scaramutti.tms.shared.repository;

import com.scaramutti.tms.shared.entity.Condition;
import com.scaramutti.tms.shared.entity.Condition_;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

/**
 * Repositorio del catálogo de condiciones generales. Ordena por {@code display_order} ASC
 * (RN-04, orden de impresión en el PDF). Espeja {@link PaymentTermRepository}.
 */
@ApplicationScoped
public class ConditionRepository implements PanacheRepositoryBase<Condition, Integer> {

    public List<Condition> listAllOrderedByDisplayOrder() {
        return listAll(Sort.by(Condition_.DISPLAY_ORDER).ascending());
    }

    public List<Condition> listByIsActiveOrderedByDisplayOrder(boolean isActive) {
        return list(
            Condition_.IS_ACTIVE + " = ?1",
            Sort.by(Condition_.DISPLAY_ORDER).ascending(),
            isActive
        );
    }

    /**
     * Condiciones linkeadas a una cotizacion (READ-path, RN-05): TODAS las linkeadas —
     * activas E inactivas — ordenadas por {@code display_order} ASC, {@code id} ASC como
     * desempate estable ({@code display_order} no es UNIQUE). NO filtra {@code isActive}:
     * el detalle/PDF muestra el snapshot historico (una condicion desactivada tras emitirse
     * la cotizacion sigue apareciendo). Asimetrico, a proposito, con la escritura (ADR-010).
     */
    public List<Condition> findLinkedToQuotation(Long quotationId) {
        return list(
            "id in (select qc.conditionId from QuotationCondition qc where qc.quotationId = ?1)",
            Sort.by(Condition_.DISPLAY_ORDER, Condition_.ID).ascending(),
            quotationId
        );
    }
}
