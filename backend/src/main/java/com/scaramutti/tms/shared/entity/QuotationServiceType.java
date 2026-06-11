package com.scaramutti.tms.shared.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "quotation_service_types", schema = "cotizaciones")
public class QuotationServiceType {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Integer id;

    @Column(nullable = false, unique = true, length = 10)
    public String code;

    @Column(nullable = false, length = 100)
    public String name;

    @Column(columnDefinition = "TEXT")
    public String description;

    @Column(name = "is_active", nullable = false)
    public Boolean isActive = true;
}
