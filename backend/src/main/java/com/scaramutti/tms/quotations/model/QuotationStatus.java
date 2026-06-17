package com.scaramutti.tms.quotations.model;

/**
 * Estado de cotizacion (eje unico del ciclo de vida). Espejo del CHECK constraint
 * en BD (`chk_quotations_status`) y del enum del contrato OpenAPI.
 *
 * <p>Estados:
 * <ul>
 *   <li>{@code DRAFT}: cotizacion en edicion, no enviada al cliente.</li>
 *   <li>{@code SENT}: ya enviada al cliente. Unico estado que puede vencer.</li>
 *   <li>{@code ACCEPTED}: el cliente la acepto. Terminal.</li>
 *   <li>{@code REJECTED}: el cliente la rechazo (lleva motivo). Terminal.</li>
 *   <li>{@code EXPIRED}: vencio sin respuesta (la produce el job). Terminal.</li>
 * </ul>
 *
 * <p>Maquina de estados (ADR-004): {@code DRAFT → SENT → {ACCEPTED|REJECTED|EXPIRED}}.
 * Los estados terminales son inmutables (no se puede transicionar fuera de ellos).
 * La fuente de verdad de que transicion es legal vive en
 * {@code QuotationStatusMachine} (un solo mapa), NO en este enum.
 *
 * <p>El flag {@code isExpired} expuesto en las respuestas se DERIVA del estado persistido
 * (ADR-005): {@code true} si y solo si {@code status == EXPIRED} (lo mantiene el
 * {@code QuotationExpiryJob}). Ya NO se computa por fechas en el read-path.
 */
public enum QuotationStatus {
    DRAFT,
    SENT,
    ACCEPTED,
    REJECTED,
    EXPIRED;

    /**
     * {@code true} si el estado es terminal (inmutable): {@code ACCEPTED},
     * {@code REJECTED} o {@code EXPIRED}. Una cotizacion terminal no admite mas
     * transiciones ni edicion (PUT → QUO-006).
     */
    public boolean isTerminal() {
        return this == ACCEPTED || this == REJECTED || this == EXPIRED;
    }
}
