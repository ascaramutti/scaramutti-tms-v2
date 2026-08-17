package com.scaramutti.tms.operations.util;

import com.scaramutti.tms.shared.exception.CommonError;
import com.scaramutti.tms.shared.util.MultiWordSearch;
import com.scaramutti.tms.shared.util.StringUtils;
import org.mapstruct.Named;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Traduccion de la entrada CRUDA de la API de operaciones a valores utilizables, con el rechazo
 * que corresponde cuando no lo es.
 *
 * <p>Vive fuera del mapper porque siendo este una interfaz, TODO campo suyo es implicitamente
 * publico, y un test puede afirmarse contra la constante en vez de contra el valor esperado (pasaba:
 * los bordes de la ventana de negocio se comparaban contra las constantes que definen esa misma
 * ventana). Siendo una clase, las CONSTANTES que nadie consume de afuera se pueden cerrar, y abajo
 * estan cerradas, con lo cual contra esas la tautologia ya no se puede escribir. Las cuatro que
 * quedan abiertas son las que el mapper usa, asi que contra ELLAS sigue siendo posible: hoy las
 * pruebas de paginacion escriben 0, 20, 100 y 1 a mano, y conviene que siga asi.
 *
 * <p>Los METODOS de la API de parseo, en cambio, son publicos y no podrian no serlo: el mapper
 * vive en otro paquete. Ninguno permite saltear nada (validan o normalizan), asi que la apertura no
 * agrega superficie util a nadie. Los auxiliares que nadie llama de afuera si quedan privados.
 *
 * <p>El segundo motivo, mas chico, es tener el parseo de fechas y la ventana de negocio disponibles
 * sin arrastrar el mapeo de los cinco cuerpos que el mapper tambien atiende. Todavia no hay un
 * segundo consumidor: el reporte va a serlo.
 *
 * <p>Nada de lo que hay aca conoce un DTO, un tipo del dominio ni la forma de una ruta: lo que
 * depende del cuerpo que se esta validando (el estado, la transicion, el motivo, la justificacion)
 * se queda en el mapper, y tambien {@code toServiceId}, que traduce un segmento de la URL y por eso
 * decide su propio mensaje de error.
 *
 * <p>⚠️ Todo metodo publico de UN solo argumento lleva {@code @Named}. Sin el, MapStruct lo trata
 * como candidato AUTOMATICO para cualquier mapeo sin anotar de esa firma en los mappers que
 * declaren esta clase en {@code uses}: {@code parseSearch} es {@code String -> String} y pisaria
 * todo campo de texto que hoy pasa tal cual. Es el mismo defecto que ya documenta
 * {@code StringUtils.escapeLikeWildcards}, y alli fue un bug real.
 */
public final class ServiceRequestParsing {

    private ServiceRequestParsing() {
        // utility class
    }

    // ---------- Termino de busqueda --------------------------------------------

    /** Mínimo de caracteres del término y de cada palabra, como declara el contrato. */
    private static final int MIN_SEARCH_LENGTH = 3;

    /** Tope de caracteres del término, como declara el contrato. */
    private static final int MAX_SEARCH_LENGTH = 200;

    /** Tope de palabras de la búsqueda: cada una multiplica las condiciones de la consulta. */
    private static final int MAX_SEARCH_TOKENS = 8;

    /** Los mismos que {@code \s} de la expresion con la que se parte el termino en palabras. */
    private static final String WORD_SEPARATORS = " \t\n\u000B\f\r";

    // ---------- Paginacion ------------------------------------------------------

    public static final int MAX_PAGE_SIZE = 100;
    public static final int DEFAULT_PAGE = 0;
    public static final int DEFAULT_PAGE_SIZE = 20;

    // ---------- Fechas ----------------------------------------------------------

    /**
     * Ventana admisible de CUALQUIER fecha del viaje, venga por filtro o por cuerpo. El formato
     * ISO acepta años de hasta nueve cifras y las columnas de fecha de PostgreSQL no: un valor
     * por fuera de su rango llega hasta el motor y revienta con un 500 donde el contrato promete
     * un 400. Los bordes son de NEGOCIO, no del motor (el tope real esta miles de años mas
     * arriba): ninguna fecha util de un viaje cae fuera de esto, asi que un valor asi siempre es
     * un error de quien llama.
     */
    private static final LocalDate MIN_BUSINESS_DATE = LocalDate.of(1900, 1, 1);

