package com.scaramutti.tms.shared.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Carreta de la flota ({@code public.trailers}, catálogo compartido con v1). Vista mínima
 * (id + placa + activo), solo-lectura desde almacén (resuelve la unidad destino del retiro).
 */
@Entity
@Table(name = "trailers", schema = "public")
public class Trailer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Integer id;

    @Column(nullable = false, length = 6)
    public String plate;

    @Column(name = "is_active", nullable = false)
    public Boolean isActive;
}
