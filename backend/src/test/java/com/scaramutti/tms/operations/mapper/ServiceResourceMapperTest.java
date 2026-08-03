package com.scaramutti.tms.operations.mapper;

import com.scaramutti.tms.operations.model.ServiceStatus;
import com.scaramutti.tms.operations.service.cmd.ListServicesQuery;
import com.scaramutti.tms.shared.exception.ApiException;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit de la traducción de los query params del listado. Acá viven las reglas más densas del
 * endpoint (seis parseos y siete rechazos distintos), y probarlas como función pura permite
 * afirmar el MENSAJE de cada una: por HTTP todas responden el mismo 400 con el mismo código, así
 * que sin mirar el detalle cinco casos distintos parecerían probados y estaría probado uno solo.
 *
 * También se fija el lado que ACEPTA de cada borde, no solo el que rechaza: un tope mal escrito
 * (mayor en vez de mayor-o-igual) deja pasar o rechaza justo el valor límite que el contrato
 * publica como válido.
 */
class ServiceResourceMapperTest {

    private final ServiceResourceMapper mapper = Mappers.getMapper(ServiceResourceMapper.class);

    private ListServicesQuery query(String q) {
        return mapper.toListServicesQuery(q, null, null, null, null, null, null);
    }

    private static String messageOf(Executable call) {
        return assertThrows(ApiException.class, call).getMessage();
    }

    /** Alias local para no arrastrar el de JUnit en cada firma. */
    private interface Executable extends org.junit.jupiter.api.function.Executable { }

    // ---------- Id del viaje ----------------------------------------------------

    @Test
    void toServiceId_readsThePlainInteger() {
        assertEquals(42L, mapper.toServiceId("42"));
    }

    /**
     * El id NO se recorta: recortando, un id con espacios o con un carácter de control pegado
     * sería la misma dirección que el limpio. (Los ceros a la izquierda sí siguen aliasando, como
     * en el resto de los filtros del módulo: eso es otra decisión y no la toma este parseo.)
     */
    @Test
    void toServiceId_withPaddingOrControlCharacters_isRejected() {
        assertTrue(messageOf(() -> mapper.toServiceId(" 42")).contains("número entero"));
        assertTrue(messageOf(() -> mapper.toServiceId("42 ")).contains("número entero"));
        assertTrue(messageOf(() -> mapper.toServiceId("42\u0000")).contains("número entero"));
        assertTrue(messageOf(() -> mapper.toServiceId("4\t2")).contains("número entero"));
    }

    /**
     * El id se recibe como texto para poder contestar un 400 CON CUERPO: declarado con su tipo, un
     * valor que no parsea da el 404 vacío del framework, que se confunde con el 404 legítimo de
     * "ese viaje no existe".
     */
    @Test
    void toServiceId_withSomethingThatIsNotAnInteger_saysSo() {
        assertTrue(messageOf(() -> mapper.toServiceId("abc")).contains("número entero"));
        assertTrue(messageOf(() -> mapper.toServiceId("1.5")).contains("número entero"));
        assertTrue(messageOf(() -> mapper.toServiceId("")).contains("número entero"));
        assertTrue(messageOf(() -> mapper.toServiceId(null)).contains("número entero"));
        // cifras de otro alfabeto: un 12 árabigo no es un 12
        assertTrue(messageOf(() -> mapper.toServiceId("١٢")).contains("número entero"));
    }

    /** Un número bien escrito pero más grande que el tipo tiene su propio mensaje. */
    @Test
    void toServiceId_withANumberTooBig_saysSo() {
        assertTrue(messageOf(() -> mapper.toServiceId("9999999999999999999999"))
            .contains("fuera de rango"));
    }

    /** El borde del tipo ENTRA: es un id válido, aunque no exista. */
    @Test
    void toServiceId_withTheLargestPossibleId_isAccepted() {
        assertEquals(Long.MAX_VALUE, mapper.toServiceId(String.valueOf(Long.MAX_VALUE)));
    }

    // ---------- Término de búsqueda -------------------------------------------