    private static final LocalDate MAX_BUSINESS_DATE = LocalDate.of(2999, 12, 31);

    // ---------- Otros -----------------------------------------------------------

    /** Cifras arabigas: {@code Integer.valueOf} acepta las de cualquier alfabeto. */
    public static final Pattern ASCII_INTEGER = Pattern.compile("-?[0-9]+");

    // ---------- Textos ----------------------------------------------------------

    /**
     * PostgreSQL no admite el byte NUL dentro de un texto: llega hasta el motor y revienta la
     * sentencia con un 500 donde el contrato promete un 400. Sobrevive a todo lo demas —no lo
     * saca el recorte, no lo tapa el minimo de largo y para Java es un caracter mas—, asi que se
     * rechaza explicitamente.
     *
     * <p>En un texto MULTILINEA (observaciones, justificacion) se rechaza solo el NUL: un salto de
     * linea o una tabulacion ahi son legitimos y la columna los guarda sin problema.
     */
    public static void requireStorableText(String value, String what) {
        if (value != null && value.indexOf('\0') >= 0) {
            throw CommonError.VALIDATION_FAILED.toException(
                what + " tiene caracteres que no se pueden guardar");
        }
    }

    /**
     * Un texto de UNA LINEA (el origen, el destino) rechaza ademas cualquier caracter de control.
     *
     * <p>No es cosmetico: esos dos campos se vuelcan al log de la aplicacion —la guarda anti
     * doble-click del ALTA los registra— y el formato del log es una linea por evento. Un salto de
     * linea en el medio del origen deja escrita una linea entera con el formato del servidor,
     * inventando un evento que nunca ocurrio. Es el mismo defecto que la bitacora cierra
     * aplastando los saltos, entrando por la otra puerta. La edicion aplica la misma regla por
     * consistencia: es el mismo campo y el mismo dato, y dos varas para el mismo texto segun por
     * donde entre es como estas reglas se pudren.
     *
     * <p>Se mide sobre el texto YA RECORTADO: al log llega el recortado, asi que un salto al final
     * —lo que deja pegar desde una planilla— no inventa ninguna linea, y rechazarlo seria endurecer
     * mas de lo que el motivo pide (y de forma asimetrica con el espacio, que si se tolera).
     * Un nombre de lugar no tiene saltos ni tabulaciones en el medio, asi que la regla no le quita
     * nada a nadie.
     */
    public static void requireSingleLineText(String value, String what) {
        requireStorableText(value, what);
        if (value != null
                && value.chars().anyMatch(ServiceRequestParsing::isControlOrLineSeparator)) {
            throw CommonError.VALIDATION_FAILED.toException(
                what + " no puede tener saltos de línea ni caracteres de control");
        }
    }

    /**
     * Control ISO, mas los dos separadores de linea de Unicode que quedan afuera de esa definicion
     * y que el aplastado de la bitacora ({@code \R}) si trata como salto. Sin ellos, la guarda
     * dejaria pasar dos caracteres que son literalmente lo que dice rechazar.
     */
    private static boolean isControlOrLineSeparator(int codePoint) {
        return Character.isISOControl(codePoint) || codePoint == 0x2028 || codePoint == 0x2029;
    }

