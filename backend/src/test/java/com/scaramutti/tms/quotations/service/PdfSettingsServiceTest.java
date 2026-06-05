package com.scaramutti.tms.quotations.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scaramutti.tms.quotations.dto.CompanyPdfSettings;
import com.scaramutti.tms.shared.repository.SystemSettingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * Unit tests del parseo de system_settings para el PDF. Mockea el repository y usa
 * un ObjectMapper real para ejercer las ramas de {@code parseJsonList}: JSON valido,
 * JSON corrupto que degrada a lista vacia sin tumbar el PDF, y keys ausentes.
 *
 * <p>El happy path E2E con el seed real (seed manual de system_settings) se cubre indirectamente
 * en QuotationPdfServiceTest y en el endpoint. Los campos {@code repository}/{@code objectMapper}
 * son package-private, por eso se asignan directo sin reflection ni contexto Quarkus.
 */
@ExtendWith(MockitoExtension.class)
class PdfSettingsServiceTest {

    @Mock SystemSettingRepository repository;

    private PdfSettingsService service;

    @BeforeEach
    void setup() {
        service = new PdfSettingsService();
        service.repository = repository;
        service.objectMapper = new ObjectMapper();
    }

    @Test
    void forPdf_withValidJson_parsesAllFields() {
        when(repository.findAllAsMap()).thenReturn(Map.of(
            "company.legal_name", "TRANSPORTES SCARAMUTTI S.A.C.",
            "company.address", "Av. Siempre Viva 123",
            "company.phone", "Ofic.: 5927868",
            "company.email", "ventas@scaramutti.pe",
            "quotation.pdf_terms", "[\"Termino uno\",\"Termino dos\"]",
            "quotation.pdf_bank_accounts",
                "[{\"bank\":\"BCP - Soles\",\"account\":\"123-456\",\"cci\":\"002-123\"}]"
        ));

        CompanyPdfSettings settings = service.forPdf();

        assertEquals("TRANSPORTES SCARAMUTTI S.A.C.", settings.legalName());
        assertEquals("Av. Siempre Viva 123", settings.address());
        assertEquals("Ofic.: 5927868", settings.phone());
        assertEquals("ventas@scaramutti.pe", settings.email());
        assertEquals(2, settings.terms().size());
        assertEquals("Termino uno", settings.terms().get(0));
        assertEquals(1, settings.bankAccounts().size());
        assertEquals("BCP - Soles", settings.bankAccounts().get(0).bank());
        assertEquals("002-123", settings.bankAccounts().get(0).cci());
    }

    @Test
    void forPdf_withCorruptTermsJson_degradesToEmptyListWithoutFailing() {
        when(repository.findAllAsMap()).thenReturn(Map.of(
            "company.legal_name", "ACME",
            "quotation.pdf_terms", "esto no es json",
            "quotation.pdf_bank_accounts", "[]"
        ));

        CompanyPdfSettings settings = service.forPdf();

        // El setting corrupto NO tumba el PDF: terms degrada a vacio, el resto sigue OK.
        assertTrue(settings.terms().isEmpty());
        assertTrue(settings.bankAccounts().isEmpty());
        assertEquals("ACME", settings.legalName());
    }

    @Test
    void forPdf_withCorruptBankJson_degradesToEmptyList() {
        when(repository.findAllAsMap()).thenReturn(Map.of(
            "quotation.pdf_terms", "[\"ok\"]",
            "quotation.pdf_bank_accounts", "{no cierra"
        ));

        CompanyPdfSettings settings = service.forPdf();

        assertEquals(1, settings.terms().size());
        assertTrue(settings.bankAccounts().isEmpty());
    }

    @Test
    void forPdf_withMissingKeys_returnsEmptyDefaults() {
        when(repository.findAllAsMap()).thenReturn(Map.of());

        CompanyPdfSettings settings = service.forPdf();

        assertEquals("", settings.legalName());
        assertEquals("", settings.address());
        assertEquals("", settings.phone());
        assertEquals("", settings.email());
        assertTrue(settings.terms().isEmpty());
        assertTrue(settings.bankAccounts().isEmpty());
    }
}
