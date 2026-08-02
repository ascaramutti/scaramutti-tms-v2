package com.scaramutti.tms.operations.mapper;

import com.scaramutti.tms.operations.dto.ServiceCreateRequest;
import com.scaramutti.tms.operations.model.ServiceStatus;
import com.scaramutti.tms.operations.service.cmd.CreateServiceCommand;
import com.scaramutti.tms.operations.service.cmd.ListServicesQuery;
import com.scaramutti.tms.shared.exception.CommonError;
import com.scaramutti.tms.shared.mapper.SharedMapperConfig;
import com.scaramutti.tms.shared.util.MultiWordSearch;
import com.scaramutti.tms.shared.util.StringUtils;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.regex.Pattern;
import java.util.Arrays;
import java.util.List;

/**
 * Mapper de la capa REST del servicio de transporte.
 *
 * <p>Normaliza los textos libres con trim: el origen y el destino porque alimentan la busqueda
 * del listado, la comparacion de la guarda anti doble-click y lo que el usuario ve en el
 * detalle (un espacio de mas los volveria rutas "distintas"), y las observaciones que ademas
 * quedan en null cuando llegan vacias. NO se pasan a mayusculas: son nombres de lugares.
 */
@Mapper(config = SharedMapperConfig.class, uses = StringUtils.class)
public interface ServiceResourceMapper {

    @Mapping(target = "origin", source = "origin", qualifiedByName = "trimToNull")
    @Mapping(target = "destination", source = "destination", qualifiedByName = "trimToNull")
    @Mapping(target = "observations", source = "observations", qualifiedByName = "trimToNull")
    CreateServiceCommand toCreateServiceCommand(ServiceCreateRequest serviceCreateRequest);

    // ---------- Termino de busqueda --------------------------------------------

    /** Mínimo de caracteres del término y de cada palabra, como declara el contrato. */
    int MIN_SEARCH_LENGTH = 3;

    /** Tope de caracteres del término, como declara el contrato. */
    int MAX_SEARCH_LENGTH = 200;

    /** Tope de palabras de la búsqueda: cada una multiplica las condiciones de la consulta. */
    int MAX_SEARCH_TOKENS = 8;

    /** Los mismos que {@code \s} de la expresion con la que se parte el termino en palabras. */
    String WORD_SEPARATORS = " \t\n\u000B\f\r";

    // ---------- Paginacion ------------------------------------------------------

    int MAX_PAGE_SIZE = 100;
    int DEFAULT_PAGE = 0;
    int DEFAULT_PAGE_SIZE = 20;

    // ---------- Fechas ----------------------------------------------------------

    /**
     * Ventana de fechas admisible en los filtros. El formato ISO acepta años de hasta nueve
     * cifras y la columna {@code date} de PostgreSQL no: una fecha por fuera de su rango llega
     * hasta el motor y revienta la consulta con un 500. Los bordes son de NEGOCIO, no del motor
     * (el tope real esta miles de años mas arriba): ninguna fecha util de un viaje cae fuera de
     * esto, asi que un valor asi siempre es un error de quien llama.
     */
    LocalDate MIN_FILTER_DATE = LocalDate.of(1900, 1, 1);

    LocalDate MAX_FILTER_DATE = LocalDate.of(2999, 12, 31);

    // ---------- Otros -----------------------------------------------------------

    /** Tope de caracteres del texto del usuario que se refleja en un mensaje de error. */
    int MAX_ECHOED_CHARS = 30;

    /** Cifras arabigas: {@code Integer.valueOf} acepta las de cualquier alfabeto. */
    Pattern ASCII_INTEGER = Pattern.compile("-?[0-9]+");

    /**
     * Query params del listado a filtros.
     *
     * <p>TODOS llegan como texto y se parsean acá a proposito: declararlos con su tipo hace que
     * un valor invalido (o vacio, que es como un formulario serializa un filtro sin elegir)
     * termine en un 404 sin cuerpo del framework, en vez del 400 con el detalle que promete el
     * contrato.
     */
    default ListServicesQuery toListServicesQuery(String q, String status, String clientId,
            String dateFrom, String dateTo, String page, String size) {
        return new ListServicesQuery(
            parseSearch(q),
            parseStatus(status),
            parseInteger(clientId, "clientId"),
            parseDate(dateFrom, "dateFrom"),
            parseDate(dateTo, "dateTo"),
            parseRangedInt(page, "page", DEFAULT_PAGE, 0, Integer.MAX_VALUE),
            parseRangedInt(size, "size", DEFAULT_PAGE_SIZE, 1, MAX_PAGE_SIZE));
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
     */
    private static String parseSearch(String q) {
        String normalized = StringUtils.trimToNull(q);
        if (normalized == null) {
            return null;
        }
        // Se aceptan los que el partidor de palabras trata como separador (salto de linea,
        // tabulacion y compañia). NO alcanza con preguntar por "espacio en blanco": Java cuenta
        // como tales cuatro controles que el partidor NO separa, y esos viajarian dentro del
        // termino hasta la consulta.
        if (normalized.chars().anyMatch(c -> Character.isISOControl(c) && WORD_SEPARATORS.indexOf(c) < 0)) {
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

    /**
     * El estado es un dominio CERRADO y en mayusculas, igual que en el contrato: no se acepta
     * otra grafia, que es el mismo criterio de aceptacion que el listado de cotizaciones. Lo que
     * si cambia es la RESPUESTA al valor invalido: alla el parametro esta declarado con el tipo
     * del enum y el framework contesta un 404 vacio; aca se recibe como texto justamente para
     * poder contestar el 400 con detalle que promete el contrato.
     */
    private static ServiceStatus parseStatus(String status) {
        String normalized = StringUtils.trimToNull(status);
        if (normalized == null) {
            return null;
        }
        try {
            return ServiceStatus.valueOf(normalized);
        } catch (IllegalArgumentException e) {
            throw CommonError.VALIDATION_FAILED.toException(
                "El estado indicado no existe: " + abbreviate(normalized));
        }
    }

    private static Integer parseInteger(String value, String field) {
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

    private static LocalDate parseDate(String value, String field) {
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
        if (parsed.isBefore(MIN_FILTER_DATE) || parsed.isAfter(MAX_FILTER_DATE)) {
            throw CommonError.VALIDATION_FAILED.toException(
                "El filtro " + field + " tiene que estar entre "
                    + MIN_FILTER_DATE + " y " + MAX_FILTER_DATE);
        }
        return parsed;
    }

    /** Entero de paginacion: ausente o vacio toma el valor por defecto; fuera de rango es 400. */
    private static int parseRangedInt(String value, String field, int fallback, int min, int max) {
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

    /**
     * Recorta el texto del usuario que se devuelve en el error: no hay por que reflejarlo entero.
     *
     * <p>El corte cuenta CARACTERES, no unidades de codigo: un emoji ocupa dos unidades y
     * cortarlo al medio deja media pareja suelta, que ya no es texto valido (un lector estricto
     * rechaza la respuesta y la interfaz muestra un rombo).
     */
    private static String abbreviate(String value) {
        if (value.codePointCount(0, value.length()) <= MAX_ECHOED_CHARS) {
            return value;
        }
        return value.substring(0, value.offsetByCodePoints(0, MAX_ECHOED_CHARS)) + "…";
    }
}
