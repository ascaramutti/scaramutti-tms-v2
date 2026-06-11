package com.scaramutti.tms.quotations.service.cmd;

import com.scaramutti.tms.quotations.model.QuotationType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Command interno del CreateQuotationService.
 *
 * Mapeado por ClientResourceMapper desde QuotationRequest. Diferencias:
 *  - origin/destination ya trimmed (no normalizamos uppercase — son lugares
 *    geograficos, no nombres comerciales).
 *  - contactName trimmed.
 *  - observations trimmed por item.
 *  - Los items vienen como records inmutables — el service no muta el command.
 */
public record SaveQuotationCommand(
    QuotationType quotationType,
    Integer clientId,
    String contactName,
    String contactPhone,
    Integer currencyId,
    Integer paymentTermId,
    LocalDate tentativeServiceDate,
    Integer validityDays,
    String origin,
    String destination,
    List<Item> items
) {

    /**
     * Item dentro del Command. Mantengo los campos numericos como BigDecimal
     * para preservar precision exacta de los calculos (no convertir a double).
     */
    public record Item(
        Integer itemNumber,
        Integer parentItemNumber,
        Integer serviceTypeId,
        Integer cargoTypeId,
        String observations,
        BigDecimal weightKg,
        BigDecimal lengthMeters,
        BigDecimal widthMeters,
        BigDecimal heightMeters,
        Integer quantity,
        BigDecimal unitPrice,
        BigDecimal internalReferencePrice,
        BigDecimal insuredAmount,
        Standby standby
    ) {}

    public record Standby(
        BigDecimal pricePerDay,
        Boolean includesIgv
    ) {}
}
