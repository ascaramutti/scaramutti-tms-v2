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
 *  - `displayLabel`: numeracion de PRESENTACION jerarquica (root "1","2"; hijo del
 *    Integral "1.a","1.b"). Derivada de la posicion + jerarquia, NO persistida (como
 *    expiresAt). El frontend la muestra; el `itemNumber` plano sigue siendo la
 *    numeracion tecnica. La computa el assembler para que el Detalle y el PDF
 *    (ambos backend) usen la misma fuente y nunca diverjan.
 *  - `serviceType` y `cargoType` son Summaries (subset del contexto de cotizacion)
 *    — no Responses completos del modulo de origen. Aislamiento por
 *    Anti-Corruption Layer del bounded context Quotations.
 */
public record QuotationItemResponse(
    Long id,
    Long parentItemId,
    Integer itemNumber,
    String displayLabel,
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
