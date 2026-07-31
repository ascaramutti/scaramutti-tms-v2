package com.scaramutti.tms.shared.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/**
 * Conductor de {@code public.drivers} (catalogo compartido con v1, read-only desde v2: el
 * alta pertenece a la futura gestion de flota y personal). Vive en {@code shared/entity/}
 * porque {@code drivers} no tiene un modulo dueno en v2, igual que {@link Worker}.
 *
 * <p>El nombre no esta aca: sale del trabajador asociado ({@code worker_id}), y la
 * disponibilidad es una FK al catalogo {@code public.resource_statuses}. El listado los
 * resuelve por join en el repositorio.
 */
@Entity
@Table(name = "drivers")
public class Driver {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Integer id;

    @Column(name = "worker_id", nullable = false, unique = true)
    public Integer workerId;

    @Column(name = "license_number", nullable = false, unique = true)
    public String licenseNumber;

    /** Categoria de la licencia (A-IIb, A-IIIc...); la columna de v1 se llama {@code category}. */
    @Column(name = "category")
    public String licenseCategory;

    @Column(name = "status_id", nullable = false)
    public Integer statusId;

    @Column(name = "is_active", nullable = false)
    public Boolean isActive = true;

    @Column(name = "created_at", nullable = false)
    public OffsetDateTime createdAt;
}