    @Test
    void toListServicesQuery_withoutSearch_leavesItUnset() {
        assertNull(query(null).q());
        assertNull(query("   ").q());
    }

    @Test
    void toListServicesQuery_trimsTheSearchTerm() {
        assertEquals("Piura", query("  Piura  ").q());
    }

    /** El mínimo del término completo tiene su propio mensaje: es una regla distinta. */
    @Test
    void toListServicesQuery_withTermShorterThanTheMinimum_saysSo() {
        assertTrue(messageOf(() -> query("ab")).contains("al menos 3 caracteres"));
        assertTrue(messageOf(() -> query("  ab  ")).contains("al menos 3 caracteres"));
    }

    /** Tres caracteres es el MÍNIMO que publica el contrato, no el primer valor rechazado. */
    @Test
    void toListServicesQuery_withExactlyTheMinimumLength_isAccepted() {
        assertEquals("IPH", query("IPH").q());
    }

    /** Con el largo suficiente pero sin ninguna palabra útil, el rechazo es OTRO. */
    @Test
    void toListServicesQuery_withoutAnyUsefulWord_saysSo() {
        assertTrue(messageOf(() -> query("a de la")).contains("al menos una palabra"));
    }

    @Test
    void toListServicesQuery_dropsShortWordsAndKeepsTheRest() {
        assertEquals("Piura Lima", query("Piura de Lima").q());
    }

    @Test
    void toListServicesQuery_withTooManyWords_saysSo() {
        assertTrue(messageOf(() -> query("uno dos tres cuatro cinco seis siete ocho nueve"))
            .contains("hasta 8 palabras"));
    }

    /** Ocho palabras es el LÍMITE, no el primer valor rechazado. */
    @Test
    void toListServicesQuery_withExactlyEightWords_isAccepted() {
        String eightWords = "uno dos tres cuatro cinco seis siete ocho";

        assertEquals(eightWords, query(eightWords).q());
    }

    @Test
    void toListServicesQuery_withTooLongTerm_saysSo() {
        assertTrue(messageOf(() -> query("x".repeat(201))).contains("hasta 200 caracteres"));
    }

    /** Doscientos caracteres exactos entran: es el tope que publica el contrato. */
    @Test
    void toListServicesQuery_withExactlyTheMaximumLength_isAccepted() {
        String atTheLimit = "x".repeat(200);

        assertEquals(atTheLimit, query(atTheLimit).q());
    }

    /** Un NUL sobrevive al trim y a la división en palabras: PostgreSQL no lo admite. */
    @Test
    void toListServicesQuery_withControlCharacterInSearch_saysSo() {
        assertTrue(messageOf(() -> query("abc\u0000def")).contains("caracteres no válidos"));
        assertTrue(messageOf(() -> query("abc\u007Fdef")).contains("caracteres no válidos"));
        // Java los cuenta como espacio en blanco, pero el partidor de palabras NO los separa:
        // preguntar por "es espacio" los dejaría viajar dentro del término hasta la consulta.
        assertTrue(messageOf(() -> query("abc\u001Cdef")).contains("caracteres no válidos"));
        assertTrue(messageOf(() -> query("abc\u001Fdef")).contains("caracteres no válidos"));
    }

    /** Un salto de línea NO está prohibido: separa palabras igual que un espacio. */
    @Test
    void toListServicesQuery_withNewlineInSearch_splitsTheWords() {
        assertEquals("Piura Lima", query("Piura\nLima").q());
    }

    // ---------- Estado ---------------------------------------------------------

    @Test
    void toListServicesQuery_translatesTheStatus() {
        ListServicesQuery result = mapper.toListServicesQuery(null, "DELETED", null, null, null, null, null);

        assertEquals(ServiceStatus.DELETED, result.status());
    }

    @Test
    void toListServicesQuery_withUnknownStatus_saysWhichOne() {
        String message = messageOf(
            () -> mapper.toListServicesQuery(null, "NAVEGANDO", null, null, null, null, null));

        assertTrue(message.contains("El estado indicado no existe"));
        assertTrue(message.contains("NAVEGANDO"));
    }

