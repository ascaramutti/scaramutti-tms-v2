package com.scaramutti.tms.shared.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.DynamicUpdate;

import java.time.OffsetDateTime;

/*
 * `@DynamicUpdate` porque transacciones distintas escriben esta fila sin coordinarse: el cambio
 * de contrasena y el cambio de estado de un trabajador. El cambio toma la fila antes de cargarla,
 * pero la baja la lee sin tomarla: con la escritura de columnas completas, la baja pisaria el hash
 * y la version que el cambio acaba de guardar; escribiendo solo lo que cada una cambia, ninguna
 * borra a la otra.
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

    /** La version de sus sesiones: viaja en el token de renovacion, que renueva solo si coincide. */
    @Column(name = "session_version", nullable = false)
    public Integer sessionVersion = 0;
}
