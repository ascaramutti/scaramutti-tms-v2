package com.scaramutti.tms.shared.entity;

import com.scaramutti.tms.shared.util.DateUtils;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/**
 * Conductor de {@code public.drivers}. La ficha se crea junto con su TRABAJADOR, en la misma
 * transaccion que el, y no tiene alta propia: no existe sin su persona. Vive en {@code shared/entity/}
 * como las 31 entidades del proyecto, sin excepcion: es la convencion, no una consecuencia
 * de que el catalogo no tenga modulo.
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

    /**
     * La columna es obligatoria y hasta ahora nadie insertaba fichas, asi que nada la llenaba.
     * Desde que el alta de un trabajador crea la suya, sin esto el primer INSERT choca contra
     * el NOT NULL. Truncado a microsegundos, que es lo que guarda Postgres, como el resto de
     * las entidades.
     */
    @PrePersist
    public void onCreate() {
        if (createdAt == null) createdAt = DateUtils.nowUtcMicros();
    }
}
