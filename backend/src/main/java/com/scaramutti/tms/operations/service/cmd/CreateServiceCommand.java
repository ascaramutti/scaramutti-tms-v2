package com.scaramutti.tms.operations.service.cmd;

import com.scaramutti.tms.operations.model.TripScope;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Alta de un servicio de transporte, ya normalizada desde el request. */
public record CreateServiceCommand(
    Integer clientId,
    TripScope tripScope,
    LocalDate tentativeDate,
    String origin,
    String destination,
    Integer cargoTypeId,
    BigDecimal weightKg,
    BigDecimal lengthM,
    BigDecimal widthM,
    BigDecimal heightM,
    BigDecimal price,
    Integer currencyId,
    String observations
) {}
