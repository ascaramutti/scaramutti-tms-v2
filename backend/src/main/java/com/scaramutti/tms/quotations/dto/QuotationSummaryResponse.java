package com.scaramutti.tms.quotations.dto;

import com.scaramutti.tms.auth.dto.UserResponse;
import com.scaramutti.tms.quotations.dto.embedded.QuotationClientSummary;
import com.scaramutti.tms.quotations.model.QuotationStatus;
import com.scaramutti.tms.quotations.model.QuotationType;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Fila del listado de cotizaciones (GET /quotations). Version reducida del
 * {@code QuotationResponse} del detalle — sin items ni standby.
 *
 *  - {@code client}: subset {id,name,ruc} del bounded context (consistente con
 *    el detalle), datos live del master via JOIN.
 *  - {@code totalAmount}/{@code itemsCount}: computados en runtime desde los items
 *    (NO persistidos). totalAmount usa {@code QuotationCalculatorService.calculateFromEntities}
 *    (misma funcion que el detalle → totales identicos). itemsCount cuenta solo
 *    items root (los hijos del Integral no cuentan).
 *  - {@code createdBy}: UserResponse completo (consistente con el detalle).
 *  - {@code isExpired}: derivado del estado persistido (true SII {@code status == EXPIRED}, ADR-005).
 */
public record QuotationSummaryResponse(
    Long id,
    String code,
    QuotationType quotationType,
    QuotationStatus status,
    QuotationClientSummary client,
    String currencyCode,
    BigDecimal totalAmount,
    Integer itemsCount,
    Integer validityDays,
    OffsetDateTime expiresAt,
    Boolean isExpired,
    String origin,
    String destination,
    OffsetDateTime createdAt,
    UserResponse createdBy
) {}
