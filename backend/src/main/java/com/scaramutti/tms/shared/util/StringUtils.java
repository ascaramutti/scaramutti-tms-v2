package com.scaramutti.tms.shared.util;

import org.mapstruct.Named;

/**
 * Utilidades de manejo de strings compartidas entre mappers y validators.
 * Centraliza patrones comunes como "trim defensivo + normalizar vacío a null"
 * que aparecen en múltiples DTOs del proyecto.
 *
 * Los métodos están anotados con `@Named` para que cualquier MapStruct mapper
 * pueda referenciarlos via `@Mapper(uses = StringUtils.class)` +
 * `@Mapping(qualifiedByName = "...")`, sin necesidad de declarar wrappers locales.
 */
public final class StringUtils {

    private StringUtils() {
        // utility class
    }

    /**
     * Aplica `trim()` y devuelve null si el resultado queda vacío.
     * - null            → null
     * - "" / "   "      → null
     * - "  Acme  "      → "Acme"
     *
     * Útil en mappers Request → Command donde queremos que "vacío" y "ausente"
     * sean equivalentes (campos nullable del contrato OpenAPI).
     */
    @Named("trimToNull")
    public static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
