package com.scaramutti.tms.shared.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.util.Objects;

/**
 * Tabla de apoyo (junction) que une una cotizacion con las condiciones generales que le
 * aplican (ADR-009). FK-only, PK compuesta {@code (quotation_id, condition_id)}: la fila
 * inmutable del catalogo {@link Condition} ES el snapshot, por eso aca no se guarda texto.
 * El borrado de la cotizacion cascada estas filas (FK ON DELETE CASCADE); el de la condicion
 * NO (las condiciones no se borran, solo se desactivan — preserva el snapshot).
 */
@Entity
@Table(name = "quotation_conditions", schema = "cotizaciones")
@IdClass(QuotationCondition.Pk.class)
public class QuotationCondition {

    @Id
    @Column(name = "quotation_id")
    public Long quotationId;

    @Id
    @Column(name = "condition_id")
    public Integer conditionId;

    public QuotationCondition() {}

    public QuotationCondition(Long quotationId, Integer conditionId) {
        this.quotationId = quotationId;
        this.conditionId = conditionId;
    }

    /** Clave compuesta de la junction. Requerida por JPA para una entity con {@code @IdClass}. */
    public static class Pk implements Serializable {
        public Long quotationId;
        public Integer conditionId;

        public Pk() {}

        public Pk(Long quotationId, Integer conditionId) {
            this.quotationId = quotationId;
            this.conditionId = conditionId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Pk pk)) return false;
            return Objects.equals(quotationId, pk.quotationId)
                && Objects.equals(conditionId, pk.conditionId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(quotationId, conditionId);
        }
    }
}
