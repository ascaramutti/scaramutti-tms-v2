package com.scaramutti.tms.quotations.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Item de cotizacion (request body).
 *
 * Validaciones Bean Validation:
 *  - serviceTypeId, quantity: required.
 *  - quantity >= 1.
 *  - unitPrice (cuando se envia): >= 0, max NUMERIC(12,2).
 *  - Numericos opcionales: >= 0, precision adecuada.
 *
 * El {@code igvPercentage} NO se acepta en el request (decision de negocio:
 * IGV nacional uniforme, source of truth backend). El backend lo resuelve
 * desde {@code app.quotations.default-igv-percentage} y lo persiste como
 * snapshot en cada item.
 *
 * Validaciones de negocio (en QuotationValidatorService):
 *  - unitPrice required en items root, debe ser 0/omit en hijos del Integral.
 *  - parentItemNumber siempre 1 si esta presente (referencia al Integral).
 *  - itemNumber autogenerado si se omite; si se envia, validar consistencia.
 *  - insuredAmount solo valido si serviceType es SEG (Seguro de Carga).
 */
public record QuotationItemRequest(

    Integer itemNumber,

    Integer parentItemNumber,

    @NotNull
    Integer serviceTypeId,

    Integer cargoTypeId,

    @Size(max = 2000)
    String observations,

    @DecimalMin(value = "0", inclusive = true)
    @Digits(integer = 8, fraction = 2)
    BigDecimal weightKg,

    @DecimalMin(value = "0", inclusive = true)
    @Digits(integer = 6, fraction = 2)
    BigDecimal lengthMeters,

    @DecimalMin(value = "0", inclusive = true)
    @Digits(integer = 6, fraction = 2)
    BigDecimal widthMeters,

    @DecimalMin(value = "0", inclusive = true)
    @Digits(integer = 6, fraction = 2)
    BigDecimal heightMeters,

    @NotNull
    @Min(1)
    Integer quantity,

    @DecimalMin(value = "0", inclusive = true)
    @Digits(integer = 10, fraction = 2)
    BigDecimal unitPrice,

    @DecimalMin(value = "0", inclusive = true)
    @Digits(integer = 10, fraction = 2)
    BigDecimal internalReferencePrice,

    // igvPercentage YA NO se acepta en el request. El backend lo resuelve
    // desde config (app.quotations.default-igv-percentage). El IGV en Peru
    // es uniforme nacional (18%) — frontend no debe enviarlo. El % usado al
    // cotizar se persiste como snapshot en `quotation_items.igv_percentage`
    // (las cotizaciones viejas mantienen su % aunque el config cambie).

    @DecimalMin(value = "0", inclusive = true)
    @Digits(integer = 12, fraction = 2)
    BigDecimal insuredAmount,

    @Valid
    QuotationStandbyCostRequest standby
) {}
