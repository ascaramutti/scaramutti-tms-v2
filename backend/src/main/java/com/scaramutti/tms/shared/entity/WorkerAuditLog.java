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
 * Una fila de la bitacora de trabajadores: quien cambio que, cuando y por que.
 *
 * <p>El trabajador y el autor van como id suelto y no como asociacion, igual que en las
 * columnas de auditoria del trabajador y por el mismo motivo: un usuario apunta a su
 * trabajador, asi que la asociacion cerraria un ciclo con dos cargas ansiosas de por medio.
 *
 * <p>{@code changeType} es texto y no un enum mapeado, para que esta entidad, que vive en el
 * paquete compartido, no importe un enum del modulo. El dominio lo cierra la restriccion de la
 * columna.
 *
 * <p>Los cambios sobre el USUARIO que nacen de tocar un trabajador se guardan como filas de ese
 * trabajador, con el nombre del campo prefijado: es la unica prueba de por que una cuenta
 * cambio de permisos o dejo de entrar, y se encuentra por el trabajador.
 */
@Entity
@Table(name = "worker_audit_logs")
public class WorkerAuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "worker_id", nullable = false)
    public Integer workerId;

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

    /** Obligatorio solo al cambiar el numero de documento, y eso lo exige el servicio. */
    public String reason;

    @Column(name = "changed_by", nullable = false)
    public Integer changedBy;

    @Column(name = "logged_at", nullable = false)
    public OffsetDateTime loggedAt;

    /**
     * La columna tiene valor por omision, pero la marca se fija igual desde el codigo: asi se
     * trunca a microsegundos como el resto de las entidades y el valor en memoria coincide con
     * el de la fila.
     */
    @PrePersist
    public void onCreate() {
        if (loggedAt == null) loggedAt = DateUtils.nowUtcMicros();
    }
}
