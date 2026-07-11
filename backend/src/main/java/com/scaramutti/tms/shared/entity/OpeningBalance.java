package com.scaramutti.tms.shared.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

/**
 * Corte inicial de inventario de un producto ("con cuánto arranca"). Vive en
 * {@code shared/entity/} por convención del proyecto (mismo criterio que
 * Product/Supplier). Registro INMUTABLE: sin PUT/DELETE, una única fila por
 * producto ({@code product_id} UNIQUE, V002) y es el primer movimiento del
 * kardex ({@code APERTURA}, ver VIEW {@code almacen.stock_movements}).
 */
@Entity
@Table(name = "opening_balances", schema = "almacen")
public class OpeningBalance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Integer id;

    @Column(name = "product_id", nullable = false, unique = true)
    public Integer productId;

    @Column(nullable = false)
    public BigDecimal quantity;

    @Column(columnDefinition = "text")
    public String observations;

    @Column(name = "registered_by", nullable = false)
    public Integer registeredBy;

    @Column(name = "registered_at", nullable = false, updatable = false)
    public OffsetDateTime registeredAt;

    // registeredAt viaja en el response del POST tal cual queda en memoria: se
    // trunca a MICROSEGUNDOS porque Postgres (timestamptz) guarda esa precisión
    // (mismo fix que Product.onCreate) — si se dejara en nanos, el valor devuelto
    // por el POST no coincidiría con el que después releería un GET en JVMs con
    // reloj de nanosegundos (Linux), desalineando cualquier comparación entre ambos.
    @PrePersist
    public void onCreate() {
        if (registeredAt == null) {
            registeredAt = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        }
    }
}
