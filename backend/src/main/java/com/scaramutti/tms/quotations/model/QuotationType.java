package com.scaramutti.tms.quotations.model;

/**
 * Tipo de cotizacion. Espejo del CHECK constraint en BD
 * (`chk_quotations_type`).
 *
 *  - TRANSPORTE: requiere origin/destination. Servicios con kind SERVICIO
 *    o COMPLEMENTARIO o INTEGRAL.
 *  - ALQUILER: sin ruta. Solo kind ALQUILER.
 *
 * En `model/` por convencion del proyecto (ver memoria
 * `feedback_domain_enums_in_model_package`).
 */
public enum QuotationType {
    TRANSPORTE,
    ALQUILER
}
