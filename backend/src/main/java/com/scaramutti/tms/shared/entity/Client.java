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
 * Esta entidad NO puede llevar `@DynamicUpdate`, y no es una preferencia de estilo.
 *
 * Con esa anotacion el UPDATE lleva solo las columnas que quedaron sucias, asi que un campo
 * que el cuerpo trae igual a como se cargo no se escribe y sobrevive lo que haya escrito otra
 * edicion en el medio: gana la PRIMERA escritura. Las dos specs de este endpoint prometen lo
 * contrario. Lo mide
 * {@code ClientByIdResourceTest::update_thatChangesAField_alsoOverwritesTheFieldsThatArrivedUnchanged}.
 *
 * Que la edicion no toque `is_active` lo consigue el `updatable = false` de esa columna, no la
 * escritura dinamica. Son dos cosas distintas y solo una es negociable.
 */
@Entity
@Table(name = "clients")
public class Client {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Integer id;

    @Column(nullable = false, unique = true, length = 200)
    public String name;

    @Column(nullable = false, unique = true, length = 11)
    public String ruc;

    @Column(length = 9)
    public String phone;

    @Column(name = "contact_name", length = 100)
    public String contactName;

    // El mapper lo setea explícitamente (en CREATE, vía @Mapping constant="true").
    // No usamos default = true en el field para que cualquier caller que bypaseen
    // el mapper sin setear isActive falle con NOT NULL violation (fail-fast).
    //
    // `updatable = false` es lo que hace cierta la regla de que editar un cliente NO lo
    // reactiva. El UPDATE de una edición que cambia algo lleva todas las columnas
    // actualizables, así que sin esto llevaría is_active con el valor leído al abrir la
    // transacción: si alguien desactiva
    // al cliente entre esa lectura y el guardado, el guardado lo resucita. Que el mapper de la
    // edición ignore el campo protege la memoria, no la columna.
    //
    // Consecuencia para quien escriba la desactivación, que es el segundo escritor y todavía no
    // existe: por acá NO va a poder cambiarla. Tiene que escribirla con una consulta propia.
    @Column(name = "is_active", nullable = false, updatable = false)
    public Boolean isActive;

    // Lo asigna automáticamente el callback @PrePersist (más cohesivo que setearlo
    // en el service). El DEFAULT CURRENT_TIMESTAMP de la BD queda como safety net
    // para inserts via SQL directo.
    @Column(name = "created_at", nullable = false, updatable = false)
    public OffsetDateTime createdAt;

    @PrePersist
    public void onCreate() {
        if (createdAt == null) {
            createdAt = DateUtils.nowUtcMicros();
        }
    }
}
