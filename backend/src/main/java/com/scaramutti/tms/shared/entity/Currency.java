package com.scaramutti.tms.shared.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "currencies")
public class Currency {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Integer id;

    @Column(nullable = false, unique = true, length = 3)
    public String code;

    @Column(nullable = false, length = 5)
    public String symbol;

    @Column(length = 50)
    public String name;

    @Column(name = "is_active", nullable = false)
    public Boolean isActive = true;
}
