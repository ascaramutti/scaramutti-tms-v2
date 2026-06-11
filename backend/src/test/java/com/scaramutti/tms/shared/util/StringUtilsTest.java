package com.scaramutti.tms.shared.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class StringUtilsTest {

    @Test
    void trimToNull_withNull_returnsNull() {
        assertNull(StringUtils.trimToNull(null));
    }

    @Test
    void trimToNull_withEmptyString_returnsNull() {
        assertNull(StringUtils.trimToNull(""));
    }

    @Test
    void trimToNull_withOnlyWhitespace_returnsNull() {
        assertNull(StringUtils.trimToNull("   "));
        assertNull(StringUtils.trimToNull("\t\n  "));
    }

    @Test
    void trimToNull_withSurroundingWhitespace_returnsTrimmed() {
        assertEquals("Acme", StringUtils.trimToNull("  Acme  "));
        assertEquals("Juan Pérez", StringUtils.trimToNull("\tJuan Pérez\n"));
    }

    @Test
    void trimToNull_withCleanString_returnsAsIs() {
        assertEquals("Acme Corp SAC", StringUtils.trimToNull("Acme Corp SAC"));
    }

    @Test
    void trimToNull_doesNotChangeCase() {
        // trimToNull preserva mayusculas/minusculas. El uppercase lo hace el caller
        // (ej. ClientResourceMapper.trimUpperOrNull) si el campo lo requiere.
        assertEquals("acme", StringUtils.trimToNull("  acme  "));
        assertEquals("ACME", StringUtils.trimToNull("  ACME  "));
    }
}
