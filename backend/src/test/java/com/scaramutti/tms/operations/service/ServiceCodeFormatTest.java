package com.scaramutti.tms.operations.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit test de la fórmula del código del viaje. El sistema anterior lo armaba con un relleno de
 * ancho FIJO, que a partir del id 10000 recortaba el número y hacía colisionar códigos distintos
 * contra su propia unicidad; acá se fija que el código crece en vez de truncarse.
 */
class ServiceCodeFormatTest {

    @Test
    void formatServiceCode_padsToFourDigits() {
        assertEquals("SRV-0001", CreateServiceService.formatServiceCode(1L));
        assertEquals("SRV-0042", CreateServiceService.formatServiceCode(42L));
        assertEquals("SRV-9999", CreateServiceService.formatServiceCode(9_999L));
    }

    @Test
    void formatServiceCode_growsBeyondFourDigitsWithoutTruncating() {
        assertEquals("SRV-10000", CreateServiceService.formatServiceCode(10_000L));
        assertEquals("SRV-123456", CreateServiceService.formatServiceCode(123_456L));
    }
}
