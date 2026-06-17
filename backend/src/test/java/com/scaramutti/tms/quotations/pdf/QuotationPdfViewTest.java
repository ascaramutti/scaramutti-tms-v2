package com.scaramutti.tms.quotations.pdf;

import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test estructural de {@link QuotationPdfView} (no necesita Quarkus ni BD).
 *
 * <p>Hace cumplir <b>RN-03</b> de la feature de observaciones: la observacion para
 * el cliente ({@code clientNote}) SI esta en el view del PDF, pero la observacion
 * INTERNA NUNCA debe existir aqui — la plantilla Qute solo puede renderizar campos
 * del view, asi que mientras {@code internalNote} no sea un componente del record es
 * <b>estructuralmente imposible</b> que se filtre al PDF del cliente. Si un dev futuro
 * agrega un campo {@code internal*} a este view, este test se pone rojo a proposito.
 */
class QuotationPdfViewTest {

    private List<String> componentNames() {
        return Arrays.stream(QuotationPdfView.class.getRecordComponents())
            .map(RecordComponent::getName)
            .toList();
    }

    @Test
    void view_exposesClientNote() {
        assertTrue(componentNames().contains("clientNote"),
            "El view del PDF debe exponer clientNote (la observacion visible para el cliente).");
    }

    @Test
    void view_neverExposesInternalNote() {
        assertFalse(
            componentNames().stream().anyMatch(name -> name.toLowerCase().contains("internal")),
            "RN-03: la observacion interna NUNCA debe existir en el view del PDF "
                + "(seria filtrable hacia el cliente). Campos actuales: " + componentNames());
    }
}
