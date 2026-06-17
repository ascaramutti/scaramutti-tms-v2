package com.scaramutti.tms.quotations.dto;

import com.scaramutti.tms.quotations.model.QuotationStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request body de PATCH /quotations/{id}/status (cambio de estado).
 *
 * <p>Validaciones Bean Validation:
 * <ul>
 *   <li>{@code status}: obligatorio. Aunque el tipo es {@link QuotationStatus} (5 valores),
 *       el contrato OpenAPI lo restringe a los destinos de usuario {@code {SENT, ACCEPTED,
 *       REJECTED}} — {@code DRAFT}/{@code EXPIRED} NO son destinos de usuario ({@code EXPIRED}
 *       solo lo produce el job). Si llega {@code DRAFT}/{@code EXPIRED} la maquina de estados
 *       lo rechaza igual con 409 QUO-005.</li>
 *   <li>{@code rejectionReason}: opcional en el schema, pero <b>obligatorio Y exclusivo</b> de
 *       {@code REJECTED} (regla de negocio validada en el service, ADR-007): {@code REJECTED}
 *       sin motivo → 400; cualquier otro destino con motivo → 400. Texto libre, mismo patron
 *       anti-caracteres-de-control que las observaciones ({@code clientNote}/{@code internalNote}).
 *       Se persiste trimmeado. NUNCA se incluye en el PDF.</li>
 * </ul>
 */
public record ChangeQuotationStatusRequest(

    @NotNull
    QuotationStatus status,

    @Size(max = 500)
    @Pattern(regexp = "^[\\P{Cntrl}\\t\\n\\r]*$", message = "rejectionReason no puede contener caracteres de control")
    String rejectionReason
) {}
