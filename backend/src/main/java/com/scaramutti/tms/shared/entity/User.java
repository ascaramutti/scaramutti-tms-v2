package com.scaramutti.tms.shared.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.DynamicUpdate;

import java.time.OffsetDateTime;

/*
 * `@DynamicUpdate` porque transacciones distintas escriben esta fila sin coordinarse: el cambio
 * de contrasena, y la edicion y el cambio de estado de un trabajador. Con la escritura de
 * columnas completas, la que llegue segunda pisa lo que la primera acababa de guardar, y una de
 * las dos se pierde sin ningun error. Escribiendo solo lo que cada una cambia, las dos tocan
 * columnas distintas y ninguna borra a la otra.
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

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "role_id", nullable = false)
    public Role role;

    @Column(name = "is_active", nullable = false)
    public Boolean isActive = true;

    @Column(name = "created_at", nullable = false)
    public OffsetDateTime createdAt;
}
