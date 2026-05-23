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
 * Item de cotizacion. Tabla `cotizaciones.quotation_items`.
 *
 *  - Puede ser root (parent_item_id NULL) o hijo del Servicio Integral
 *    (parent_item_id apuntando al item padre).
 *  - Snapshot de medidas (weight_kg, length/width/height_meters) — se copia
 *    del cargo_type al crear pero el usuario puede sobreescribirlo manualmente
 *    en el wizard.
 *  - Para hijos del Integral: unit_price = 0 (no entran al total), pueden
 *    tener internal_reference_price (no se expone al cliente).
 *  - insured_amount: solo aplica si el service_type es Seguro de Carga (SEG).
 */
@Entity
@Table(name = "quotation_items", schema = "cotizaciones")
public class QuotationItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "quotation_id", nullable = false)
    public Long quotationId;

    @Column(name = "parent_item_id")
    public Long parentItemId;

    @Column(name = "item_number", nullable = false)
    public Integer itemNumber;

    @Column(name = "quotation_service_type_id")
    public Integer quotationServiceTypeId;

    @Column(name = "cargo_type_id")
    public Integer cargoTypeId;

    @Column(columnDefinition = "text")
    public String observations;

    @Column(name = "weight_kg", precision = 10, scale = 2)
    public BigDecimal weightKg;

    @Column(name = "length_meters", precision = 8, scale = 2)
    public BigDecimal lengthMeters;

    @Column(name = "width_meters", precision = 8, scale = 2)
    public BigDecimal widthMeters;

    @Column(name = "height_meters", precision = 8, scale = 2)
    public BigDecimal heightMeters;

    @Column(nullable = false)
    public Integer quantity;

    @Column(name = "unit_price", nullable = false, precision = 12, scale = 2)
    public BigDecimal unitPrice;

    @Column(name = "igv_percentage", nullable = false, precision = 5, scale = 2)
    public BigDecimal igvPercentage;

    @Column(name = "insured_amount", precision = 14, scale = 2)
    public BigDecimal insuredAmount;

    @Column(name = "internal_reference_price", precision = 12, scale = 2)
    public BigDecimal internalReferencePrice;

    @Column(name = "created_at", nullable = false, updatable = false)
    public OffsetDateTime createdAt;

    @PrePersist
    public void onCreate() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now(ZoneOffset.UTC);
        }
    }
}
