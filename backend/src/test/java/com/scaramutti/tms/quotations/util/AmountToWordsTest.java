package com.scaramutti.tms.quotations.util;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AmountToWordsTest {

    @Test
    void basic() {
        assertEquals("Cuatro mil setecientos setenta con 00/100 dólares americanos",
            AmountToWords.amountToWords(new BigDecimal("4770"), "USD"));
    }

    @Test
    void withCents() {
        assertEquals("Mil ciento ochenta con 50/100 soles",
            AmountToWords.amountToWords(new BigDecimal("1180.50"), "PEN"));
    }

    @Test
    void one_apocope() {
        assertEquals("Uno con 00/100 dólares americanos",
            AmountToWords.amountToWords(new BigDecimal("1"), "USD"));
    }

    @Test
    void hundred() {
        assertEquals("Cien con 00/100 soles",
            AmountToWords.amountToWords(new BigDecimal("100"), "PEN"));
    }

    @Test
    void zero() {
        assertEquals("Cero con 00/100 soles",
            AmountToWords.amountToWords(BigDecimal.ZERO, "PEN"));
    }

    @Test
    void billion_longScale() {
        // 10^12 = "un billón" en escala larga es-PE.
        assertEquals("Un billón con 00/100 soles",
            AmountToWords.amountToWords(new BigDecimal("1000000000000"), "PEN"));
    }

    @Test
    void roundingCarry() {
        // 4769.999 redondea a 4770.00 (HALF_UP); centavos = 0, no 100.
        assertEquals("Cuatro mil setecientos setenta con 00/100 soles",
            AmountToWords.amountToWords(new BigDecimal("4769.999"), "PEN"));
    }

    @Test
    void negativeDegradesToZero() {
        assertEquals("Cero con 00/100 soles",
            AmountToWords.amountToWords(new BigDecimal("-5"), "PEN"));
    }
}