    /** Otra grafía no es el mismo estado. */
    @Test
    void toListServicesQuery_withLowercaseStatus_isRejected() {
        assertThrows(ApiException.class,
            () -> mapper.toListServicesQuery(null, "deleted", null, null, null, null, null));
    }

    /** El texto del usuario vuelve recortado, no entero. */
    @Test
    void toListServicesQuery_withVeryLongInvalidStatus_truncatesTheEcho() {
        String message = messageOf(
            () -> mapper.toListServicesQuery(null, "X".repeat(120), null, null, null, null, null));

        assertTrue(message.contains("…"));
        assertTrue(message.length() < 120);
    }

    /**
     * El recorte del texto reflejado cuenta CARACTERES, no unidades de código: cortando por
     * unidades, un emoji al borde queda partido al medio y el mensaje deja de ser texto válido.
     */
    @Test
    void toListServicesQuery_withEmojiAtTheCutOff_doesNotBreakIt() {
        String justAtTheLimit = "A".repeat(29) + "😀";
        String wellPastTheLimit = "A".repeat(40) + "😀";

        String message = messageOf(
            () -> mapper.toListServicesQuery(null, justAtTheLimit, null, null, null, null, null));
        assertTrue(message.contains("😀"));
        assertEveryCharacterIsWellFormed(message);

        assertEveryCharacterIsWellFormed(messageOf(
            () -> mapper.toListServicesQuery(null, wellPastTheLimit, null, null, null, null, null)));
    }

    /** Ningún carácter de dos unidades puede quedar con la mitad suelta. */
    private static void assertEveryCharacterIsWellFormed(String text) {
        for (int i = 0; i < text.length(); i++) {
            char unit = text.charAt(i);
            if (Character.isHighSurrogate(unit)) {
                assertTrue(i + 1 < text.length() && Character.isLowSurrogate(text.charAt(i + 1)),
                    "quedó una mitad suelta en: " + text);
                i++;
            } else {
                assertTrue(!Character.isLowSurrogate(unit), "quedó una mitad suelta en: " + text);
            }
        }
    }

    /** El contrato declara un entero: un 5 escrito en otro alfabeto no es un 5. */
    @Test
    void toListServicesQuery_withNonAsciiDigits_isRejected() {
        assertTrue(messageOf(() -> mapper.toListServicesQuery(null, null, "٥", null, null, null, null))
            .contains("clientId"));
        assertTrue(messageOf(() -> mapper.toListServicesQuery(null, null, "５", null, null, null, null))
            .contains("número entero"));
        assertTrue(messageOf(() -> mapper.toListServicesQuery(null, null, null, null, null, "１", null))
            .contains("page"));
    }

    /** Lo que el contrato SÍ declara sigue entrando, signo incluido. */
    @Test
    void toListServicesQuery_withPlainIntegers_areAccepted() {
        assertEquals(7, mapper.toListServicesQuery(null, null, "7", null, null, null, null).clientId());
        assertEquals(-3, mapper.toListServicesQuery(null, null, "-3", null, null, null, null).clientId());
    }

    // ---------- Cliente y fechas ------------------------------------------------

    @Test
    void toListServicesQuery_parsesClientAndDates() {
        ListServicesQuery result =
            mapper.toListServicesQuery(null, null, "7", "2026-05-10", "2026-05-12", null, null);

        assertEquals(7, result.clientId());
        assertEquals(LocalDate.of(2026, 5, 10), result.dateFrom());
        assertEquals(LocalDate.of(2026, 5, 12), result.dateTo());
    }

    @Test
    void toListServicesQuery_withMalformedClient_namesTheFilter() {
        String message = messageOf(
            () -> mapper.toListServicesQuery(null, null, "abc", null, null, null, null));

        assertTrue(message.contains("clientId"));
        assertTrue(message.contains("número entero"));
    }

