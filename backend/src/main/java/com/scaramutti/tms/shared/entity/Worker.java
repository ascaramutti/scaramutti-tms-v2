package com.scaramutti.tms.shared.entity;

import com.scaramutti.tms.shared.util.DateUtils;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * Trabajador de {@code public.workers}.
 *
 * <p>El cargo es el ROL: la columna {@code position} sigue en la base con sus valores
 * viejos para que la imagen anterior de la aplicacion arranque si hay que volver atras,
 * pero esta entidad ya no la mapea y nadie la lee. El cargo visible sale de
 * {@code role.description}. La columna se borra en una migracion posterior.
 *
 * <p>{@code hireDate} es cuando la persona entro a la empresa y {@code createdAt} cuando
 * se grabo la fila: no son lo mismo y por eso son dos columnas.
 */
@Entity
@Table(name = "workers")
public class Worker {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Integer id;

    @Column(name = "first_name", nullable = false)
    public String firstName;

    @Column(name = "last_name", nullable = false)
    public String lastName;

    @Column(name = "document_type_id", nullable = false)
    public Integer documentTypeId;

    @Column(name = "document_number", nullable = false, unique = true)
    public String documentNumber;

    public String phone;

    /**
     * El cargo. Se carga con el trabajador porque el cargo visible viaja en TODA respuesta
     * que lleve una persona adentro: el token de sesion, la sesion actual, el listado de
     * trabajadores, el "quien recibe" de un retiro, y cada objeto de usuario embebido en
     * cotizaciones, almacen y operaciones. Diferirlo cambiaria una consulta por muchas.
     */
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "role_id", nullable = false)
    public Role role;

    @Column(name = "hire_date", nullable = false)
    public LocalDate hireDate;

    @Column(name = "is_active", nullable = false)
    public Boolean isActive = true;

    /**
     * Quien creo y quien modifico, como id suelto y no como asociacion a usuario: un
     * usuario apunta a su trabajador, asi que la asociacion cerraria el ciclo trabajador
     * a usuario a trabajador con dos cargas ansiosas de por medio. Mismo molde que las
     * cotizaciones.
     *
     * <p>Los dos son nulos en las filas anteriores a esta unidad: se migraron por SQL del
     * sistema anterior y nadie sabe quien las cargo; escribir ahi un usuario seria un
     * hecho falso en una columna de auditoria.
     */
    @Column(name = "created_by")
    public Integer createdBy;

    @Column(name = "updated_by")
    public Integer updatedBy;

    @Column(name = "created_at", nullable = false)
    public OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    public OffsetDateTime updatedAt;

    public String fullName() {
        String first = firstName != null ? firstName : "";
        String last = lastName != null ? lastName : "";
        return (first + " " + last).trim();
    }

    /**
     * Las marcas de tiempo se truncan a microsegundos, que es la resolucion que guarda
     * Postgres: sin truncar, el valor en memoria y el releido de la fila no coinciden en
     * plataformas con reloj de nanosegundos.
     */
    @PrePersist
    public void onCreate() {
        OffsetDateTime now = DateUtils.nowUtcMicros();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    public void onUpdate() {
        updatedAt = DateUtils.nowUtcMicros();
    }
}
