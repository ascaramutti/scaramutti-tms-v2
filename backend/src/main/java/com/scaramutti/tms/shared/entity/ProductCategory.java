package com.scaramutti.tms.shared.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "product_categories", schema = "almacen")
public class ProductCategory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Integer id;

    @Column(nullable = false, unique = true, length = 100)
    public String name;

    @Column(columnDefinition = "text")
    public String description;

    // Sin default = true en el field — el mapper lo setea explicitamente (mismo
    // patron fail-fast que Client/CargoType: un caller que bypasee el mapper
    // sin setear isActive falla con NOT NULL violation en vez de guardar en
    // silencio).
    @Column(name = "is_active", nullable = false)
    public Boolean isActive;
}
