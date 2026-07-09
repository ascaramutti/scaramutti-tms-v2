package com.scaramutti.tms.shared.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;

/**
 * Producto del catalogo de almacen. Vive en {@code shared/entity/} por
 * convencion del proyecto (mismo criterio que Supplier/ProductCategory).
 *
 * Identidad de negocio (Delta-2, RN-WH10): la unicidad es COMPUESTA
 * case-insensitive sobre (name, brand, part_number) via el indice funcional
 * {@code uq_products_identity} (V002, con COALESCE de los opcionales). El
 * {@code code} (SKU) es unico aparte ({@code uq_products_code_ci}, V005).
 *
 * {@code attributes} = objeto JSON libre clave-valor mapeado a JSONB nativo
 * (Hibernate 6 {@code @JdbcTypeCode(JSON)}); nunca null (default {@code {}}).
 *
 * {@code createdBy} es la FK plana a {@code public.users(id)} (mismo patron que
 * {@code Quotation.createdBy}: se guarda el id, no se embebe la entidad User).
 */
@Entity
@Table(name = "products", schema = "almacen")
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Integer id;

    @Column(unique = true, length = 30)
    public String code;

    @Column(nullable = false, length = 200)
    public String name;

    @Column(name = "category_id", nullable = false)
    public Integer categoryId;

    @Column(name = "unit_of_measure_id", nullable = false)
    public Integer unitOfMeasureId;

    @Column(length = 100)
    public String brand;

    @Column(name = "part_number", length = 100)
    public String partNumber;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    public Map<String, String> attributes;

    @Column(name = "min_stock", nullable = false)
    public BigDecimal minStock;

    @Column(columnDefinition = "text")
    public String observations;

    // Sin default = true en el field — el mapper lo setea explicitamente
    // (fail-fast, mismo patron que Supplier/ProductCategory).
    @Column(name = "is_active", nullable = false)
    public Boolean isActive;

    @Column(name = "created_by", nullable = false)
    public Integer createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    public OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    public OffsetDateTime updatedAt;

    @PrePersist
    public void onCreate() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
    }

    @PreUpdate
    public void onUpdate() {
        updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }
}
