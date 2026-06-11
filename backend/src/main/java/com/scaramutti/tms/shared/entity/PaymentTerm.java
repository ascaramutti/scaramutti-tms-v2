package com.scaramutti.tms.shared.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "payment_terms", schema = "cotizaciones")
public class PaymentTerm {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Integer id;

    @Column(nullable = false, unique = true, length = 100)
    public String name;

    @Column(nullable = false)
    public Integer days = 0;

    @Column(name = "is_active", nullable = false)
    public Boolean isActive = true;
}
