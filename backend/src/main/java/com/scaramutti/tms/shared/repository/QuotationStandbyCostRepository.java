package com.scaramutti.tms.shared.repository;

import com.scaramutti.tms.shared.entity.QuotationStandbyCost;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

/**
 * Repositorio de costos de stand-by por item. Helpers agregados conforme
 * los endpoints los necesitan (write en CREATE, read en GET).
 */
@ApplicationScoped
public class QuotationStandbyCostRepository implements PanacheRepositoryBase<QuotationStandbyCost, Long> {

    /**
     * Carga todos los standby costs de una cotizacion en UNA query. El
     * service los indexa por {@code quotationItemId} en un Map para que el
     * assembler resuelva cada item en O(1) sin re-pegarle a la BD.
     */
    public List<QuotationStandbyCost> findByQuotationId(Long quotationId) {
        return list("quotationId", quotationId);
    }

    /**
     * Borra todos los standby costs de una cotizacion. Usado por el UPDATE (PUT)
     * antes de borrar los items (la FK {@code quotation_item_id} es NOT NULL).
     * Devuelve la cantidad borrada.
     */
    public long deleteByQuotationId(Long quotationId) {
        return delete("quotationId = ?1", quotationId);
    }
}
