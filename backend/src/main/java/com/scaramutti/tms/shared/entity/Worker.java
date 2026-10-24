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
import java.util.StringJoiner;

/**
 * Trabajador de {@code public.workers}.
 *
 * <p>El cargo es el ROL: su nombre visible sale de {@code role.description}, que es lo que las
 * respuestas llaman {@code position}.
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

    /**
     * Los apellidos juntos, como los guardaba la columna de siempre: el paterno y, si hay, un
     * espacio y el materno. La leen la ficha, la edicion (que la compara con el cuerpo) y la imagen
     * anterior. Esta columna y las dos de las partes las escribe solo {@link #assignSurnames}.
     */
    @Column(name = "last_name", nullable = false)
    public String lastName;

    @Column(name = "paternal_last_name", nullable = false)
    public String paternalLastName;

    /** Nulo si no tiene: la base rechaza el materno vacio o de espacios. */
    @Column(name = "maternal_last_name")
    public String maternalLastName;

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
     * <p>Las filas anteriores a esta unidad las cargo el usuario admin y quedan firmadas por
     * el en {@code createdBy} (nulo si no habia admin al migrar); {@code updatedBy} queda nulo
     * hasta su primera edicion, baja o reactivacion desde la aplicacion.
     */
    @Column(name = "created_by")
    public Integer createdBy;

    @Column(name = "updated_by")
    public Integer updatedBy;

    @Column(name = "created_at", nullable = false)
    public OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    public OffsetDateTime updatedAt;

    /**
     * El nombre completo: nombres, paterno y materno, con un espacio entre cada uno y sin el
     * materno si no tiene, recortado. Es la regla de {@code WorkerRepository.fullNameExpression}
     * en Java; todo nombre de un trabajador que arma el backend sale de una de las dos.
     */
    public String fullName() {
        String first = firstName != null ? firstName : "";
        return (first + " " + joinedSurnames(paternalLastName, maternalLastName)).trim();
    }

    /**
     * El unico que escribe los apellidos. Deja {@code lastName} compuesto como lo compone el
     * disparador de la base, asi la fila que se graba ya esta de acuerdo y el disparador no cambia
     * nada: si la cambiara, sin {@code @DynamicUpdate} la siguiente escritura de la entidad en la
     * misma sesion mandaria el {@code lastName} viejo, y el disparador lo copiaria al paterno.
     */
    public void assignSurnames(String paternal, String maternal) {
        paternalLastName = paternal;
        maternalLastName = maternal;
        lastName = joinedSurnames(paternal, maternal);
    }

    /** Como {@code concat_ws(' ', ...)}: un espacio entre cada parte, sin las nulas. */
    private static String joinedSurnames(String paternal, String maternal) {
        StringJoiner surnames = new StringJoiner(" ");
        if (paternal != null) surnames.add(paternal);
        if (maternal != null) surnames.add(maternal);
        return surnames.toString();
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
