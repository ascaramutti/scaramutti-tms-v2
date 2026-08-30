package com.scaramutti.tms.shared.entity;

import com.scaramutti.tms.shared.util.DateUtils;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * Servicio de transporte: el viaje ({@code operaciones.services}). Es el nucleo del modulo y
 * el recurso que la UI llama por su codigo ({@code SRV-0042}), no por su id.
 *
 * <p>El id es {@code BIGSERIAL} (a diferencia del resto de los modulos, que usan SERIAL): los
 * repositorios de operaciones tipan {@code Long}.
 *
 * <p>Estado, ambito del viaje y los tipos de la bitacora son dominios cerrados: la columna es
 * VARCHAR con CHECK en la BD y el enum vive en {@code operations/model/}. La flota y el
 * conductor viven en {@code public} y se referencian por FK plana, sin relacion JPA: este
 * modulo NO escribe nada de {@code public}.
 *
 * <p>La version que respalda el ETag es {@code updated_at}, truncado a micros como en el resto
 * de la casa para que el valor del POST coincida con el que relee un GET.
 */
@Entity
@Table(name = "services", schema = "operaciones")
public class Service {

    /**
     * Id de la fila, ASIGNADO POR LA APLICACION: quien persista un servicio tiene que pedir
     * antes el proximo valor de la secuencia del BIGSERIAL ({@code ServiceRepository.nextId()}).
     * Sin id, el persist falla ruidosamente en vez de insertar una fila incompleta.
     *
     * <p>El motivo de no delegarlo a JPA es que el codigo del viaje se deriva del id, y ninguna
     * estrategia de generacion permite completarlo despues: con IDENTITY el INSERT se ejecuta
     * dentro del propio {@code persist}, y con SEQUENCE Hibernate encola la insercion con una
     * FOTO del estado tomada en ese momento, asi que un campo asignado entre el {@code persist}
     * y el {@code flush} no entra en la sentencia y la fila sale con el codigo en null (ambas
     * verificadas contra esta tabla).
     *
     * <p>La secuencia sigue siendo la autoridad del numero, asi que el script de migracion de
     * datos puede insertar los ids historicos y reposicionarla despues.
     */
    @Id
    public Long id;

    /** {@code SRV-} + id con ceros a la izquierda (minimo 4 digitos); lo asigna el backend. */
    @Column(nullable = false, length = 15)
    public String code;

    @Column(name = "client_id", nullable = false)
    public Integer clientId;

    @Column(nullable = false, length = 255)
    public String origin;

    @Column(nullable = false, length = 255)
    public String destination;

    /** Fecha tentativa de salida; PUEDE ser pasada (registro retroactivo). */
    @Column(name = "tentative_date", nullable = false)
    public LocalDate tentativeDate;

    @Column(name = "trip_scope", nullable = false, length = 10)
    public String tripScope;

    @Column(name = "cargo_type_id", nullable = false)
    public Integer cargoTypeId;

    @Column(nullable = false)
    public BigDecimal weight;

    @Column
    public BigDecimal length;

    @Column
    public BigDecimal width;

    @Column
    public BigDecimal height;

    @Column(columnDefinition = "text")
    public String observations;

    @Column(nullable = false)
    public BigDecimal price;

    @Column(name = "currency_id", nullable = false)
    public Integer currencyId;

    @Column(nullable = false, length = 30)
    public String status;

    @Column(name = "driver_id")
    public Integer driverId;

    @Column(name = "tractor_id")
    public Integer tractorId;

    @Column(name = "trailer_id")
    public Integer trailerId;

    @Column(name = "start_date_time")
    public OffsetDateTime startDateTime;

    @Column(name = "end_date_time")
    public OffsetDateTime endDateTime;

    @Column(name = "created_by", nullable = false, updatable = false)
    public Integer createdBy;

    @Column(name = "updated_by", nullable = false)
    public Integer updatedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    public OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    public OffsetDateTime updatedAt;

    @PrePersist
    public void onCreate() {
        if (createdAt == null) {
            createdAt = DateUtils.nowUtcMicros();
        }
        if (updatedAt == null) {
            updatedAt = createdAt;
        }
    }

    /**
     * Mueve la version cuando la fila se modifica A TRAVES DE LA ENTITY, como el resto de las
     * entities versionadas de la casa. No alcanza con que el servicio que edita la asigne a mano:
     * los endpoints que vienen (asignar recursos, cambiar de estado) mutan esta misma entity, y el
     * que se olvide del bump dejaria la version congelada — un cliente con el ETag ANTERIOR
     * pasaria el {@code If-Match} y pisaria el cambio con un 200 limpio.
     *
     * <p>⚠️ NO cubre los UPDATE masivos por consulta (JPQL o nativa): ese gancho no corre ahi. La
     * casa ya tiene una forma asi (el job de vencimiento de cotizaciones, que a proposito no toca
     * su {@code updated_at}). Todo UPDATE masivo sobre esta tabla tiene que setear
     * {@code updated_at} EN LA MISMA SENTENCIA, o la version queda congelada y el
     * {@code If-Match} deja de proteger. No resolverlo con un trigger: pisaria el valor que la
     * aplicacion ya mando en el volcado, y el ETag que devolvio la respuesta dejaria de coincidir
     * con lo confirmado.
     *
     * <p>Tampoco mueve {@code updated_by}: esa la sigue asignando quien edita.
     */
    @PreUpdate
    public void onUpdate() {
        updatedAt = DateUtils.nowUtcMicros();
    }
}
