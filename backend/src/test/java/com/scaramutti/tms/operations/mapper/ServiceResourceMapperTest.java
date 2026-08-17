package com.scaramutti.tms.operations.mapper;

import com.scaramutti.tms.operations.dto.ServiceAddResourcesRequest;
import com.scaramutti.tms.operations.dto.ServiceCreateRequest;
import com.scaramutti.tms.operations.dto.ServiceStatusChangeRequest;
import com.scaramutti.tms.operations.dto.ServiceUpdateRequest;
import com.scaramutti.tms.operations.model.ServiceStatus;
import com.scaramutti.tms.operations.model.TripScope;
import com.scaramutti.tms.operations.service.cmd.AddServiceResourcesCommand;
import com.scaramutti.tms.operations.service.cmd.CreateServiceCommand;
import com.scaramutti.tms.operations.service.cmd.ListServicesQuery;
import com.scaramutti.tms.operations.service.cmd.UpdateServiceCommand;
import com.scaramutti.tms.shared.exception.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mapstruct.factory.Mappers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    /**
     * Un salto de línea NO está prohibido: separa palabras igual que un espacio. Y lo mismo los
     * otros cuatro.
     *
     * <p>Van los SEIS y escritos, no leídos de la constante: la lista existe para reflejar
     * exactamente el {@code \s} con el que {@code MultiWordSearch} parte el término, así que
     * probarla contra sí misma no verifica esa correspondencia. Con un solo caso (el salto), la
     * constante se podía recortar a la mitad y un término pegado desde Windows —que trae retorno de
     * carro— se iba en 400 "caracteres no válidos" sin que nada fallara.
     */
    @ParameterizedTest
    @ValueSource(chars = {' ', '\t', '\n', '\013', '\f', '\r'})
    void toListServicesQuery_withAnyWordSeparatorInSearch_splitsTheWords(char separator) {
        assertEquals("Piura Lima", query("Piura" + separator + "Lima").q());
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
        String emojiAtTheCutOffWithTrailingText = "A".repeat(29) + "😀" + "B".repeat(10);

        String message = messageOf(
            () -> mapper.toListServicesQuery(null, justAtTheLimit, null, null, null, null, null));
        assertTrue(message.contains("😀"));
        assertEveryCharacterIsWellFormed(message);

        assertEveryCharacterIsWellFormed(messageOf(
            () -> mapper.toListServicesQuery(null, emojiAtTheCutOffWithTrailingText, null, null, null, null, null)));
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

    /**
     * Y el otro extremo, PEDIDO EXPLÍCITAMENTE: la primera página y el tamaño mínimo que el
     * contrato publica como válidos.
     *
     * <p>El caso de los valores por defecto no cubre esto aunque dé los mismos números: ausente y
     * vacío salen por el atajo del valor por defecto sin llegar a la comparación de rango. Sin este
     * caso, escribir el borde de abajo como "menor o igual" en vez de "menor" rechazaba con 400
     * justo esos dos valores y la suite entera seguía en verde.
     */
    @Test
    void toListServicesQuery_withTheMinimumPagingValues_isAccepted() {
        assertEquals(1, mapper.toListServicesQuery(null, null, null, null, null, null, "1").size());
        assertEquals(0, mapper.toListServicesQuery(null, null, null, null, null, "0", null).page());
    }

    /**
     * Un entero bien escrito pero más grande que el tipo. Es la única rama del parseo de filtros que
     * no alcanza la expresión de cifras árabigas: la pasa entera y revienta recién al convertir.
     * Sin este caso, esa rama podía devolver null y el filtro se descartaba en silencio, sirviendo
     * el listado COMPLETO con 200 donde el contrato promete 400.
     */
    @Test
    void toListServicesQuery_withAnIntegerFilterTooBig_saysSo() {
        assertTrue(messageOf(() ->
                mapper.toListServicesQuery(null, null, "9999999999", null, null, null, null))
            .contains("clientId"));
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

    // ---------- Edición ----------------------------------------------------------

    @Test
    void toUpdateServiceCommand_carriesTheIdAndTheVersionFromOutsideTheBody() {
        UpdateServiceCommand command = mapper.toUpdateServiceCommand(42L, "\"v1\"", updateRequest());

        assertEquals(42L, command.serviceId());
        assertEquals("\"v1\"", command.ifMatch());
    }

    /**
     * Los TRECE campos del cuerpo, uno por uno y cada uno con un valor DISTINTO (el id y la
     * version vienen de afuera del cuerpo y los cubre el caso de arriba). El command se arma a
     * mano, así que dos campos cruzados (ancho por alto, largo por peso) compilan perfecto y no
     * los caza ningún test que solo mire que "el campo no es null": es exactamente el bug que ya
     * apareció una vez en este módulo cruzando origen con destino.
     */
    @Test
    void toUpdateServiceCommand_carriesEveryFieldToItsOwnSlot() {
        LocalDate tentativeDate = LocalDate.of(2026, 9, 15);
        OffsetDateTime start = OffsetDateTime.parse("2026-09-16T10:00:00Z");
        OffsetDateTime end = OffsetDateTime.parse("2026-09-17T18:30:00Z");
        ServiceUpdateRequest request = new ServiceUpdateRequest(
            tentativeDate, "Piura", "Lima",
            new BigDecimal("12000.50"), new BigDecimal("12.5"), new BigDecimal("2.6"),
            new BigDecimal("3.1"), new BigDecimal("4100.75"), 7, "Puerta 3",
            start, end, VALID_JUSTIFICATION);

        UpdateServiceCommand command = mapper.toUpdateServiceCommand(42L, "\"v1\"", request);

        assertEquals(tentativeDate, command.tentativeDate());
        assertEquals("Piura", command.origin());
        assertEquals("Lima", command.destination());
        assertEquals(new BigDecimal("12000.50"), command.weightKg());
        assertEquals(new BigDecimal("12.5"), command.lengthM());
        assertEquals(new BigDecimal("2.6"), command.widthM());
        assertEquals(new BigDecimal("3.1"), command.heightM());
        assertEquals(new BigDecimal("4100.75"), command.price());
        assertEquals(7, command.currencyId());
        assertEquals("Puerta 3", command.observations());
        assertEquals(start, command.startDateTime());
        assertEquals(end, command.endDateTime());
        assertEquals(VALID_JUSTIFICATION, command.justification());
    }

    /** Los textos libres se recortan, igual que en el alta: alimentan la búsqueda y el detalle. */
    @Test
    void toUpdateServiceCommand_trimsFreeText() {
        UpdateServiceCommand command = mapper.toUpdateServiceCommand(
            1L, null, updateRequest("  Piura  ", "  Lima  ", "   ", VALID_JUSTIFICATION));

        assertEquals("Piura", command.origin());
        assertEquals("Lima", command.destination());
        assertNull(command.observations(), "una observación en blanco es no tener observación");
    }

    /** La justificación viaja recortada: es lo que después se lee en la bitácora. */
    @Test
    void toUpdateServiceCommand_trimsTheJustification() {
        UpdateServiceCommand command = mapper.toUpdateServiceCommand(
            1L, null, updateRequest("Piura", "Lima", null, "  " + VALID_JUSTIFICATION + "  "));

        assertEquals(VALID_JUSTIFICATION, command.justification());
    }

    /**
     * El mínimo de la justificación se mide sobre el texto YA RECORTADO. (Por HTTP este caso lo
     * tapa antes la anotación de "no en blanco"; el que de verdad necesita esta guarda es el
     * siguiente, y como función pura los dos se pueden separar.)
     */
    @Test
    void toUpdateServiceCommand_withAJustificationOfOnlySpaces_isRejected() {
        assertTrue(messageOf(() -> mapper.toUpdateServiceCommand(
                1L, null, updateRequest("Piura", "Lima", null, "              ")))
            .contains("al menos 10 caracteres"));
    }

    @Test
    void toUpdateServiceCommand_withAJustificationPaddedToReachTheMinimum_isRejected() {
        assertTrue(messageOf(() -> mapper.toUpdateServiceCommand(
                1L, null, updateRequest("Piura", "Lima", null, "corta     ")))
            .contains("al menos 10 caracteres"));
    }

    /**
     * El borde que SÍ se acepta: exactamente el mínimo. Sin este caso, un tope mal escrito
     * (mayor en vez de mayor-o-igual) rechazaría la justificación más corta que el contrato
     * publica como válida y ningún test lo notaría.
     */
    @Test
    void toUpdateServiceCommand_withExactlyTheMinimumJustification_isAccepted() {
        UpdateServiceCommand command = mapper.toUpdateServiceCommand(
            1L, null, updateRequest("Piura", "Lima", null, "1234567890"));

        assertEquals("1234567890", command.justification());
    }

    /**
     * El formato ISO admite años de nueve cifras y las columnas de fecha de PostgreSQL no: sin
     * esta ventana el valor llega hasta el motor y sale un 500 donde el contrato promete un 400.
     * El tipado no protege de nada acá — la fecha es perfectamente válida para Java.
     */
    @Test
    void toUpdateServiceCommand_withADateOutsideTheBusinessWindow_isRejected() {
        assertTrue(messageOf(() -> mapper.toUpdateServiceCommand(
                1L, null, updateRequest(LocalDate.of(999_999_999, 12, 31))))
            .contains("fecha tentativa"));
        assertTrue(messageOf(() -> mapper.toUpdateServiceCommand(
                1L, null, updateRequest(LocalDate.of(1899, 12, 31))))
            .contains("fecha tentativa"));
    }

    /**
     * Los dos bordes que SÍ entran, para que la ventana no se cierre de más.
     *
     * <p>Las fechas van ESCRITAS y no tomadas de la constante que definen la ventana: pasadas por
     * la constante, la prueba compara el valor contra sí mismo y sigue en verde con la ventana
     * corrida a cualquier otro par de fechas, o sea deja de medir lo que dice medir. El caso
     * hermano de arriba ya escribe el 1899-12-31 que queda un día afuera.
     */
    @Test
    void toUpdateServiceCommand_withTheEdgesOfTheBusinessWindow_isAccepted() {
        LocalDate firstDayInside = LocalDate.of(1900, 1, 1);
        LocalDate lastDayInside = LocalDate.of(2999, 12, 31);
        assertEquals(firstDayInside, mapper.toUpdateServiceCommand(
            1L, null, updateRequest(firstDayInside)).tentativeDate());
        assertEquals(lastDayInside, mapper.toUpdateServiceCommand(
            1L, null, updateRequest(lastDayInside)).tentativeDate());
    }

    /** La misma ventana rige para las marcas de tiempo, que van a otra columna con el mismo tope. */
    @Test
    void toUpdateServiceCommand_withARealDateOutsideTheBusinessWindow_isRejected() {
        assertTrue(messageOf(() -> mapper.toUpdateServiceCommand(1L, null,
                updateRequestWithRealDates(OffsetDateTime.of(
                    LocalDate.of(999_999_999, 12, 31).atStartOfDay(), ZoneOffset.UTC), null)))
            .contains("inicio"));
        assertTrue(messageOf(() -> mapper.toUpdateServiceCommand(1L, null,
                updateRequestWithRealDates(null, OffsetDateTime.of(
                    LocalDate.of(999_999_999, 12, 31).atStartOfDay(), ZoneOffset.UTC))))
            .contains("fin"));
    }

    /**
     * El borde que solo se ve midiendo en el huso CORRECTO: las 23:00 del último día de la ventana
     * escritas con catorce horas de atraso son, en UTC, el primer día de fuera. Medida tal como
     * vino la marca entra como válida y se guarda ya fuera de rango; el chequeo tiene que mirar el
     * mismo huso en el que el valor se va a guardar.
     */
    @Test
    void toUpdateServiceCommand_withARealDateThatOnlyLeavesTheWindowInUtc_isRejected() {
        OffsetDateTime lastDayLateOffset = OffsetDateTime.parse("2999-12-31T23:00:00-14:00");

        assertTrue(messageOf(() -> mapper.toUpdateServiceCommand(
                1L, null, updateRequestWithRealDates(lastDayLateOffset, null)))
            .contains("inicio"));
        // Y la mitad simétrica: el fin pasa por la misma guarda y tiene que rechazarse igual.
        assertTrue(messageOf(() -> mapper.toUpdateServiceCommand(
                1L, null, updateRequestWithRealDates(null, lastDayLateOffset)))
            .contains("fin"));
    }

    /** Y el borde que SÍ entra: el mismo instante escrito ya en UTC cae dentro de la ventana. */
    @Test
    void toUpdateServiceCommand_withTheLastInstantOfTheWindowInUtc_isAccepted() {
        OffsetDateTime lastDayInUtc = OffsetDateTime.parse("2999-12-31T23:00:00Z");

        UpdateServiceCommand command = mapper.toUpdateServiceCommand(
            1L, null, updateRequestWithRealDates(lastDayInUtc, null));

        assertEquals(lastDayInUtc, command.startDateTime());
    }

    /**
     * Una marca pegada al tope del tipo con desfase negativo DESBORDA al pasarla a UTC. Sin medirla
     * ANTES de convertirla, el error de fecha sale sin mapear, o sea un 500 en la misma clase de
     * entrada que esta ventana existe para atajar. (Por HTTP el lector de JSON llega primero; acá,
     * como función pura, se puede medir la guarda propia.)
     */
    @Test
    void toUpdateServiceCommand_withARealDateThatOverflowsWhenNormalized_isRejected() {
        OffsetDateTime pastTheTypeLimit = OffsetDateTime.parse("+999999999-12-31T23:59:59-18:00");

        assertTrue(messageOf(() -> mapper.toUpdateServiceCommand(
                1L, null, updateRequestWithRealDates(pastTheTypeLimit, null)))
            .contains("inicio"));
    }

    /** Las fechas reales ausentes pasan como null: es lo que significa "sin cambio". */
    @Test
    void toUpdateServiceCommand_withoutRealDates_leavesThemNull() {
        UpdateServiceCommand command = mapper.toUpdateServiceCommand(1L, null, updateRequest());

        assertNull(command.startDateTime());
        assertNull(command.endDateTime());
    }

    /**
     * El NUL al PRINCIPIO tiene caso propio porque es el borde de POSICIÓN y no de campo: las
     * cuatro aserciones del caso hermano (abajo) lo mandan en el MEDIO de la cadena, así que
     * escrita la guarda como "aparece después del primer carácter" las cuatro quedarían en verde.
     *
     * <p>La guarda hoy es correcta y siempre lo fue: mira toda la cadena. Lo que faltaba era el
     * caso que lo sostuviera, y sin él el error se podía introducir sin que nada fallara. En un
     * campo MULTILÍNEA no hay red debajo (la guarda de una línea no lo mira), así que el byte
     * llegaría hasta PostgreSQL, que no lo admite, y saldría un 500 donde el contrato promete
     * un 400.
     */
    @Test
    void toUpdateServiceCommand_withANulCharacterAtTheStartOfAMultilineField_isRejected() {
        assertTrue(messageOf(() -> mapper.toUpdateServiceCommand(1L, null,
                updateRequest("Piura", "Lima", "\u0000al principio", VALID_JUSTIFICATION)))
            .contains("observaciones"));
        assertTrue(messageOf(() -> mapper.toUpdateServiceCommand(1L, null,
                updateRequest("Piura", "Lima", null, "\u0000justificacion larga")))
            .contains("justificación"));
    }

    /**
     * El byte NUL en los textos libres. Como función pura se puede afirmar el MENSAJE de cada
     * campo: por HTTP los cuatro rechazos son el mismo 400 con el mismo código, así que sin mirar
     * el detalle parecería probado uno solo.
     */
    @Test
    void toUpdateServiceCommand_withANulCharacterInFreeText_namesTheField() {
        String withNul = "texto \u0000 partido";

        assertTrue(messageOf(() -> mapper.toUpdateServiceCommand(1L, null,
                updateRequest(withNul, "Lima", null, VALID_JUSTIFICATION))).contains("origen"));
        assertTrue(messageOf(() -> mapper.toUpdateServiceCommand(1L, null,
                updateRequest("Piura", withNul, null, VALID_JUSTIFICATION))).contains("destino"));
        assertTrue(messageOf(() -> mapper.toUpdateServiceCommand(1L, null,
                updateRequest("Piura", "Lima", withNul, VALID_JUSTIFICATION))).contains("observaciones"));
        assertTrue(messageOf(() -> mapper.toUpdateServiceCommand(1L, null,
                updateRequest("Piura", "Lima", null, withNul + " y larga"))).contains("justificación"));
    }

    /**
     * Los DOS separadores de línea de Unicode en un texto de una línea (el origen, el destino).
     *
     * <p>Quedan fuera de la definición de "control ISO", así que la guarda los nombra aparte. Sin
     * esta prueba esa mitad se podía borrar y la suite seguía verde: los demás casos usan controles
     * ISO, que la primera mitad ya cubre. Y el motivo por el que importan es el mismo que el del
     * salto común: el aplastado que la bitácora aplica ({@code \R}) SÍ los trata como salto, así que
     * pasar sin rechazo dejaría plantar una línea con el formato del servidor.
     */
    @ParameterizedTest
    @ValueSource(strings = {"\u2028", "\u2029"})
    void toUpdateServiceCommand_withAUnicodeLineSeparatorInASingleLineField_isRejected(
            String separator) {
        assertTrue(messageOf(() -> mapper.toUpdateServiceCommand(1L, null,
                updateRequest("Piura" + separator + "Lima", "Lima", null, VALID_JUSTIFICATION)))
            .contains("saltos de línea"));
        assertTrue(messageOf(() -> mapper.toUpdateServiceCommand(1L, null,
                updateRequest("Piura", "Lima" + separator + "Sullana", null, VALID_JUSTIFICATION)))
            .contains("saltos de línea"));
    }

    /**
     * La marca de tiempo de la transición se recibe como TEXTO, y su rechazo tiene mensaje propio.
     *
     * <p>Se afirma acá y no por HTTP por el mismo motivo que el resto de este archivo: contra el
     * servidor los rechazos de este endpoint son todos el mismo 400 con el mismo código, así que
     * el texto podía cambiarse por cualquier otro sin que nada fallara y el usuario quedaba sin
     * saber qué formato mandar.
     */
    @Test
    void toChangeServiceStatusCommand_withADateTimeThatDoesNotParse_saysTheExpectedFormat() {
        assertTrue(messageOf(() -> mapper.toChangeServiceStatusCommand(1L, null,
                new ServiceStatusChangeRequest("IN_PROGRESS", "ayer", null, null)))
            .contains("AAAA-MM-DDTHH:MM:SSZ"));
    }

    /** Los controles que la columna SÍ admite no se tocan: un salto de línea es texto legítimo. */
    @Test
    void toUpdateServiceCommand_withNewlinesAndTabs_isAccepted() {
        UpdateServiceCommand command = mapper.toUpdateServiceCommand(1L, null,
            updateRequest("Piura", "Lima", "linea1\nlinea2\tcon tab", VALID_JUSTIFICATION));

        assertEquals("linea1\nlinea2\tcon tab", command.observations());
    }

    // ---------- Alta ---------------------------------------------------------------

    /** La ventana de fechas es la misma en el alta: la columna que las recibe es la misma. */
    @Test
    void toCreateServiceCommand_withADateOutsideTheBusinessWindow_isRejected() {
        assertTrue(messageOf(() -> mapper.toCreateServiceCommand(new ServiceCreateRequest(
                1, TripScope.PROVINCIA, LocalDate.of(999_999_999, 12, 31), "Piura", "Lima", 1,
                BigDecimal.TEN, null, null, null, BigDecimal.TEN, 1, null)))
            .contains("fecha tentativa"));
    }

    @Test
    void toCreateServiceCommand_trimsFreeTextAsBefore() {
        CreateServiceCommand command = mapper.toCreateServiceCommand(new ServiceCreateRequest(
            1, TripScope.PROVINCIA, LocalDate.now(), "  Piura  ", "  Lima  ", 1,
            BigDecimal.TEN, null, null, null, BigDecimal.TEN, 1, "   "));

        assertEquals("Piura", command.origin());
        assertEquals("Lima", command.destination());
        assertNull(command.observations());
    }

    // ---------- Fábricas del cuerpo de edición ---------------------------------------

    private static final String VALID_JUSTIFICATION = "Corrección del punto de entrega";

    private static ServiceUpdateRequest updateRequest() {
        return updateRequest("Piura", "Lima", null, VALID_JUSTIFICATION);
    }

    private static ServiceUpdateRequest updateRequest(LocalDate tentativeDate) {
        return new ServiceUpdateRequest(tentativeDate, "Piura", "Lima", BigDecimal.TEN,
            null, null, null, BigDecimal.TEN, 1, null, null, null, VALID_JUSTIFICATION);
    }

    private static ServiceUpdateRequest updateRequest(
            String origin, String destination, String observations, String justification) {
        return new ServiceUpdateRequest(LocalDate.now(), origin, destination, BigDecimal.TEN,
            null, null, null, BigDecimal.TEN, 1, observations, null, null, justification);
    }

    private static ServiceUpdateRequest updateRequestWithRealDates(
            OffsetDateTime start, OffsetDateTime end) {
        return new ServiceUpdateRequest(LocalDate.now(), "Piura", "Lima", BigDecimal.TEN,
            null, null, null, BigDecimal.TEN, 1, null, start, end, VALID_JUSTIFICATION);
    }

    // ---------- Refuerzos ------------------------------------------------------

    private AddServiceResourcesCommand reinforcement(
            Integer driverId, Integer tractorId, Integer trailerId, String reason, String force) {
        return mapper.toAddServiceResourcesCommand(7L,
            new ServiceAddResourcesRequest(driverId, tractorId, trailerId, reason, force));
    }

    private static final String VALID_REASON = "Relevo por descanso reglamentario";

    @Test
    void toAddServiceResourcesCommand_keepsTheThreeResourceIds() {
        AddServiceResourcesCommand command = reinforcement(4, 5, 6, VALID_REASON, null);

        assertEquals(7L, command.serviceId());
        assertEquals(4, command.driverId());
        assertEquals(5, command.tractorId());
        assertEquals(6, command.trailerId());
        assertEquals(VALID_REASON, command.reason());
        assertFalse(command.force());
    }

    /** "Al menos uno" es una condición ENTRE campos: no se puede declarar, la sostiene el mapper. */
    @Test
    void toAddServiceResourcesCommand_withoutAnyResource_rejects() {
        assertTrue(messageOf(() -> reinforcement(null, null, null, VALID_REASON, null))
            .contains("al menos un recurso"));
    }

    /** Un solo recurso alcanza, sea cual sea: es lo que separa el refuerzo de la asignación. */
    @Test
    void toAddServiceResourcesCommand_withOnlyOneResource_isAccepted() {
        assertEquals(4, reinforcement(4, null, null, VALID_REASON, null).driverId());
        assertEquals(5, reinforcement(null, 5, null, VALID_REASON, null).tractorId());
        assertEquals(6, reinforcement(null, null, 6, VALID_REASON, null).trailerId());
    }

    @Test
    void toAddServiceResourcesCommand_trimsTheReason() {
        assertEquals(VALID_REASON, reinforcement(4, null, null, "  " + VALID_REASON + "  ", null).reason());
    }

    /**
     * El mínimo se mide DESPUÉS del recorte. Medido en crudo, "corta     " son diez caracteres y
     * cinco de contenido, y la bitácora quedaría con un motivo que no explica nada.
     */
    @Test
    void toAddServiceResourcesCommand_withAPaddedShortReason_rejects() {
        assertTrue(messageOf(() -> reinforcement(4, null, null, "corta     ", null))
            .contains("al menos 10 caracteres"));
    }

    @Test
    void toAddServiceResourcesCommand_withAReasonOfSpaces_rejects() {
        assertTrue(messageOf(() -> reinforcement(4, null, null, "            ", null))
            .contains("al menos 10 caracteres"));
    }

    /** El NUL sobrevive al recorte y al mínimo, y PostgreSQL no lo admite en un texto. */
    @Test
    void toAddServiceResourcesCommand_withANulInTheReason_rejects() {
        assertTrue(messageOf(() -> reinforcement(4, null, null, "Relevo\u0000 reglamentario", null))
            .contains("no se pueden guardar"));
    }

    /**
     * El motivo se mira ANTES que los recursos: un cuerpo con los dos problemas contesta por el
     * campo que el usuario acaba de escribir, no por los combos.
     */
    @Test
    void toAddServiceResourcesCommand_withBothProblems_rejectsByTheReason() {
        assertTrue(messageOf(() -> reinforcement(null, null, null, "corta", null))
            .contains("al menos 10 caracteres"));
    }

    /** Ausente, null y cualquier grafía de "false" significan lo mismo: NO forzar. */
    @Test
    void toAddServiceResourcesCommand_parsesForce() {
        assertFalse(reinforcement(4, null, null, VALID_REASON, null).force());
        assertFalse(reinforcement(4, null, null, VALID_REASON, "false").force());
        assertFalse(reinforcement(4, null, null, VALID_REASON, "FALSE").force());
        assertTrue(reinforcement(4, null, null, VALID_REASON, "true").force());
        assertTrue(reinforcement(4, null, null, VALID_REASON, "TRUE").force());
    }

    @Test
    void toAddServiceResourcesCommand_withAForceThatIsNotABoolean_rejects() {
        assertTrue(messageOf(() -> reinforcement(4, null, null, VALID_REASON, "maybe"))
            .contains("true o false"));
    }
}
