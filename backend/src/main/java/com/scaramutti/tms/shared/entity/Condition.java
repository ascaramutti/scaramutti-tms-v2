package com.scaramutti.tms.shared.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Condición general del catálogo del PDF de cotización (tabla {@code cotizaciones.conditions},
 * ADR-009). Versionada e inmutable: para "editar" una condición se desactiva (is_active=false)
 * y se inserta una nueva — el {@code text} nunca se actualiza, preservando el snapshot de las
 * cotizaciones ya emitidas que la referencian. El catálogo se siembra por migración/seed; este
 * módulo solo lo lee.
 */
@Entity
@Table(name = "conditions", schema = "cotizaciones")
public class Condition {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Integer id;

    @Column(nullable = false, columnDefinition = "text")
    public String text;

    @Column(name = "display_order", nullable = false)
    public Integer displayOrder;

    @Column(name = "is_active", nullable = false)
    public Boolean isActive = true;
}
