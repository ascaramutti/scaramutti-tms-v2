package com.scaramutti.tms.catalogs.quotationservicetype.model;

import com.scaramutti.tms.shared.exception.ApiException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests del enum QuotationServiceKind, en particular del metodo
 * fromCode que es la lógica crítica del kind computado. Si esto se rompe,
 * todos los responses devuelven kinds incorrectos sin warning.
 */
class QuotationServiceKindTest {

    @Test
    void fromCode_withSPrefix_returnsServicio() {
        assertEquals(QuotationServiceKind.SERVICIO, QuotationServiceKind.fromCode("SCB"));
        assertEquals(QuotationServiceKind.SERVICIO, QuotationServiceKind.fromCode("STR"));
    }

    @Test
    void fromCode_withAPrefix_returnsAlquiler() {
        assertEquals(QuotationServiceKind.ALQUILER, QuotationServiceKind.fromCode("ACB"));
        assertEquals(QuotationServiceKind.ALQUILER, QuotationServiceKind.fromCode("ALM"));
    }

    @Test
    void fromCode_withCPrefix_returnsComplementario() {
        assertEquals(QuotationServiceKind.COMPLEMENTARIO, QuotationServiceKind.fromCode("CES"));
        assertEquals(QuotationServiceKind.COMPLEMENTARIO, QuotationServiceKind.fromCode("CSE"));
    }

    @Test
    void fromCode_withIPrefix_returnsIntegral() {
        assertEquals(QuotationServiceKind.INTEGRAL, QuotationServiceKind.fromCode("INT"));
    }

    @Test
    void fromCode_withInvalidPrefix_throwsCatalogsErrorCAT002() {
        // X no es S/A/C/I → debe fallar con CAT-002.
        ApiException ex = assertThrows(
            ApiException.class,
            () -> QuotationServiceKind.fromCode("XYZ")
        );
        assertEquals("CAT-002", ex.code());
        assertEquals(400, ex.status());
        assertTrue(
            ex.getMessage().contains("XYZ"),
            "Mensaje de error debe incluir el code ofensor"
        );
    }

    @Test
    void fromCode_withNullCode_throwsCatalogsErrorCAT001() {
        ApiException ex = assertThrows(
            ApiException.class,
            () -> QuotationServiceKind.fromCode(null)
        );
        assertEquals("CAT-001", ex.code());
        assertEquals(400, ex.status());
    }

    @Test
    void fromCode_withEmptyCode_throwsCatalogsErrorCAT001() {
        ApiException ex = assertThrows(
            ApiException.class,
            () -> QuotationServiceKind.fromCode("")
        );
        assertEquals("CAT-001", ex.code());
    }

    @Test
    void fromCode_withLowercasePrefix_throwsCatalogsErrorCAT002() {
        // El contrato exige uppercase (^[A-Z]{2,10}$). El enum solo matchea
        // mayúsculas exactas. Si llegara un code minúscula, falla — lo cual
        // es correcto porque la convención lo prohibe.
        ApiException ex = assertThrows(
            ApiException.class,
            () -> QuotationServiceKind.fromCode("scb")
        );
        assertEquals("CAT-002", ex.code());
    }

    @Test
    void prefix_returnsExpectedChar() {
        assertEquals('S', QuotationServiceKind.SERVICIO.prefix());
        assertEquals('A', QuotationServiceKind.ALQUILER.prefix());
        assertEquals('C', QuotationServiceKind.COMPLEMENTARIO.prefix());
        assertEquals('I', QuotationServiceKind.INTEGRAL.prefix());
    }
}
