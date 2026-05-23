package com.scaramutti.tms.quotations.dto;

import com.scaramutti.tms.quotations.dto.embedded.QuotationCargoTypeSummary;
import com.scaramutti.tms.quotations.dto.embedded.QuotationServiceTypeSummary;

import java.math.BigDecimal;
import java.util.List;

/**
 * Item de cotizacion en el response.
 *
 *  - Para hijos del Integral: parentItemId != null, unitPrice=0, subtotal=0.
 *  - Para items root: parentItemId == null, subtotal = unitPrice * quantity.
 *  - `children`: solo presente en items padre del Integral (estructura jerarquica
 *    para que el frontend renderice el grupo visualmente).
 *  - `serviceType` y `cargoType` son Summaries (subset del contexto de cotizacion)
 *    — no Responses completos del modulo de origen. Aislamiento por
 *    Anti-Corruption Layer del bounded context Quotations.
 */
public record QuotationItemResponse(
    Long id,
    Long parentItemId,
    Integer itemNumber,
    QuotationServiceTypeSummary serviceType,
    QuotationCargoTypeSummary cargoType,
    String observations,
    BigDecimal weightKg,
    BigDecimal lengthMeters,
    BigDecimal widthMeters,
    BigDecimal heightMeters,
    Integer quantity,
    BigDecimal unitPrice,
    BigDecimal internalReferencePrice,
    BigDecimal igvPercentage,
    BigDecimal subtotal,
    BigDecimal insuredAmount,
    QuotationStandbyCostResponse standby,
    List<QuotationItemResponse> children
) {}
