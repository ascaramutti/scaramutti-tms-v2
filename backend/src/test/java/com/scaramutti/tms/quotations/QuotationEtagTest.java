package com.scaramutti.tms.quotations;

import com.scaramutti.tms.shared.entity.Quotation;
import com.scaramutti.tms.shared.exception.ApiException;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit puro del helper {@link QuotationEtag} (sin Quarkus/Mockito). Centraliza el
 * formato del ETag y el check del {@code If-Match} que antes vivian DUPLICADOS en
 * UpdateQuotationService y ChangeQuotationStatusService. Cubre:
 *  - el formato del ETag ({@code "\"" + updatedAt + "\""}), por entity y por OffsetDateTime;
 *  - match → no lanza;
 *  - stale → 412 COM-004;
 *  - null → 412 COM-004.
 */
class QuotationEtagTest {

    private static final OffsetDateTime UPDATED_AT =
        OffsetDateTime.parse("2026-06-01T10:00:30Z");

    private Quotation quotationWith(OffsetDateTime updatedAt) {
        Quotation q = new Quotation();
        q.id = 1L;
        q.updatedAt = updatedAt;
        return q;
    }

    // ---------- of(...) formato --------------------------------------------

    @Test
    void of_entity_wrapsUpdatedAtInQuotes() {
        Quotation q = quotationWith(UPDATED_AT);
        assertEquals("\"" + UPDATED_AT + "\"", QuotationEtag.of(q));
    }

    @Test
    void of_offsetDateTime_matchesEntityOverload() {
        Quotation q = quotationWith(UPDATED_AT);
        // Ambas sobrecargas producen EXACTAMENTE el mismo string (1 sola fuente de verdad).
        assertEquals(QuotationEtag.of(q), QuotationEtag.of(UPDATED_AT));
    }

    // ---------- verify(...) -------------------------------------------------

    @Test
    void verify_matchingIfMatch_doesNotThrow() {
        Quotation q = quotationWith(UPDATED_AT);
        String validEtag = QuotationEtag.of(q);
        assertDoesNotThrow(() -> QuotationEtag.verify(validEtag, q));
    }

    @Test
    void verify_staleIfMatch_throwsCOM004() {
        Quotation q = quotationWith(UPDATED_AT);
        // ETag de una version anterior (otro usuario edito primero).
        String staleEtag = QuotationEtag.of(UPDATED_AT.minusHours(1));

        ApiException ex = assertThrows(ApiException.class,
            () -> QuotationEtag.verify(staleEtag, q));

        assertEquals("COM-004", ex.code());
        assertEquals(412, ex.status());
    }

    @Test
    void verify_nullIfMatch_throwsCOM004() {
        Quotation q = quotationWith(UPDATED_AT);

        ApiException ex = assertThrows(ApiException.class,
            () -> QuotationEtag.verify(null, q));

        assertEquals("COM-004", ex.code());
        assertEquals(412, ex.status());
    }

    @Test
    void verify_unquotedRawValue_throwsCOM004() {
        // Un If-Match sin las comillas envolventes NO coincide con el ETag (formato opaco).
        Quotation q = quotationWith(UPDATED_AT);

        ApiException ex = assertThrows(ApiException.class,
            () -> QuotationEtag.verify(UPDATED_AT.toString(), q));

        assertEquals("COM-004", ex.code());
        assertEquals(412, ex.status());
    }
}
