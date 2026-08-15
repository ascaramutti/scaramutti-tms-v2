package com.scaramutti.tms.operations.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * La regla del texto que entra a la bitacora, medida en el unico lugar donde vive.
 *
 * <p>Existe porque la clase se extrajo justamente para ser ese lugar unico, y sin test propio la
 * garantia queda apoyada en los casos de los endpoints, que prueban UNA forma de salto de linea.
 * El javadoc de la clase promete que cubre TODAS las que reconoce Java, y esa promesa la tiene
 * que sostener alguien: reemplazar {@code \R} por {@code "\n"} deja verdes los tests de los dos
 * endpoints y reabre el agujero para el retorno de carro suelto, que en pantalla corta la linea
 * igual.
 */
class ServiceLogTextTest {

    /**
     * Todas las formas de salto que reconoce {@code \R}. El retorno de carro suelto, la
     * tabulacion vertical y los dos separadores de linea de Unicode son las que un reemplazo
     * ingenuo de {@code "\n"} deja pasar, y todas cortan la linea en pantalla igual que un salto
     * normal.
     *
     * <p>Los caracteres se arman por su CODIGO y no se escriben literales: un separador de linea
     * dentro del fuente lo corta de verdad, y un archivo de test con saltos invisibles es
     * exactamente la clase de cosa que despues nadie entiende.
     */
    @ParameterizedTest
    @ValueSource(ints = {0x0A, 0x0B, 0x0C, 0x0D, 0x85, 0x2028, 0x2029})
    void flattenLineBreaks_replacesEveryFormOfLineBreak(int codePoint) {
        String flattened = ServiceLogText.flattenLineBreaks(
            "ok" + (char) codePoint + "Conductor: FALSO");

        assertEquals("ok ⏎ Conductor: FALSO", flattened);
        assertEquals(1, flattened.lines().count(), "el texto aplastado ocupa UNA linea");
    }

    /** El par CR+LF es UN salto, no dos: aplastarlo dos veces duplicaria el simbolo. */
    @Test
    void flattenLineBreaks_treatsCarriageReturnPlusNewlineAsOneBreak() {
        assertEquals("ok ⏎ Conductor: FALSO",
            ServiceLogText.flattenLineBreaks("ok\r\nConductor: FALSO"));
    }

    /** Varios saltos seguidos se aplastan todos, no solo el primero. */
    @Test
    void flattenLineBreaks_replacesAllOfThem() {
        assertEquals("a ⏎ b ⏎ c", ServiceLogText.flattenLineBreaks("a\nb\rc"));
    }

    /** El texto sin saltos no se toca: aplastar no puede alterar lo que ya estaba bien. */
    @Test
    void flattenLineBreaks_leavesPlainTextUntouched() {
        assertEquals("Sale del patio a las 05:00",
            ServiceLogText.flattenLineBreaks("Sale del patio a las 05:00"));
    }

    /**
     * Null devuelve null y NO la etiqueta del vacio: quien llama decide si un texto ausente se
     * nombra o se omite, y esas dos cosas no son lo mismo en todos los endpoints.
     */
    @Test
    void flattenLineBreaks_withNull_returnsNull() {
        assertNull(ServiceLogText.flattenLineBreaks(null));
    }

    @Test
    void display_withNull_namesTheEmptyValue() {
        assertEquals("(vacío)", ServiceLogText.display(null));
    }

    /**
     * El recorte cuenta CARACTERES, no unidades de codigo. Es la promesa del javadoc y la unica que
     * importa: un emoji ocupa dos unidades, y cortarlo al medio deja media pareja suelta, que ya no
     * es texto valido —un lector estricto rechaza la respuesta y la interfaz muestra un rombo—.
     * Medido con el emoji JUSTO en el borde de los 30 caracteres, que es donde el error aparece.
     */
    @Test
    void abbreviate_doesNotCutAnEmojiInHalf() {
        String recortado = ServiceLogText.abbreviate("A".repeat(29) + "\uD83D\uDE00" + "B");

        assertEquals("A".repeat(29) + "\uD83D\uDE00" + "…", recortado);
        assertTrue(Character.isHighSurrogate(recortado.charAt(29)), recortado);
        assertTrue(Character.isLowSurrogate(recortado.charAt(30)), recortado);
    }

    /**
     * Lo que entra en el tope sale intacto, y el nulo no explota. El caso que importa es el del
     * medio: treinta EMOJIS son treinta caracteres y sesenta unidades de codigo. Medir el largo en
     * unidades recorta un texto que entraba —y encima con el corte a mitad de pareja—, y ese es el
     * unico caso donde las dos formas de contar se separan: con texto mas largo que el tope, las
     * dos recortan igual y la diferencia no se ve.
     */
    @Test
    void abbreviate_leavesShortTextAndNullAlone() {
        assertEquals("corto", ServiceLogText.abbreviate("corto"));
        assertEquals("A".repeat(30), ServiceLogText.abbreviate("A".repeat(30)));
        String treintaEmojis = "\uD83D\uDE00".repeat(30);
        assertEquals(treintaEmojis, ServiceLogText.abbreviate(treintaEmojis),
            "treinta caracteres entran en el tope, aunque ocupen sesenta unidades de codigo");
        assertNull(ServiceLogText.abbreviate(null));
    }

    /** {@code display} tambien aplasta: es el mismo rastro, entrando por la otra puerta. */
    @Test
    void display_flattensLineBreaksToo() {
        String displayed = ServiceLogText.display("ok\rPrecio: 3200 → 300");

        assertEquals("ok ⏎ Precio: 3200 → 300", displayed);
        assertFalse(displayed.contains("\r"));
    }
}
