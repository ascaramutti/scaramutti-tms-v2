package com.scaramutti.tms.shared.entity;

import com.scaramutti.tms.shared.util.DateUtils;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * Entrada de almacén: factura de compra con N ítems ({@link PurchaseInvoiceItem}).
 * Vive en {@code shared/entity/} por convención del proyecto (mismo criterio que
 * Product/Supplier/OpeningBalance). Cada ítem ACTIVO suma stock vía la VIEW
 * {@code almacen.stock_movements} (RN-WH1: el stock nunca se persiste, se deriva).
 *
 * <p>La relación con los ítems es por FK plana ({@code PurchaseInvoiceItem.invoiceId}),
 * sin colección JPA ni cascade: mismo criterio "FK plana en todos lados" del resto
 * del dominio (Product.categoryId, OpeningBalance.productId). El service arma factura
 * e ítems en la MISMA transacción, así que la atomicidad la da {@code @Transactional},
 * no un grafo de objetos.
 *
 * <p>El {@code status} se guarda como String (no un enum) para no invertir la
 * dependencia {@code shared → warehouse}: el enum de dominio
 * {@code WarehouseRecordStatus} vive en {@code warehouse/model/} y lo usan las capas
 * REST/service; la BD ya restringe el valor con un CHECK (V002). El alta escribe
 * ACTIVE; la anulación lo pasa a CANCELLED.
 */
@Entity
@Table(name = "purchase_invoices", schema = "almacen")
public class PurchaseInvoice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Integer id;

    @Column(name = "supplier_id", nullable = false)
    public Integer supplierId;

    @Column(name = "invoice_number", nullable = false, length = 50)
    public String invoiceNumber;

    @Column(name = "invoice_date", nullable = false)
    public LocalDate invoiceDate;

    @Column(name = "guide_number", length = 50)
    public String guideNumber;

    @Column(name = "currency_id", nullable = false)
    public Integer currencyId;

    @Column(name = "attachment_path", length = 255)
    public String attachmentPath;

    @Column(columnDefinition = "text")
    public String observations;

    @Column(name = "registered_by", nullable = false)
    public Integer registeredBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    public OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    public OffsetDateTime updatedAt;

    @Column(nullable = false, length = 20)
    public String status;

    @Column(name = "cancel_reason", columnDefinition = "text")
    public String cancelReason;

    @Column(name = "cancelled_by")
    public Integer cancelledBy;

    @Column(name = "cancelled_at")
    public OffsetDateTime cancelledAt;

    // createdAt/updatedAt son la "versión" del ETag: se truncan a MICROSEGUNDOS
    // porque Postgres (timestamptz) guarda esa precisión (mismo fix que Product,
    // bug D-12). Sin truncar, el valor devuelto por el POST no coincidiría con el
    // releído por un GET en JVMs con reloj de nanosegundos (Linux), desalineando el
    // If-Match del PUT/cancel.
    @PrePersist
    public void onCreate() {
        OffsetDateTime now = DateUtils.nowUtcMicros();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
        if (status == null) {
            status = "ACTIVE";
        }
    }

    @PreUpdate
    public void onUpdate() {
        updatedAt = DateUtils.nowUtcMicros();
    }
}
