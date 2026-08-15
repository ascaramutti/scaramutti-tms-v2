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
 * Refuerzo de un viaje que YA estaba en ruta ({@code operaciones.service_assignments}): el relevo
 * de un conductor que agoto su descanso, la unidad de apoyo que sale a un varado.
 *
 * <p>Una fila es un PEDIDO, no un recurso: puede traer conductor, tracto y carreta a la vez, o uno
 * solo, y por eso los tres son opcionales por separado (la tabla exige al menos uno con un CHECK).
 * El motivo es obligatorio, a diferencia de la nota de la asignacion principal, porque sumar un
 * refuerzo siempre responde a algo que paso en ruta, y es lo que el reporte semanal va a mostrar
 * al lado de cada conductor adicional cuando exista.
 *
 * <p>Los recursos son ids sueltos y no asociaciones, igual que en {@link Service} y por el mismo
 * motivo: viven en el schema {@code public} que se comparte con el sistema anterior, y mapearlos
 * como asociaciones ataria este modulo a ese schema.
 */
@Entity
@Table(name = "service_assignments", schema = "operaciones")
public class ServiceAssignment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "service_id", nullable = false)
    public Long serviceId;

    @Column(name = "tractor_id")
    public Integer tractorId;

    @Column(name = "trailer_id")
    public Integer trailerId;

    @Column(name = "driver_id")
    public Integer driverId;

    @Column(nullable = false, columnDefinition = "text")
    public String reason;

    @Column(name = "assigned_by", nullable = false)
    public Integer assignedBy;

    @Column(name = "assigned_at", nullable = false, updatable = false)
    public OffsetDateTime assignedAt;

    @PrePersist
    public void onCreate() {
        if (assignedAt == null) {
            assignedAt = DateUtils.nowUtcMicros();
        }
    }
}
