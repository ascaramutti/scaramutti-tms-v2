package com.scaramutti.tms.warehouse.product;

import com.scaramutti.tms.shared.entity.Product;
import com.scaramutti.tms.shared.exception.ApiException;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit puro del helper {@link WarehouseProductEtag} (sin Quarkus/Mockito). Calco
 * de {@code QuotationEtagTest}. Cubre:
 *  - el formato del ETag ({@code "\"" + updatedAt + "\""}), por entity y por OffsetDateTime;
 *  - match → no lanza; stale → 412 COM-004; null → 412 COM-004;
 *  - AISLA la causa raiz del bug D-12: un {@code OffsetDateTime} con nanos, truncado
 *    a MICROS (como hace {@code Product.onCreate/onUpdate}), produce el string
 *    ESPERADO sin sufijo de nanos residual — sin necesitar DB ni JVM de Linux.
 */
class WarehouseProductEtagTest {

    private static final OffsetDateTime UPDATED_AT =
        OffsetDateTime.parse("2026-06-01T10:00:30.123456Z");

    private Product productWith(OffsetDateTime updatedAt) {
        Product p = new Product();
        p.id = 1;
        p.updatedAt = updatedAt;
        return p;
    }

    // ---------- of(...) formato --------------------------------------------

    @Test
    void of_entity_wrapsUpdatedAtInQuotes() {
        Product p = productWith(UPDATED_AT);
        assertEquals("\"" + UPDATED_AT + "\"", WarehouseProductEtag.of(p));
    }

    @Test
    void of_offsetDateTime_matchesEntityOverload() {
        Product p = productWith(UPDATED_AT);
        // Ambas sobrecargas producen EXACTAMENTE el mismo string (1 sola fuente de verdad).
        assertEquals(WarehouseProductEtag.of(p), WarehouseProductEtag.of(UPDATED_AT));
    }

    // ---------- D-12: truncacion a MICROS aisla la causa raiz ------------------

    @Test
    void of_offsetDateTimeWithNanos_truncatedToMicros_dropsNanoResidue() {
        // Un valor "en memoria" con nanos (como daria OffsetDateTime.now() sin truncar
        // en una JVM de reloj de nanos, Linux) truncado a MICROS (el fix de Product.onCreate)
        // coincide EXACTAMENTE con el mismo valor ya truncado — nunca arrastra el residuo
        // de nanos que rompia el ETag POST vs GET (bug D-12).
        OffsetDateTime withNanos = OffsetDateTime.parse("2026-06-01T10:00:30.123456789Z");
        OffsetDateTime truncated = withNanos.truncatedTo(ChronoUnit.MICROS);

        assertEquals("\"2026-06-01T10:00:30.123456Z\"", WarehouseProductEtag.of(truncated));
        assertEquals(WarehouseProductEtag.of(truncated), WarehouseProductEtag.of(withNanos.truncatedTo(ChronoUnit.MICROS)));
    }

    // ---------- verify(...) -------------------------------------------------

    @Test
    void verify_matchingIfMatch_doesNotThrow() {
        Product p = productWith(UPDATED_AT);
        String validEtag = WarehouseProductEtag.of(p);
        assertDoesNotThrow(() -> WarehouseProductEtag.verify(validEtag, p));
    }

    @Test
    void verify_staleIfMatch_throwsCOM004() {
        Product p = productWith(UPDATED_AT);
        // ETag de una version anterior (otro usuario edito primero).
        String staleEtag = WarehouseProductEtag.of(UPDATED_AT.minusHours(1));

        ApiException ex = assertThrows(ApiException.class,
            () -> WarehouseProductEtag.verify(staleEtag, p));

        assertEquals("COM-004", ex.code());
        assertEquals(412, ex.status());
    }

    @Test
    void verify_nullIfMatch_throwsCOM004() {
        Product p = productWith(UPDATED_AT);

        ApiException ex = assertThrows(ApiException.class,
            () -> WarehouseProductEtag.verify(null, p));

        assertEquals("COM-004", ex.code());
        assertEquals(412, ex.status());
    }

    @Test
    void verify_unquotedRawValue_throwsCOM004() {
        // Un If-Match sin las comillas envolventes NO coincide con el ETag (formato opaco).
        Product p = productWith(UPDATED_AT);

        ApiException ex = assertThrows(ApiException.class,
            () -> WarehouseProductEtag.verify(UPDATED_AT.toString(), p));

        assertEquals("COM-004", ex.code());
        assertEquals(412, ex.status());
    }
}
