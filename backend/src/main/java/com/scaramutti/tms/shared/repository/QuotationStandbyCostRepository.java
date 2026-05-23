package com.scaramutti.tms.shared.repository;

import com.scaramutti.tms.shared.entity.QuotationStandbyCost;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Repositorio de costos de stand-by por item. Sin metodos custom todavia —
 * el persist via `persist(entity)` cubre el caso del create. Se agregaran
 * helpers (deleteByQuotation, findByItem, etc.) cuando lleguen los endpoints
 * de update/delete.
 */
@ApplicationScoped
public class QuotationStandbyCostRepository implements PanacheRepositoryBase<QuotationStandbyCost, Long> {
}
