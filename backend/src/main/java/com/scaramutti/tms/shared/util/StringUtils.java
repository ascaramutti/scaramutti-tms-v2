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

    /**
     * Escapa los metacaracteres de LIKE/ILIKE ({@code \ % _}) para que un
     * input de búsqueda se trate como literal en un patrón {@code ILIKE
     * :param ESCAPE '\'}. El backslash primero (es el char de escape, no debe
     * duplicarse después). Usado por los repos con búsqueda `q` (Client,
     * Quotation, Supplier) — antes duplicado en cada uno, extraído acá al
     * aparecer el 3er caso.
     *
     * @Named aunque ningún mapper lo invoque hoy vía qualifiedByName: sin él,
     * MapStruct lo trata como candidato AUTOMATICO para cualquier campo
     * String→String sin anotar en los mappers que usan `uses = StringUtils.class`
     * (ej. ruc/phone "pasan tal cual" en ClientResourceMapper) — pisaba el
     * passthrough directo con un escape no pedido (bug real, cazado por los
     * tests de create al agregar este método).
     */
    @Named("escapeLikeWildcards")
    public static String escapeLikeWildcards(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
