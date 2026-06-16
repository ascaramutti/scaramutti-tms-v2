package com.scaramutti.tms.quotations.pdf;

import com.scaramutti.tms.quotations.dto.QuotationResponse;
import com.scaramutti.tms.quotations.service.PdfSettingsService;
import com.scaramutti.tms.shared.exception.ApiException;
import io.quarkus.qute.Template;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

/**
 * Unit test del manejo de error de {@code generate()}: si el armado/render del PDF
 * falla, NO debe propagar una excepcion cruda (500 fuera del contrato Problem, con
 * riesgo de filtrar internals) sino una {@link ApiException} COM-500. Se aisla con
 * un settingsService mock que tira; los campos del service son package-private.
 */
@ExtendWith(MockitoExtension.class)
class QuotationPdfServiceErrorTest {

    @Mock PdfSettingsService settingsService;
    @Mock Template template;

    @Test
    void generate_whenAssemblyFails_throwsApiExceptionCom500() {
        QuotationPdfService service = new QuotationPdfService();
        service.settingsService = settingsService;
        service.template = template;
        when(settingsService.forPdf()).thenThrow(new RuntimeException("boom"));

        ApiException ex = assertThrows(ApiException.class,
            () -> service.generate(responseWithCode("2026-00001")));

        assertEquals("COM-500", ex.code());
        assertEquals(500, ex.status());
    }

    /** Solo necesita {@code code()} != null para el mensaje de error; forPdf() tira antes de tocar el resto. */
    private QuotationResponse responseWithCode(String code) {
        return new QuotationResponse(
            1L, code, null, null, null, null, null, null, null, null, null,
            null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }
}
