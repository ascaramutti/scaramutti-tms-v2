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
 * Registro de auditoria del modulo Almacen ({@code almacen.audit_logs}): una fila por
 * cambio (edicion de un campo o anulacion) de una entrada o retiro. Vive en
 * {@code shared/entity/} por convencion. Se escribe desde A9 (primer writer); la
 * lectura hoy solo alimenta el {@code lastEdit} del detalle (el ultimo FIELD_EDIT), el
 * historial completo queda consultable en la tabla.
 *
 * <p>{@code entityType}/{@code changeType} se guardan como String (respaldados por los
 * CHECK de V002); las capas de servicio usan los enums {@code AuditEntityType}/
 * {@code AuditChangeType} y guardan su {@code name()}, para no invertir la dependencia
 * {@code shared -> warehouse}.
 */
@Entity
@Table(name = "audit_logs", schema = "almacen")
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "entity_type", nullable = false, length = 30)
    public String entityType;

    @Column(name = "entity_id", nullable = false)
    public Integer entityId;

    @Column(name = "change_type", nullable = false, length = 30)
    public String changeType;

    @Column(name = "field_name", length = 50)
    public String fieldName;

    @Column(name = "field_label", length = 100)
    public String fieldLabel;

    @Column(name = "old_value", columnDefinition = "text")
    public String oldValue;

    @Column(name = "new_value", columnDefinition = "text")
    public String newValue;

    @Column(nullable = false, columnDefinition = "text")
    public String reason;

    @Column(name = "changed_by", nullable = false)
    public Integer changedBy;

    @Column(name = "logged_at", nullable = false, updatable = false)
    public OffsetDateTime loggedAt;

    @PrePersist
    public void onCreate() {
        if (loggedAt == null) {
            loggedAt = DateUtils.nowUtcMicros();
        }
    }
}
