package com.scaramutti.tms.quotations.dto;

import java.math.BigDecimal;

public record QuotationStandbyCostResponse(
    Long id,
    BigDecimal pricePerDay,
    Boolean includesIgv
) {}
