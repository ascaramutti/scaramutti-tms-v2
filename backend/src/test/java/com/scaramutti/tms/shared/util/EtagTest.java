package com.scaramutti.tms.shared.util;

import com.scaramutti.tms.shared.exception.ApiException;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit puro del helper compartido {@link Etag} (fuente de verdad del formato ETag /
 * optimistic locking del proyecto). Los helpers tipados por entity ({@code QuotationEtag},
 * {@code WarehouseProductEtag}) delegan acá, así que sus tests validan la equivalencia;
 * este cubre el helper directamente:
 *  - formato ({@code "\"" + updatedAt + "\""});
 *  - match → no lanza; stale → 412 COM-004; null → 412 COM-004; sin comillas → 412;
 *  - regresión D-12: un valor truncado a MICROS no arrastra residuo de nanos.
 */
class EtagTest {

    private static final OffsetDateTime VERSION =
        OffsetDateTime.parse("2026-06-01T10:00:30.123456Z");

    @Test
    void of_wrapsVersionInQuotes() {
        assertEquals("\"" + VERSION + "\"", Etag.of(VERSION));
    }

    @Test
    void of_valueWithNanos_truncatedToMicros_dropsNanoResidue() {
        OffsetDateTime withNanos = OffsetDateTime.parse("2026-06-01T10:00:30.123456789Z");
        assertEquals("\"2026-06-01T10:00:30.123456Z\"", Etag.of(withNanos.truncatedTo(ChronoUnit.MICROS)));
    }

    @Test
    void verify_matchingIfMatch_doesNotThrow() {
        assertDoesNotThrow(() -> Etag.verify(Etag.of(VERSION), VERSION));
    }

    @Test
    void verify_staleIfMatch_throwsCOM004() {
        ApiException ex = assertThrows(ApiException.class,
            () -> Etag.verify(Etag.of(VERSION.minusHours(1)), VERSION));

        assertEquals("COM-004", ex.code());
        assertEquals(412, ex.status());
    }

    @Test
    void verify_nullIfMatch_throwsCOM004() {
        ApiException ex = assertThrows(ApiException.class, () -> Etag.verify(null, VERSION));

        assertEquals("COM-004", ex.code());
        assertEquals(412, ex.status());
    }

    @Test
    void verify_unquotedRawValue_throwsCOM004() {
        ApiException ex = assertThrows(ApiException.class,
            () -> Etag.verify(VERSION.toString(), VERSION));

        assertEquals("COM-004", ex.code());
    }
}
