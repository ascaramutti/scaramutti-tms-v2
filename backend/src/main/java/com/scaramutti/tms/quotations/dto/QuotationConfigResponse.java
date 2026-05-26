package com.scaramutti.tms.quotations.dto;

import java.math.BigDecimal;

/**
 * Constantes runtime del modulo Quotations expuestas al frontend via
 * GET /quotations/config. Cacheable agresivamente (cambian raramente).
 *
 *  - {@code igvPercentage}: IGV nacional vigente. Se persiste como snapshot
 *    en cada {@code quotation_items.igv_percentage} al crear. Origen:
 *    {@code app.quotations.default-igv-percentage}.
 *  - {@code maxRootItems}: maximo de items root por cotizacion (hijos del
 *    Servicio Integral no cuentan). Origen: constante
 *    {@code QuotationValidatorService.MAX_ROOT_ITEMS}.
 *  - {@code defaultValidityDays}: validez por defecto sugerida para precargar
 *    el wizard (el usuario puede editarla). Origen:
 *    {@code app.quotations.default-validity-days}.
 *
 * {@code BigDecimal} en igvPercentage para consistencia con el resto del
 * modulo (todos los porcentajes/montos viajan como BigDecimal — evita
 * lossy double).
 */
public record QuotationConfigResponse(
    BigDecimal igvPercentage,
    int maxRootItems,
    int defaultValidityDays
) {}
