package com.scaramutti.tms.quotations.service.cmd;

import com.scaramutti.tms.quotations.model.QuotationStatus;

/**
 * Command interno del {@code ChangeQuotationStatusService} (PATCH /quotations/{id}/status).
 *
 * <p>Mapeado por {@code QuotationResourceMapper} desde {@code ChangeQuotationStatusRequest}.
 * El {@code rejectionReason} llega ya trimmeado ("" → null) — la regla de obligatoriedad/
 * exclusividad la valida el service (ADR-007), no el mapper.
 */
public record ChangeQuotationStatusCommand(
    QuotationStatus status,
    String rejectionReason
) {}
