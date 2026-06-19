package com.scaramutti.tms.quotations.dto;

import com.scaramutti.tms.auth.dto.UserResponse;
import com.scaramutti.tms.quotations.dto.embedded.QuotationClientSummary;
import com.scaramutti.tms.quotations.dto.embedded.QuotationConditionSummary;
import com.scaramutti.tms.quotations.dto.embedded.QuotationCurrencySummary;
import com.scaramutti.tms.quotations.dto.embedded.QuotationPaymentTermSummary;
import com.scaramutti.tms.quotations.model.QuotationStatus;
import com.scaramutti.tms.quotations.model.QuotationType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Response body completo de POST /quotations (y GET por id en el futuro).
 *
 *  - Totales `totalSubtotal/totalIgv/totalAmount` calculados por
 *    QuotationCalculatorService.
 *  - `expiresAt` computado: createdAt + validityDays.
 *  - `isExpired` lo decide el caller (assembler recibe el bool).
 *  - `updatedAt` sirve como source del ETag header — optimistic locking.
 *  - `items` lista jerarquica: items padre del Integral exponen `children`,
 *    items root estandar tienen `children = null` o lista vacia.
 *  - `contactName` y `contactPhone` son SNAPSHOTS del contacto al momento
 *    de cotizar (NO se sincronizan si el cliente master cambia). El
 *    {@code client} embebido (Summary) NO incluye estos campos: estos viven
 *    en el header de la cotizacion como dato historico.
 *  - `client`, `currency`, `paymentTerm` son Summaries especificos del
 *    contexto de cotizacion (subset minimo). NO son los Response completos
 *    de los modulos de origen — aislado por Anti-Corruption Layer del
 *    bounded context Quotations.
 *  - `createdBy` y `updatedBy` se exponen como UserResponse (sin embeber
 *    Summary — el dato del usuario es el mismo en cualquier contexto).
 *  - `rejectionReason` (nullable): motivo del rechazo, presente solo si
 *    `status=REJECTED`. INTERNO — se devuelve a los 4 roles pero NUNCA entra
 *    al PDF ni a salidas hacia el cliente (ADR-007, hereda la regla de
 *    `internalNote`).
 */
public record QuotationResponse(
    Long id,
    String code,
    QuotationType quotationType,
    QuotationStatus status,
    QuotationClientSummary client,
    String contactName,
    String contactPhone,
    QuotationCurrencySummary currency,
    QuotationPaymentTermSummary paymentTerm,
    LocalDate tentativeServiceDate,
    Integer validityDays,
    OffsetDateTime expiresAt,
    Boolean isExpired,
    String origin,
    String destination,
    String clientNote,
    String internalNote,
    String rejectionReason,
    BigDecimal totalSubtotal,
    BigDecimal totalIgv,
    BigDecimal totalAmount,
    List<QuotationItemResponse> items,
    List<QuotationConditionSummary> conditions,
    UserResponse createdBy,
    UserResponse updatedBy,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt
) {}
