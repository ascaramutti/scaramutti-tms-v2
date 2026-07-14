package com.scaramutti.tms.shared.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Vehículo escolta de la flota ({@code public.escort_vehicles}). Vista mínima (id + placa +
 * activo), solo-lectura desde almacén (resuelve la unidad destino del retiro).
 */
@Entity
@Table(name = "escort_vehicles", schema = "public")
public class EscortVehicle {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Integer id;

    @Column(nullable = false, length = 6)
    public String plate;

    @Column(name = "is_active", nullable = false)
    public Boolean isActive;
}