    /**
     * Normaliza el termino de busqueda y RECIEN AHI lo mide, para que el rechazo salga con el
     * mensaje que corresponde: medido en crudo, un termino de una letra con espacios de relleno
     * tambien termina en 400, pero en el de "ninguna palabra util", que describe otra regla.
     *
     * <p>Lo que de verdad acota el costo de la consulta es el filtro de ABAJO: las palabras de
     * menos de {@link #MIN_SEARCH_LENGTH} caracteres se descartan (un "de" no acota nada y si
     * multiplica el costo); si no queda ninguna util, es un 400 en vez de un listado completo
     * disfrazado de resultado de busqueda.
     *
     * <p>Los caracteres de control que no separan palabras se rechazan aparte: un NUL incrustado
     * sobrevive al trim y a la division en palabras, y PostgreSQL no admite ese byte en un texto,
     * asi que llegaria hasta el motor a reventar la consulta con un 500 donde el contrato promete
     * un 400.
     *
     * <p>El {@code @Named} no lo invoca ningun {@code qualifiedByName}: esta como ESCUDO, para
     * que MapStruct no lo elija solo para cualquier campo {@code String -> String}, que es el
     * caso mas peligroso de los tres. No borrarlo por parecer huerfano.
     */
    @Named("parseSearch")
    public static String parseSearch(String q) {
        String normalized = StringUtils.trimToNull(q);
        if (normalized == null) {
            return null;
        }
        // Se aceptan los que el partidor de palabras trata como separador (salto de linea,
        // tabulacion y compañia). NO alcanza con preguntar por "espacio en blanco": Java cuenta
        // como tales cuatro controles que el partidor NO separa, y esos viajarian dentro del
        // termino hasta la consulta.
        if (normalized.chars()
                .anyMatch(c -> Character.isISOControl(c) && WORD_SEPARATORS.indexOf(c) < 0)) {
            throw CommonError.VALIDATION_FAILED.toException(
                "La búsqueda tiene caracteres no válidos");
        }
        if (normalized.length() < MIN_SEARCH_LENGTH) {
            throw CommonError.VALIDATION_FAILED.toException(
                "La búsqueda necesita al menos " + MIN_SEARCH_LENGTH + " caracteres");
        }
        if (normalized.length() > MAX_SEARCH_LENGTH) {
            throw CommonError.VALIDATION_FAILED.toException(
                "La búsqueda admite hasta " + MAX_SEARCH_LENGTH + " caracteres");
        }
        List<String> tokens = Arrays.stream(MultiWordSearch.tokenize(normalized))
            .filter(token -> token.length() >= MIN_SEARCH_LENGTH)
            .toList();
        if (tokens.isEmpty()) {
            throw CommonError.VALIDATION_FAILED.toException(
                "La búsqueda necesita al menos una palabra de " + MIN_SEARCH_LENGTH + " caracteres");
        }
        if (tokens.size() > MAX_SEARCH_TOKENS) {
            throw CommonError.VALIDATION_FAILED.toException(
                "La búsqueda admite hasta " + MAX_SEARCH_TOKENS + " palabras");
        }
        return String.join(" ", tokens);
    }

    // ---------- Numeros ---------------------------------------------------------

    public static Integer parseInteger(String value, String field) {
        String normalized = StringUtils.trimToNull(value);
        if (normalized == null) {
            return null;
        }
        // Integer.valueOf acepta cifras de CUALQUIER alfabeto: un "٥" arabigo o un "５" de ancho
        // completo entran como 5. El contrato declara un entero, no cualquier grafia de un 5.
        if (!ASCII_INTEGER.matcher(normalized).matches()) {
            throw CommonError.VALIDATION_FAILED.toException(
                "El filtro " + field + " tiene que ser un número entero");
        }
        try {
            return Integer.valueOf(normalized);
        } catch (NumberFormatException e) {
            throw CommonError.VALIDATION_FAILED.toException(
                "El filtro " + field + " tiene que ser un número entero");
        }
    }

    /** Entero de paginacion: ausente o vacio toma el valor por defecto; fuera de rango es 400. */
    public static int parseRangedInt(String value, String field, int fallback, int min, int max) {
        Integer parsed = parseInteger(value, field);
        if (parsed == null) {
            return fallback;
        }
        if (parsed < min) {
            throw CommonError.VALIDATION_FAILED.toException(
                "El filtro " + field + " no puede ser menor que " + min);
        }
        if (parsed > max) {
            throw CommonError.VALIDATION_FAILED.toException(
                "El filtro " + field + " no puede ser mayor que " + max);
        }
        return parsed;
    }

    // ---------- Fechas ----------------------------------------------------------

    public static LocalDate parseDate(String value, String field) {
        String normalized = StringUtils.trimToNull(value);
        if (normalized == null) {
            return null;
        }
        LocalDate parsed;
        try {
            parsed = LocalDate.parse(normalized);
        } catch (DateTimeParseException e) {
            throw CommonError.VALIDATION_FAILED.toException(
                "El filtro " + field + " tiene que ser una fecha con formato AAAA-MM-DD");
        }
        if (parsed.isBefore(MIN_BUSINESS_DATE) || parsed.isAfter(MAX_BUSINESS_DATE)) {
            throw CommonError.VALIDATION_FAILED.toException(
                "El filtro " + field + " tiene que estar entre "
                    + MIN_BUSINESS_DATE + " y " + MAX_BUSINESS_DATE);
        }
        return parsed;
    }

