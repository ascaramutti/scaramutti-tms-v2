package com.scaramutti.tms.shared.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@Entity
@Table(name = "suppliers", schema = "almacen")
public class Supplier {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Integer id;

    @Column(nullable = false, unique = true, length = 200)
    public String name;

    @Column(unique = true, length = 11)
    public String ruc;

    @Column(length = 9)
    public String phone;

    @Column(name = "contact_name", length = 100)
    public String contactName;

    // Sin default = true en el field — el mapper lo setea explicitamente
    // (fail-fast, mismo patron que Client/CargoType/ProductCategory).
    @Column(name = "is_active", nullable = false)
    public Boolean isActive;

    @Column(name = "created_at", nullable = false, updatable = false)
    public OffsetDateTime createdAt;

    @PrePersist
    public void onCreate() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now(ZoneOffset.UTC);
        }
    }
}
