package com.scaramutti.tms.quotations.service;

import com.scaramutti.tms.shared.repository.QuotationRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit del code generator. Mockea el repositorio (advisory lock + MAX).
 *
 * Casos cubiertos:
 *  - Primera cotizacion del anio (MAX=0 → "YYYY-00001").
 *  - N-esima cotizacion del anio (MAX=N → "YYYY-NN+1" con padding 5).
 *  - Boundary: MAX=99999 → "YYYY-100000" (6 digitos, valido en DB porque
 *    la columna es VARCHAR(15) — soporta YYYY-NNNNNN).
 *  - Orden estricto: advisory lock ANTES de leer MAX (criterio del lineamiento).
 */
@ExtendWith(MockitoExtension.class)
class QuotationCodeGeneratorServiceTest {

    @Mock QuotationRepository quotationRepository;
    @InjectMocks QuotationCodeGeneratorService codeGenerator;

    @Test
    void nextCodeForYear_whenNoQuotationsExist_returnsFirstNumber() {
        when(quotationRepository.maxCodeNumberForYear(2026)).thenReturn(0);

        String code = codeGenerator.nextCodeForYear(2026);

        assertEquals("2026-00001", code);
    }

    @Test
    void nextCodeForYear_returnsNextSequentialNumber() {
        when(quotationRepository.maxCodeNumberForYear(2026)).thenReturn(42);

        String code = codeGenerator.nextCodeForYear(2026);

        assertEquals("2026-00043", code);
    }

    @Test
    void nextCodeForYear_padsTo5Digits() {
        when(quotationRepository.maxCodeNumberForYear(2026)).thenReturn(9);

        String code = codeGenerator.nextCodeForYear(2026);

        assertEquals("2026-00010", code);
    }

    @Test
    void nextCodeForYear_yearChangeResetsCounter_implicitly() {
        // 2026 termina en 847, 2027 arranca en 1 (porque MAX para 2027 es 0).
        when(quotationRepository.maxCodeNumberForYear(2027)).thenReturn(0);

        String code = codeGenerator.nextCodeForYear(2027);

        assertEquals("2027-00001", code);
    }

    @Test
    void nextCodeForYear_acquiresAdvisoryLockBeforeReadingMax() {
        // Lock primero → previene race condition. Lock-in del orden.
        when(quotationRepository.maxCodeNumberForYear(2026)).thenReturn(0);

        codeGenerator.nextCodeForYear(2026);

        var order = inOrder(quotationRepository);
        order.verify(quotationRepository).acquireYearLock(2026);
        order.verify(quotationRepository).maxCodeNumberForYear(2026);
    }

    @Test
    void nextCodeForYear_above99999_extendsTo6Digits() {
        // Edge case: si en el futuro un anio supera 99999, format mantiene
        // padding pero permite 6+ digitos. La columna VARCHAR(15) lo soporta.
        when(quotationRepository.maxCodeNumberForYear(2026)).thenReturn(99999);

        String code = codeGenerator.nextCodeForYear(2026);

        assertEquals("2026-100000", code);
    }
}
