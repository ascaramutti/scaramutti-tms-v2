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
 * Ítem de una entrada ({@link PurchaseInvoice}). FK plana a la factura
 * ({@code invoiceId}) y al producto ({@code productId}), mismo criterio del resto
 * del dominio. {@code subtotal} = {@code quantity * unitPrice} es derivado (no hay
 * columna), lo calcula el service al ensamblar el response.
 */
@Entity
@Table(name = "purchase_invoice_items", schema = "almacen")
public class PurchaseInvoiceItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Integer id;

    @Column(name = "invoice_id", nullable = false)
    public Integer invoiceId;

    @Column(name = "product_id", nullable = false)
    public Integer productId;

    @Column(nullable = false)
    public BigDecimal quantity;

    @Column(name = "unit_price", nullable = false)
    public BigDecimal unitPrice;

    @Column(name = "created_at", nullable = false, updatable = false)
    public OffsetDateTime createdAt;

    @PrePersist
    public void onCreate() {
        if (createdAt == null) {
            createdAt = DateUtils.nowUtcMicros();
        }
    }
}
