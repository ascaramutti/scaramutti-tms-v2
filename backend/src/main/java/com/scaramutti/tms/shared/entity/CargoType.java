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

@Entity
@Table(name = "cargo_types")
public class CargoType {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Integer id;

    @Column(nullable = false, unique = true, length = 100)
    public String name;

    // TEXT en BD, nullable.
    @Column(columnDefinition = "text")
    public String description;

    @Column(name = "standard_weight", nullable = false, precision = 10, scale = 2)
    public BigDecimal standardWeight;

    @Column(name = "standard_length", precision = 10, scale = 2)
    public BigDecimal standardLength;

    @Column(name = "standard_width", precision = 10, scale = 2)
    public BigDecimal standardWidth;

    @Column(name = "standard_height", precision = 10, scale = 2)
    public BigDecimal standardHeight;

    // Sin default = true en el field — el caller setea explicitamente (mismo patron que Client).
    @Column(name = "is_active", nullable = false)
    public Boolean isActive;

    // El callback @PrePersist asigna createdAt en runtime. NO se expone en CargoTypeResponse
    // pero la columna existe en BD (NOT NULL DEFAULT) y la entity la mapea para que Hibernate
    // pueda hidratar el campo en SELECTs.
    @Column(name = "created_at", nullable = false, updatable = false)
    public OffsetDateTime createdAt;

    @PrePersist
    public void onCreate() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now(ZoneOffset.UTC);
        }
    }
}
