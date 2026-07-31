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
 * Entrada de la bitacora del viaje ({@code operaciones.service_events}): UNA fila por accion
 * (alta, asignacion, cambio de estado, edicion o nota suelta), consultable, ordenable y
 * atribuible. Reemplaza al campo de texto concatenado del sistema anterior, donde toda la
 * bitacora vivia en una sola celda y no habia forma de saber quien escribio que.
 */
@Entity
@Table(name = "service_events", schema = "operaciones")
public class ServiceEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "service_id", nullable = false)
    public Long serviceId;

    @Column(name = "event_type", nullable = false, length = 20)
    public String eventType;

    @Column(nullable = false, columnDefinition = "text")
    public String note;

    @Column(name = "created_by", nullable = false)
    public Integer createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    public OffsetDateTime createdAt;

    @PrePersist
    public void onCreate() {
        if (createdAt == null) {
            createdAt = DateUtils.nowUtcMicros();
        }
    }
}
