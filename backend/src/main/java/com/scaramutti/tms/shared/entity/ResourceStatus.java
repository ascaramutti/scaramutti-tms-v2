package com.scaramutti.tms.shared.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Disponibilidad de un recurso de flota, de {@code public.resource_statuses}.
 *
 * <p>Catalogo de solo lectura: se lee para traducir entre el nombre guardado y el dominio de
 * la API, y para resolver que fila apuntar al crear una ficha de conductor. Nadie lo escribe
 * desde la aplicacion.
 */
@Entity
@Table(name = "resource_statuses")
public class ResourceStatus {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Integer id;

    @Column(nullable = false)
    public String name;

    public String description;

    @Column(name = "is_active", nullable = false)
    public Boolean isActive = true;
}
