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
 * Auditoria del servicio ({@code operaciones.service_audit_logs}): el rastro de QUE cambio,
 * quien y por que. Es propia del modulo y no comparte tabla con la de almacen porque el shape
 * es distinto: cuelga de un solo tipo de entidad y guarda la justificacion de la edicion.
 *
 * <p>Los valores viejo y nuevo son TEXT (el sistema anterior los truncaba a 255 y perdia
 * observaciones largas). Se escribe desde el dia 1; mostrarla en la interfaz quedo diferido.
 */
@Entity
@Table(name = "service_audit_logs", schema = "operaciones")
public class ServiceAuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "service_id", nullable = false)
    public Long serviceId;

    @Column(name = "changed_by", nullable = false)
    public Integer changedBy;

    @Column(name = "change_type", nullable = false, length = 50)
    public String changeType;

    @Column(name = "field_name", length = 50)
    public String fieldName;

    @Column(name = "field_label", length = 100)
    public String fieldLabel;

    @Column(name = "old_value", columnDefinition = "text")
    public String oldValue;

    @Column(name = "new_value", columnDefinition = "text")
    public String newValue;

    /** Justificacion de la edicion, o la descripcion de la accion cuando no la hay. */
    @Column(nullable = false, columnDefinition = "text")
    public String description;

    @Column(name = "logged_at", nullable = false, updatable = false)
    public OffsetDateTime loggedAt;

    @PrePersist
    public void onCreate() {
        if (loggedAt == null) {
            loggedAt = DateUtils.nowUtcMicros();
        }
    }
}
