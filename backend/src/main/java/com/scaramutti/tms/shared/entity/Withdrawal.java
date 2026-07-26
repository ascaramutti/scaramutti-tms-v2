package com.scaramutti.tms.shared.entity;

import com.scaramutti.tms.shared.util.DateUtils;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Retiro (salida de stock) de almacén ({@code almacen.withdrawals}). Cada retiro ACTIVE
 * resta stock (vía la VIEW {@code stock_movements}); los anulados no cuentan (RN-WH3). FK
 * plana al producto, al trabajador que recibe ({@code public.workers}) y, a lo sumo, a UNA
 * unidad de flota disyunta (tractor | carreta | escolta, CHECK en BD).
 *
 * <p>La versión que respalda el ETag es {@code updated_at}: en el alta se inicializa igual
 * a {@code withdrawn_at} (el retiro recién creado no se editó) y la edición/anulación la
 * bumpean para el If-Match optimista, igual que en productos y facturas.
 */
@Entity
@Table(name = "withdrawals", schema = "almacen")
public class Withdrawal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Integer id;

    @Column(name = "product_id", nullable = false)
    public Integer productId;

    @Column(nullable = false)
    public BigDecimal quantity;

    @Column(name = "withdrawn_at", nullable = false, updatable = false)
    public OffsetDateTime withdrawnAt;

    @Column(name = "received_by", nullable = false)
    public Integer receivedBy;

    @Column(name = "tractor_id")
    public Integer tractorId;

    @Column(name = "trailer_id")
    public Integer trailerId;

    @Column(name = "escort_vehicle_id")
    public Integer escortVehicleId;

    @Column(columnDefinition = "text")
    public String observations;

    @Column(name = "registered_by", nullable = false)
    public Integer registeredBy;

    @Column(nullable = false, length = 20)
    public String status;

    @Column(name = "cancel_reason", columnDefinition = "text")
    public String cancelReason;

    @Column(name = "cancelled_by")
    public Integer cancelledBy;

    @Column(name = "cancelled_at")
    public OffsetDateTime cancelledAt;

    @Column(name = "updated_at", nullable = false)
    public OffsetDateTime updatedAt;

    // updatedAt sirve de versión del ETag: se trunca a MICROS (Postgres timestamptz), igual
    // que el resto del módulo, para que el valor devuelto por el POST coincida con el releído
    // por un GET en JVMs con reloj de nanosegundos (bug D-12). En el alta arranca igual a
    // withdrawnAt (el retiro recién creado no se editó); la edición/anulación lo bumpean.
    @PrePersist
    public void onCreate() {
        if (withdrawnAt == null) {
            withdrawnAt = DateUtils.nowUtcMicros();
        }
        if (updatedAt == null) {
            updatedAt = withdrawnAt;
        }
        if (status == null) {
            status = "ACTIVE";
        }
    }
}
