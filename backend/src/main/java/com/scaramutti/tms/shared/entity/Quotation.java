package com.scaramutti.tms.shared.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * Cabecera de cotizacion. Tabla `cotizaciones.quotations`.
 *
 * Notas:
 *  - `code` (formato `YYYY-NNNNN`) lo genera el QuotationCodeGeneratorService
 *    dentro de la tx con advisory lock por anio (reinicio anual automatico).
 *  - `quotationType` y `status` son strings con CHECK constraint en BD.
 *    Se exponen como enums en los DTOs (QuotationType, QuotationStatus).
 *  - `updated_at` se asigna en cada update via @PreUpdate (sirve como ETag).
 *  - `contact_name` y `contact_phone` son snapshots del contacto al momento
 *    de cotizar — no se re-sincronizan si el cliente master cambia. La razon
 *    es preservar el dato historico con el que se emitio la cotizacion.
 *  - Items y standby costs se manejan como child entities en el service
 *    (sin @OneToMany para evitar fetch implicito; los repositorios los
 *    persisten explicitamente).
 */
@Entity
@Table(name = "quotations", schema = "cotizaciones")
public class Quotation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(nullable = false, unique = true, length = 15)
    public String code;

    @Column(name = "quotation_type", nullable = false, length = 20)
    public String quotationType;

    @Column(nullable = false, length = 20)
    public String status;

    @Column(name = "client_id", nullable = false)
    public Integer clientId;

    @Column(name = "contact_name", nullable = false, length = 200)
    public String contactName;

    @Column(name = "contact_phone", length = 9)
    public String contactPhone;

    @Column(name = "currency_id", nullable = false)
    public Integer currencyId;

    @Column(name = "payment_term_id")
    public Integer paymentTermId;

    @Column(name = "tentative_service_date")
    public LocalDate tentativeServiceDate;

    @Column(name = "validity_days", nullable = false)
    public Integer validityDays;

    @Column(length = 255)
    public String origin;

    @Column(length = 255)
    public String destination;

    @Column(name = "created_by", nullable = false)
    public Integer createdBy;

    @Column(name = "updated_by", nullable = false)
    public Integer updatedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    public OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    public OffsetDateTime updatedAt;

    @PrePersist
    public void onCreate() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    public void onUpdate() {
        updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }
}