    @Test
    void toListServicesQuery_withMalformedDate_namesTheFilterAndTheFormat() {
        String message = messageOf(
            () -> mapper.toListServicesQuery(null, null, null, "ayer", null, null, null));

        assertTrue(message.contains("dateFrom"));
        assertTrue(message.contains("AAAA-MM-DD"));
    }

    /**
     * Fuera del rango de fechas que soporta la columna: 400 con detalle, no el 500 que devolvía
     * el motor cuando el valor le llegaba tal cual.
     */
    @Test
    void toListServicesQuery_withDateOutOfTheWindow_namesTheFilterAndTheWindow() {
        String message = messageOf(
            () -> mapper.toListServicesQuery(null, null, null, "+999999999-01-01", null, null, null));

        assertTrue(message.contains("dateFrom"));
        assertTrue(message.contains("entre 1900-01-01 y 2999-12-31"));
        assertTrue(messageOf(
            () -> mapper.toListServicesQuery(null, null, null, "1899-12-31", null, null, null))
            .contains("dateFrom"));
        assertTrue(messageOf(
            () -> mapper.toListServicesQuery(null, null, null, null, "3000-01-01", null, null))
            .contains("dateTo"));
    }

    /** Los dos bordes de la ventana ENTRAN: son límites, no el primer valor rechazado. */
    @Test
    void toListServicesQuery_withDatesAtTheEdgesOfTheWindow_areAccepted() {
        ListServicesQuery result =
            mapper.toListServicesQuery(null, null, null, "1900-01-01", "2999-12-31", null, null);

        assertEquals(LocalDate.of(1900, 1, 1), result.dateFrom());
        assertEquals(LocalDate.of(2999, 12, 31), result.dateTo());
    }

    // ---------- Paginación ------------------------------------------------------

    /** Ausente y vacío son lo mismo: un filtro sin elegir toma el valor por defecto. */
    @Test
    void toListServicesQuery_withoutPaging_usesTheDefaults() {
        ListServicesQuery fromNull = mapper.toListServicesQuery(null, null, null, null, null, null, null);
        ListServicesQuery fromEmpty = mapper.toListServicesQuery(null, null, null, null, null, "", "  ");

        assertEquals(0, fromNull.page());
        assertEquals(20, fromNull.size());
        assertEquals(0, fromEmpty.page());
        assertEquals(20, fromEmpty.size());
    }

    /** Cien por página es el LÍMITE que publica el contrato, no el primer valor rechazado. */
    @Test
    void toListServicesQuery_withTheMaximumPageSize_isAccepted() {
        assertEquals(100, mapper.toListServicesQuery(null, null, null, null, null, null, "100").size());
    }

    @Test
    void toListServicesQuery_withPageSizeOutOfRange_namesTheRange() {
        assertTrue(messageOf(() -> mapper.toListServicesQuery(null, null, null, null, null, null, "101"))
            .contains("no puede ser mayor que 100"));
        assertTrue(messageOf(() -> mapper.toListServicesQuery(null, null, null, null, null, null, "0"))
            .contains("no puede ser menor que 1"));
    }

    /**
     * Cada filtro tiene que nombrarse a SÍ MISMO: con las etiquetas cruzadas, el mensaje manda a
     * corregir el parámetro equivocado y por HTTP los cinco rechazos son indistinguibles (mismo
     * 400, mismo código).
     */
    @Test
    void toListServicesQuery_namesTheFilterThatFailed() {
        assertTrue(messageOf(() -> mapper.toListServicesQuery(null, null, null, null, "ayer", null, null))
            .contains("dateTo"));
        assertTrue(messageOf(() -> mapper.toListServicesQuery(null, null, null, null, null, "abc", null))
            .contains("page"));
        assertTrue(messageOf(() -> mapper.toListServicesQuery(null, null, null, null, null, null, "abc"))
            .contains("size"));
    }

    @Test
    void toListServicesQuery_withNegativePage_isRejected() {
        assertTrue(messageOf(() -> mapper.toListServicesQuery(null, null, null, null, null, "-1", null))
            .contains("no puede ser menor que 0"));
    }
}
