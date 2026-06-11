package com.scaramutti.tms.quotations.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * Stand-by cost asociado a un item (relacion 1:1). Opcional.
 *  - pricePerDay: required, >= 0, max NUMERIC(12,2).
 *  - includesIgv: default false.
 */
public record QuotationStandbyCostRequest(

    @NotNull
    @DecimalMin(value = "0", inclusive = true)
    @Digits(integer = 10, fraction = 2)
    BigDecimal pricePerDay,

    Boolean includesIgv
) {}
