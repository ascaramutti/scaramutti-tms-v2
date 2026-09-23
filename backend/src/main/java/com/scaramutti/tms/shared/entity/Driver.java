package com.scaramutti.tms.shared.entity;

import com.scaramutti.tms.shared.util.DateUtils;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import org.hibernate.annotations.DynamicUpdate;

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
 *
 * <p>{@code @DynamicUpdate} por el mismo motivo que {@code User}, y desde que la edicion de un
 * trabajador la convirtio en una fila que se ESCRIBE: la edicion reescribiria columnas completas
 * desde una fila que leyo sin bloqueo, asi que un cuerpo que solo corrige la licencia devolveria
 * {@code status_id} e {@code is_active} a los valores que tenian cuando arranco la transaccion.
 * Hoy nadie mas escribe esas dos columnas y por eso no rompe nada; el dia que un viaje ponga a un
 * conductor NO DISPONIBLE al asignarlo, una correccion de telefono se lo revertiria en silencio y
 * con 200. Escribiendo solo lo que cada transaccion cambia, las dos tocan columnas distintas.
 */
@Entity
@DynamicUpdate
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
