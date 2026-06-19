package com.scaramutti.tms.shared.repository;

import com.scaramutti.tms.shared.entity.QuotationCondition;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Repositorio de la junction cotizacion↔condiciones. La PK es compuesta
 * ({@link QuotationCondition.Pk}). {@code persist} (heredado) inserta una fila por link.
 */
@ApplicationScoped
public class QuotationConditionRepository
        implements PanacheRepositoryBase<QuotationCondition, QuotationCondition.Pk> {

    /** Borra todos los links de una cotizacion. Usado por el REPLACE del PUT (US-004). */
    public long deleteByQuotationId(Long quotationId) {
        return delete("quotationId = ?1", quotationId);
    }
}
