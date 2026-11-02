package com.scaramutti.tms.shared.entity;

import com.scaramutti.tms.shared.util.DateUtils;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import org.hibernate.annotations.DynamicUpdate;

import java.time.OffsetDateTime;

/*
 * `@DynamicUpdate` porque transacciones distintas escriben esta fila sin coordinarse: el cambio
 * de contrasena y la baja de un trabajador. El cambio toma la fila antes de cargarla, pero la baja
 * la lee sin tomarla: con la escritura de columnas completas, la baja pisaria el hash y la version
 * que el cambio acaba de guardar; escribiendo solo lo que cada una cambia, ninguna borra a la otra.
 * La autoria tambien va por columna: la ultima en confirmar deja su hora, quizas anterior a la del
 * cambio que pisa, y su autor solo si no es el que leyo (cada fila de rastro guarda los suyos).
 */
@DynamicUpdate
@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Integer id;

    @OneToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "worker_id", nullable = false)
    public Worker worker;

    @Column(nullable = false, unique = true)
    public String username;

    @Column(name = "password_hash", nullable = false)
    public String passwordHash;

    @Column(name = "is_active", nullable = false)
    public Boolean isActive = true;

    @Column(name = "created_at", nullable = false)
    public OffsetDateTime createdAt;

    /**
     * Quien creo la cuenta y quien la cambio por ultima vez, como id suelto y no como asociacion,
     * igual que en el trabajador: una asociacion a la propia tabla traeria con cada cuenta la de su
     * autor, con su trabajador y su cargo. Quien la creo es nulo en las cuentas anteriores al rastro y
     * en las del sembrador de dev, que no tienen quien las cree; quien la cambio, hasta el primer cambio
     * que la firma.
     */
    @Column(name = "created_by")
    public Integer createdBy;

    @Column(name = "updated_by")
    public Integer updatedBy;

    /**
     * Lo mueve {@link #markChangedBy} y no cada escritura de la fila, por eso no hay
     * {@code @PreUpdate}: el sembrador de dev la reescribe sin que sea el cambio de nadie.
     */
    @Column(name = "updated_at", nullable = false)
    public OffsetDateTime updatedAt;

    /** La version de sus sesiones: viaja en el token de renovacion, que renueva solo si coincide. */
    @Column(name = "session_version", nullable = false)
    public Integer sessionVersion = 0;

    /**
     * Truncadas a microsegundos como en el trabajador. Una cuenta nace sin cambios: su marca de
     * cambio es siempre la de su alta, tambien si quien la crea ya puso el alta (el sembrador) o firmo.
     */
    @PrePersist
    public void onCreate() {
        if (createdAt == null) createdAt = DateUtils.nowUtcMicros();
        updatedAt = createdAt;
    }

    /**
     * Un cambio que deja rastro de la cuenta firma quien lo hizo y cuando. A la base van solo las que
     * cambiaron: el mismo autor que ya tenia no se escribe (ver arriba).
     */
    public void markChangedBy(Integer actorUserId) {
        updatedBy = actorUserId;
        updatedAt = DateUtils.nowUtcMicros();
    }
}
