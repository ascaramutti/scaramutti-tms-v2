package com.scaramutti.tms.operations.service.cmd;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * Edicion de un servicio de transporte, ya normalizada desde el request. Sin cliente, ambito ni
 * tipo de carga: son inmutables despues del alta.
 *
 * <p>{@code startDateTime} y {@code endDateTime} en null significan "sin cambio", no "borrar".
 */
public record UpdateServiceCommand(
    long serviceId,
    String ifMatch,
    LocalDate tentativeDate,
    String origin,
    String destination,
    BigDecimal weightKg,
    BigDecimal lengthM,
    BigDecimal widthM,
    BigDecimal heightM,
    BigDecimal price,
    Integer currencyId,
    String observations,
    OffsetDateTime startDateTime,
    OffsetDateTime endDateTime,
    String justification
) {}
