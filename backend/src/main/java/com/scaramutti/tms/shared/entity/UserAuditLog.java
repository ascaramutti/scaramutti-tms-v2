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
 * Una fila del rastro de las cuentas: quien cambio que y cuando.
 *
 * <p>La cuenta y el autor van como id suelto y no como asociacion: cada una traeria con la fila una
 * cuenta, con su trabajador y su cargo. {@code changeType} es texto: esta entidad vive en el paquete
 * compartido y no importa enums de ningun modulo; el dominio lo cierra la restriccion de la columna.
 */
@Entity
@Table(name = "user_audit_logs")
public class UserAuditLog {

    /** El cambio de la propia contrasena. Lo escribe la sesion, que no depende del modulo de cuentas. */
    public static final String PASSWORD_CHANGED = "PASSWORD_CHANGED";
    public static final String PASSWORD_FIELD = "password";
    public static final String PASSWORD_LABEL = "Contraseña";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "user_id", nullable = false)
    public Integer userId;

    @Column(name = "change_type", nullable = false, length = 30)
    public String changeType;

    @Column(name = "field_name", length = 50)
    public String fieldName;

    /** Etiqueta en español del campo, para la pantalla de historial que todavia no existe. */
    @Column(name = "field_label", length = 100)
    public String fieldLabel;

    @Column(name = "old_value")
    public String oldValue;

    @Column(name = "new_value")
    public String newValue;

    @Column(name = "changed_by", nullable = false)
    public Integer changedBy;

    @Column(name = "logged_at", nullable = false)
    public OffsetDateTime loggedAt;

    /**
     * La fila del cambio de la propia contrasena: la firma la misma cuenta y va sin valores, ni la
     * clave ni su hash. Dice quien y cuando, no si la cambio la persona o quien entro con su clave.
     */
    public static UserAuditLog ownPasswordChange(Integer userId) {
        UserAuditLog log = new UserAuditLog();
        log.userId = userId;
        log.changeType = PASSWORD_CHANGED;
        log.fieldName = PASSWORD_FIELD;
        log.fieldLabel = PASSWORD_LABEL;
        log.changedBy = userId;
        return log;
    }

    /** Como en el rastro de trabajadores: la marca se fija desde el codigo, truncada a microsegundos. */
    @PrePersist
    public void onCreate() {
        if (loggedAt == null) loggedAt = DateUtils.nowUtcMicros();
    }
}
