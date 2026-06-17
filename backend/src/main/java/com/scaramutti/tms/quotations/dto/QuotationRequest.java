package com.scaramutti.tms.quotations.dto;

import com.scaramutti.tms.quotations.model.QuotationType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;

/**
 * Request body de POST /quotations.
 *
 * Validaciones Bean Validation:
 *  - quotationType, clientId, currencyId, validityDays: required.
 *  - validityDays entre 1 y 365.
 *  - items: minSize 1.
 *  - origin/destination: maxLength 255.
 *  - clientNote/internalNote: maxLength 500, opcionales (observaciones a nivel cotización).
 *  - contactName: maxLength 200.
 *
 * Validaciones de negocio (QuotationValidatorService):
 *  - Max 5 items root.
 *  - Si quotationType=TRANSPORTE: origin y destination required.
 *  - Si quotationType=ALQUILER: ignora origin/destination.
 *  - Servicio Integral: si hay item con serviceType.code='INT':
 *    - debe ser itemNumber=1
 *    - debe tener >=2 hijos con parentItemNumber=1
 *    - hijos deben incluir >=1 TRANSPORTE (kind=S) + >=1 COMPLEMENTARIO (kind=C)
 *    - solo 1 Integral por cotizacion
 *  - Anti-duplicado: mismo clientId + mismo set de serviceTypeIds + mismo
 *    created_by en los ultimos 30s → 409 QUO-002.
 */
public record QuotationRequest(

    @NotNull
    QuotationType quotationType,

    @NotNull
    Integer clientId,

    @NotBlank
    @Size(max = 200)
    String contactName,

    @Pattern(regexp = "^\\d{9}$", message = "contactPhone debe tener exactamente 9 digitos numericos")
    String contactPhone,

    @NotNull
    Integer currencyId,

    Integer paymentTermId,

    LocalDate tentativeServiceDate,

    @NotNull
    @Min(1)
    @Max(365)
    Integer validityDays,

    @Size(max = 255)
    String origin,

    @Size(max = 255)
    String destination,

    @Size(max = 500)
    @Pattern(regexp = "^[\\P{Cntrl}\\t\\n\\r]*$", message = "clientNote no puede contener caracteres de control")
    String clientNote,

    @Size(max = 500)
    @Pattern(regexp = "^[\\P{Cntrl}\\t\\n\\r]*$", message = "internalNote no puede contener caracteres de control")
    String internalNote,

    @NotEmpty
    @Valid
    List<QuotationItemRequest> items
) {}
