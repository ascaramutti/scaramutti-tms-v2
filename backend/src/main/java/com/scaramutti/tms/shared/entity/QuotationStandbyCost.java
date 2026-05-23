package com.scaramutti.tms.shared.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * Costo de stand-by por item. Tabla `cotizaciones.quotation_standby_costs`.
 * Relacion 1:1 con quotation_items (UNIQUE constraint sobre quotation_item_id).
 */
@Entity
@Table(name = "quotation_standby_costs", schema = "cotizaciones")
public class QuotationStandbyCost {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "quotation_id", nullable = false)
    public Long quotationId;

    @Column(name = "quotation_item_id", nullable = false)
    public Long quotationItemId;

    @Column(name = "price_per_day", nullable = false, precision = 12, scale = 2)
    public BigDecimal pricePerDay;

    @Column(name = "includes_igv", nullable = false)
    public Boolean includesIgv;

    @Column(name = "created_at", nullable = false, updatable = false)
    public OffsetDateTime createdAt;

    @PrePersist
    public void onCreate() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now(ZoneOffset.UTC);
        }
        if (includesIgv == null) {
            includesIgv = false;
        }
    }
}
