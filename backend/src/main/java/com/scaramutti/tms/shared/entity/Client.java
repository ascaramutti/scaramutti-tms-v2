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
@Table(name = "clients")
public class Client {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Integer id;

    @Column(nullable = false, unique = true, length = 200)
    public String name;

    @Column(nullable = false, unique = true, length = 11)
    public String ruc;

    @Column(length = 9)
    public String phone;

    @Column(name = "contact_name", length = 100)
    public String contactName;

    // El mapper lo setea explícitamente (en CREATE, vía @Mapping constant="true").
    // No usamos default = true en el field para que cualquier caller que bypaseen
    // el mapper sin setear isActive falle con NOT NULL violation (fail-fast).
    @Column(name = "is_active", nullable = false)
    public Boolean isActive;

    // Lo asigna automáticamente el callback @PrePersist (más cohesivo que setearlo
    // en el service). El DEFAULT CURRENT_TIMESTAMP de la BD queda como safety net
    // para inserts via SQL directo.
    @Column(name = "created_at", nullable = false, updatable = false)
    public OffsetDateTime createdAt;

    @PrePersist
    public void onCreate() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now(ZoneOffset.UTC);
        }
    }
}
