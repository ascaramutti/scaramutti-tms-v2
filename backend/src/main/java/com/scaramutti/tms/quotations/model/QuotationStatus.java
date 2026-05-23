package com.scaramutti.tms.quotations.model;

/**
 * Estado de cotizacion. Espejo del CHECK constraint en BD
 * (`chk_quotations_status`).
 *
 *  - DRAFT: cotizacion en edicion, no enviada al cliente.
 *  - SENT: ya enviada al cliente.
 *
 * El estado "vencida" NO se almacena — se computa en runtime:
 * createdAt + validityDays < now().
 */
public enum QuotationStatus {
    DRAFT,
    SENT
}