    /**
     * La marca de tiempo, de texto a objeto. Se recibe como texto por el mismo motivo que el id de
     * ruta y los filtros, y este quedo MEDIDO: con el campo declarado como {@code OffsetDateTime},
     * un "ayer" sale con un 400 cuyo cuerpo no es el Problem que el contrato promete, con
     * content-type comun y filtrando el nombre de la clase, la linea y la columna donde el parser
     * se trabo.
     *
     * <p>El {@code @Named} no lo invoca ningun {@code qualifiedByName}: esta como ESCUDO, para que
     * MapStruct no lo elija solo para cualquier campo {@code String -> OffsetDateTime}. No borrarlo
     * por parecer huerfano; lo mismo vale para el de {@code parseSearch}.
     */
    @Named("parseDateTime")
    public static OffsetDateTime parseDateTime(String value) {
        String normalized = StringUtils.trimToNull(value);
        if (normalized == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(normalized);
        } catch (DateTimeParseException e) {
            throw CommonError.VALIDATION_FAILED.toException(
                "La fecha tiene que venir con formato AAAA-MM-DDTHH:MM:SSZ");
        }
    }

    /**
     * Misma ventana que los filtros, aplicada a una fecha que llega YA TIPADA en el cuerpo. El
     * tipado no protege de nada acá: el lector de JSON acepta el año de nueve cifras del formato
     * ISO y lo entrega como una fecha perfectamente valida para Java, que recien revienta contra
     * la columna.
     */
    public static void requireDateWithinBusinessWindow(LocalDate date, String what) {
        if (date == null) {
            return;
        }
        if (date.isBefore(MIN_BUSINESS_DATE) || date.isAfter(MAX_BUSINESS_DATE)) {
            throw CommonError.VALIDATION_FAILED.toException(
                what + " tiene que estar entre " + MIN_BUSINESS_DATE + " y " + MAX_BUSINESS_DATE);
        }
    }

    /**
     * Hermano del anterior para las marcas de tiempo. Se mide DOS veces, y las dos hacen falta.
     *
     * <p>Primero tal como vino: convertir de huso una marca pegada al tope del tipo lo desborda y
     * revienta con un error de fecha que nadie mapea, o sea un 500 justo en la clase de entrada
     * que esta ventana existe para atajar. Midiendo antes, esos valores nunca llegan a
     * convertirse.
     *
     * <p>Y despues en UTC, que es el huso en el que la marca se va a GUARDAR: las 23:00 del ultimo
     * dia de la ventana escritas con catorce horas de atraso caen, en UTC, en el primer dia de
     * afuera. El chequeo y el valor que protege tienen que mirar el mismo marco.
     */
    public static void requireDateTimeWithinBusinessWindow(OffsetDateTime dateTime, String what) {
        if (dateTime == null) {
            return;
        }
        requireDateWithinBusinessWindow(dateTime.toLocalDate(), what);
        requireDateWithinBusinessWindow(
            dateTime.withOffsetSameInstant(ZoneOffset.UTC).toLocalDate(), what);
    }

    // ---------- Banderas --------------------------------------------------------

    /**
     * El indicador de forzado, de texto a booleano. Mismo motivo que la marca de tiempo:
     * declarado como {@code Boolean}, un valor que no parsea lo rechaza el lector de JSON antes de
     * que corra una linea nuestra, y la respuesta sale con un cuerpo que no es el Problem que el
     * contrato promete, filtrando internos del parser.
     *
     * <p>La tecnica cubre los ESCALARES, que es de donde viene el trafico real: Jackson convierte
     * un booleano o un numero JSON a texto y el valor llega hasta aca. Un objeto o un arreglo
     * siguen cayendo en el lector, y eso se cierra de una vez para toda la API con un manejador de
     * los errores de deserializacion, que este proyecto todavia no tiene. Hay un caso que fija
     * donde termina la tecnica, para que el limite este medido y no solo escrito.
     *
     * <p>Ausente, null y cualquier forma de "false" significan lo mismo: NO forzar. El default
     * tiene que ser ese y no "vino la clave": un formulario que serializa el objeto entero manda
     * el campo siempre, y con el default invertido forzaria sin que nadie lo haya pedido.
     */
    @Named("parseForce")
    public static boolean parseForce(String value) {
        String normalized = StringUtils.trimToNull(value);
        if (normalized == null || "false".equalsIgnoreCase(normalized)) {
            return false;
        }
        if ("true".equalsIgnoreCase(normalized)) {
            return true;
        }
        throw CommonError.VALIDATION_FAILED.toException(
            "El indicador de forzado tiene que ser true o false");
    }
}
