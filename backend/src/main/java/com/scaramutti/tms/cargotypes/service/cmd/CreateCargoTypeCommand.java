package com.scaramutti.tms.cargotypes.service.cmd;

import java.math.BigDecimal;

/**
 * Command interno del service. Los campos llegan ya normalizados por el ResourceMapper:
 *  - name: trimmed + uppercase
 *  - description: trimmed, "" → null
 *  - standardWeight: nunca null (Bean Validation @NotNull lo garantiza)
 *  - standardLength/Width/Height: pueden ser null
 *
 * Los numericos vienen ya validados por Bean Validation (@DecimalMin + @Digits) — el
 * service no re-valida rangos.
 */
public record CreateCargoTypeCommand(
    String name,
    String description,
    BigDecimal standardWeight,
    BigDecimal standardLength,
    BigDecimal standardWidth,
    BigDecimal standardHeight
) {}
