package com.scaramutti.tms.quotations.pdf;

import com.scaramutti.tms.quotations.dto.embedded.QuotationConditionSummary;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests puros (sin Quarkus) del armado de las CONDICIONES GENERALES del PDF.
 * {@code buildTerms} no usa estado inyectado, asi que se instancia el servicio directo y se
 * ejercitan las ramas: con/sin condiciones y con cabecera de cuentas presente / nula / en blanco.
 * El marcador {@code [[BANK_ACCOUNTS]]} (que el template reemplaza por la tabla de cuentas) va
 * SIEMPRE al final; la cabecera, si esta presente, justo antes (ultima viñeta).
 */
class QuotationPdfBuildTermsTest {

    private final QuotationPdfService service = new QuotationPdfService();
    private static final String MARKER = QuotationPdfView.BANK_ACCOUNTS_MARKER;

    private QuotationConditionSummary cond(String text) {
        return new QuotationConditionSummary(1, text, 1, true);
    }

    @Test
    void conditionsThenIntroThenMarker() {
        List<String> terms = service.buildTerms(
            List.of(cond("Clausula A"), cond("Clausula B")), "Pague en estas cuentas:");

        assertEquals(List.of("Clausula A", "Clausula B", "Pague en estas cuentas:", MARKER), terms);
    }

    @Test
    void blankIntro_omitsIntroBullet_keepsMarker() {
        List<String> terms = service.buildTerms(List.of(cond("Clausula A")), "   ");

        // Sin viñeta vacia para la cabecera; la tabla (marcador) igual sale.
        assertEquals(List.of("Clausula A", MARKER), terms);
    }

    @Test
    void nullIntro_omitsIntroBullet_keepsMarker() {
        List<String> terms = service.buildTerms(List.of(cond("Clausula A")), null);

        assertEquals(List.of("Clausula A", MARKER), terms);
    }

    @Test
    void nullConditions_withIntro_introThenMarker() {
        List<String> terms = service.buildTerms(null, "Pague en estas cuentas:");

        assertEquals(List.of("Pague en estas cuentas:", MARKER), terms);
    }

    @Test
    void noConditions_noIntro_onlyMarker() {
        List<String> terms = service.buildTerms(List.of(), "");

        assertEquals(List.of(MARKER), terms);
    }
}
