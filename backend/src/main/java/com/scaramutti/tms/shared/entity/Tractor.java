package com.scaramutti.tms.shared.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Tracto de la flota ({@code public.tractors}, catálogo compartido con v1). Vista mínima
 * (id + placa + activo): almacén solo lo LEE para resolver la unidad destino de un retiro
 * ({@code FleetUnitRef}). El ABM de flota es de otro módulo (fase RRHH); acá no se escribe.
 */
@Entity
@Table(name = "tractors", schema = "public")
public class Tractor {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Integer id;

    @Column(nullable = false, length = 6)
    public String plate;

    @Column(name = "is_active", nullable = false)
    public Boolean isActive;
}
