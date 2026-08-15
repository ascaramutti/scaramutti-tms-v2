package com.scaramutti.tms.operations.mapper;

import com.scaramutti.tms.operations.dto.ServiceAssignResourcesRequest;
import com.scaramutti.tms.operations.dto.ServiceCreateRequest;
import com.scaramutti.tms.operations.dto.ServiceStatusChangeRequest;
import com.scaramutti.tms.operations.dto.ServiceUpdateRequest;
import com.scaramutti.tms.operations.service.ServiceLogText;
import com.scaramutti.tms.operations.model.ServiceStatus;
import com.scaramutti.tms.operations.model.ServiceStatusTransition;
import com.scaramutti.tms.operations.service.cmd.AssignServiceResourcesCommand;
import com.scaramutti.tms.operations.service.cmd.ChangeServiceStatusCommand;
import com.scaramutti.tms.operations.service.cmd.CreateServiceCommand;
import com.scaramutti.tms.operations.service.cmd.ListServicesQuery;
import com.scaramutti.tms.operations.service.cmd.UpdateServiceCommand;
import com.scaramutti.tms.shared.exception.CommonError;
import com.scaramutti.tms.shared.mapper.SharedMapperConfig;
import com.scaramutti.tms.shared.util.MultiWordSearch;
import com.scaramutti.tms.shared.util.StringUtils;
import org.mapstruct.BeforeMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.regex.Pattern;
import java.util.Arrays;
import java.util.stream.Collectors;
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

    /**
     * Alta: valida el formato de los textos y la ventana de fechas ANTES de armar el command
     * (el recorte lo hace el mapeo de abajo, no este metodo).
     *
     * <p>La ventana se chequea aca y no en el service porque es una regla del formato de entrada,
     * igual que en los filtros del listado: una fecha fuera de rango no es un caso de negocio que
     * el service tenga que contemplar, es un valor que nunca debio entrar.
     */
    default CreateServiceCommand toCreateServiceCommand(ServiceCreateRequest serviceCreateRequest) {
        requireDateWithinBusinessWindow(serviceCreateRequest.tentativeDate(), "La fecha tentativa");
        requireSingleLineText(StringUtils.trimToNull(serviceCreateRequest.origin()), "El origen");
        requireSingleLineText(
            StringUtils.trimToNull(serviceCreateRequest.destination()), "El destino");
        requireStorableText(serviceCreateRequest.observations(), "Las observaciones");
        return toCreateServiceCommandFields(serviceCreateRequest);
    }

    /**
     * SOLO para el metodo de arriba: es el mapeo puro, sin ninguna de las guardas de arriba. Queda visible
     * porque MapStruct no genera metodos privados; llamarlo directo se saltea la validacion.
     */
    @Mapping(target = "origin", source = "origin", qualifiedByName = "trimToNull")
    @Mapping(target = "destination", source = "destination", qualifiedByName = "trimToNull")
    @Mapping(target = "observations", source = "observations", qualifiedByName = "trimToNull")
    CreateServiceCommand toCreateServiceCommandFields(ServiceCreateRequest serviceCreateRequest);

    /**
     * Edicion. El command se arma a mano en vez de mapearse campo a campo: son quince argumentos
     * de tres origenes distintos (la ruta, un header y el cuerpo) mas las guardas de entrada en el
     * medio, y escrito asi se lee de corrido.
     *
     * <p>Las fechas reales NO se normalizan a UTC aca: eso lo hace el service, que es quien sabe
     * si la fecha se aplica o se descarta por "sin cambio".
     */
    default UpdateServiceCommand toUpdateServiceCommand(
            long serviceId, String ifMatch, ServiceUpdateRequest request) {
        requireDateWithinBusinessWindow(request.tentativeDate(), "La fecha tentativa");
        requireDateTimeWithinBusinessWindow(request.startDateTime(), "La fecha de inicio real");
        requireDateTimeWithinBusinessWindow(request.endDateTime(), "La fecha de fin real");
        requireSingleLineText(StringUtils.trimToNull(request.origin()), "El origen");
        requireSingleLineText(StringUtils.trimToNull(request.destination()), "El destino");
        requireStorableText(request.observations(), "Las observaciones");
        requireStorableText(request.justification(), "La justificación");
        return new UpdateServiceCommand(
            serviceId,
            ifMatch,
            request.tentativeDate(),
            StringUtils.trimToNull(request.origin()),
            StringUtils.trimToNull(request.destination()),
            request.weightKg(),
            request.lengthM(),
            request.widthM(),
            request.heightM(),
            request.price(),
            request.currencyId(),
            StringUtils.trimToNull(request.observations()),
            request.startDateTime(),
            request.endDateTime(),
            requireJustification(request.justification()));
    }

    /**
     * Transicion de estado. Se arma a mano por lo mismo que la edicion: los argumentos vienen de
     * la ruta, de un header y del cuerpo, y hay tres guardas en el medio que dependen entre si
     * (que se puede pedir, si la fecha aplica, si el motivo es obligatorio). Ninguna de las tres
     * se puede declarar en el esquema: todas dependen del {@code target}, que es un dato del
     * mismo cuerpo que estan validando.
     */
    default ChangeServiceStatusCommand toChangeServiceStatusCommand(
            long serviceId, String ifMatch, ServiceStatusChangeRequest serviceStatusChangeRequest) {
        ServiceStatusTransition transition = parseTransition(serviceStatusChangeRequest.target());
        OffsetDateTime dateTime = parseDateTime(serviceStatusChangeRequest.dateTime());
        requireDateTimeWithinBusinessWindow(dateTime, "La fecha de la transición");
        requireDateTimeApplies(transition, dateTime);
        requireStorableText(serviceStatusChangeRequest.note(), noteSubject(transition));
        boolean force = parseForce(serviceStatusChangeRequest.force());
        requireForceApplies(transition, force);
        return new ChangeServiceStatusCommand(
            serviceId,
            ifMatch,
            transition,
            dateTime,
            resolveNote(transition, serviceStatusChangeRequest.note()),
            force);
    }

    /**
     * El destino, de texto a la tabla de transiciones. Se recibe como texto por lo mismo que el id
     * de ruta y los filtros del listado: un campo tipado que no parsea lo rechaza el lector de
     * JSON antes de que corra una linea nuestra, y no hay ningun manejador de ese error, asi que
     * la respuesta saldria con un cuerpo que no es el Problem que el contrato promete.
     *
     * <p>El mensaje enumera los valores validos. Un "no es valido" a secas obliga a ir a buscar el
     * contrato para descubrir cual de las cinco palabras estaba mal escrita.
     */
    private static ServiceStatusTransition parseTransition(String target) {
        String normalized = StringUtils.trimToNull(target);
        if (normalized != null) {
            try {
                return ServiceStatusTransition.valueOf(normalized);
            } catch (IllegalArgumentException ignored) {
                // Cae al rechazo de abajo. El valor AUSENTE o en blanco no llega hasta aca: lo
                // corta @NotBlank, que contesta el 400 con el arreglo `errors` en vez de este
                // mensaje. Los dos son 400 COM-001 y esa diferencia de forma esta medida.
            }
        }
        throw CommonError.VALIDATION_FAILED.toException(
            "El estado pedido tiene que ser uno de: "
                + Arrays.stream(ServiceStatusTransition.values())
                    .map(Enum::name).collect(Collectors.joining(", ")));
    }

    /**
     * La marca de tiempo, de texto a objeto. Mismo motivo que el destino y que el id de ruta, y
     * este quedo MEDIDO: con el campo declarado como {@code OffsetDateTime}, un "ayer" sale con
     * un 400 cuyo cuerpo no es el Problem que el contrato promete, con content-type comun y
     * filtrando el nombre de la clase, la linea y la columna donde el parser se trabo.
     */
    private static OffsetDateTime parseDateTime(String value) {
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
     * El indicador de forzado, de texto a booleano. Mismo motivo que el destino y la marca de
     * tiempo: declarado como {@code Boolean}, un valor que no parsea lo rechaza el lector de JSON
     * antes de que corra una linea nuestra, y la respuesta sale con un cuerpo que no es el Problem
     * que el contrato promete, filtrando internos del parser.
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
    static boolean parseForce(String value) {
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

    /**
     * Solo la reapertura mira conflictos, asi que forzar en las otras cuatro es un 400 y no un dato
     * que se descarte en silencio. Mismo criterio que la fecha que no aplica, y por el mismo
     * motivo, aunque el argumento no sea identico: una fecha aceptada y descartada miente sobre lo
     * que quedo GUARDADO, y esto no guarda nada. Lo que pesa aca es que es la unica bandera que
     * autoriza pisar la reja de conflictos, o sea a poner dos viajes activos sobre el mismo
     * conductor: "aceptado e ignorado" es el peor default posible para ese campo el dia que una
     * segunda transicion se vuelva forzable.
     *
     * <p>Muerde solo sobre true. Ausente, null y false son "no forzar", que es lo que manda un
     * formulario que serializa el objeto entero.
     *
     * <p>Reabrir hacia un estado que NO retiene recursos tampoco usa la bandera, pero eso no se
     * puede decidir aca: el destino sale del rastro y todavia no se leyo. Ahi sigue siendo no-op.
     */
    private static void requireForceApplies(ServiceStatusTransition transition, boolean force) {
        if (force && !transition.restoresPreviousStatus()) {
            throw CommonError.VALIDATION_FAILED.toException(
                "Forzar solo aplica al reabrir");
        }
    }

    /**
     * Cancelar, eliminar y reabrir no fechan nada, asi que mandarles una fecha es un 400 y no un
     * dato que se descarte en silencio.
     *
     * <p>Aceptar un valor para ignorarlo deja un endpoint que miente sobre lo que guardo: el
     * cliente recibe 200, cree que la marca quedo escrita y solo se entera releyendo el detalle.
     * Es el mismo criterio con el que el modulo ya rechaza el precio que no puede ver quien lo
     * manda, en vez de descartarlo.
     *
     * <p>Muerde sobre el VALOR y no sobre la presencia de la clave: un formulario que serializa el
     * objeto entero manda {@code null} en los campos que no aplican y tiene que seguir andando.
     */
    private static void requireDateTimeApplies(
            ServiceStatusTransition transition, OffsetDateTime dateTime) {
        if (dateTime != null
                && transition.dateColumn() == ServiceStatusTransition.DateColumn.NONE) {
            throw CommonError.VALIDATION_FAILED.toException(
                "La fecha solo aplica al iniciar o al finalizar");
        }
    }

    /**
     * El texto libre, recortado, y medido contra el minimo SOLO cuando la transicion lo exige.
     *
     * <p>El minimo se mide DESPUES del recorte por el mismo motivo que la justificacion de la
     * edicion: {@code "corta     "} son diez caracteres y cinco de contenido. Un minimo que se
     * cumple con espacios no es un minimo, es un campo obligatorio que se puede saltear.
     */
    private static String resolveNote(ServiceStatusTransition transition, String note) {
        String normalized = StringUtils.trimToNull(note);
        if (transition.requiresNote()
                && (normalized == null
                    || normalized.length() < ServiceStatusChangeRequest.MIN_NOTE_LENGTH)) {
            throw CommonError.VALIDATION_FAILED.toException(
                noteSubject(transition) + " necesita al menos "
                    + ServiceStatusChangeRequest.MIN_NOTE_LENGTH + " caracteres");
        }
        return normalized;
    }

    /** Como se NOMBRA el texto libre en los mensajes de error, segun lo que significa. */
    private static String noteSubject(ServiceStatusTransition transition) {
        return transition.requiresNote() ? "El motivo" : "La nota";
    }

    /**
     * Guarda de entrada de la asignacion. Corre ANTES del mapeo de abajo porque MapStruct invoca
     * los metodos {@code @BeforeMapping} que aceptan el tipo de origen al entrar al metodo
     * generado. Escrita asi y no dentro de un metodo a mano, la validacion no se puede saltear:
     * no queda ningun mapeo publico "puro" al que llamar de costado.
     *
     * <p>La nota es MULTILINEA (como las observaciones y la justificacion): solo se le rechaza el
     * byte NUL. Un salto de linea es legitimo y la bitacora lo aplasta al escribirlo, asi que no
     * puede plantar lineas falsas.
     */
    @BeforeMapping
    default void requireStorableAssignmentNote(
            ServiceAssignResourcesRequest serviceAssignResourcesRequest) {
        requireStorableText(serviceAssignResourcesRequest.note(), "La nota");
    }

    /**
     * Asignacion de recursos. El id sale de la ruta y el resto del cuerpo; los tres ids de recurso
     * se mapean por nombre.
     *
     * <p>{@code force} llega como TEXTO y se resuelve con {@code parseForce}, igual que en la
     * transicion: declarado como {@code Boolean}, un valor que no parsea lo rechaza el lector de
     * JSON con un cuerpo que no es el Problem que el contrato promete. El mapeo es EXPLICITO
     * porque MapStruct convierte texto a booleano por su cuenta, y esa conversion trata cualquier
     * cosa que no sea "true" como false en silencio.
     */
    @Mapping(target = "serviceId", source = "serviceId")
    @Mapping(target = "note", source = "serviceAssignResourcesRequest.note",
        qualifiedByName = "trimToNull")
    @Mapping(target = "force", source = "serviceAssignResourcesRequest.force",
        qualifiedByName = "parseForce")
    AssignServiceResourcesCommand toAssignServiceResourcesCommand(
        long serviceId, ServiceAssignResourcesRequest serviceAssignResourcesRequest);

    /**
     * PostgreSQL no admite el byte NUL dentro de un texto: llega hasta el motor y revienta la
     * sentencia con un 500 donde el contrato promete un 400. Sobrevive a todo lo demas —no lo
     * saca el recorte, no lo tapa el minimo de largo y para Java es un caracter mas—, asi que se
     * rechaza explicitamente.
     *
     * <p>En un texto MULTILINEA (observaciones, justificacion) se rechaza solo el NUL: un salto de
     * linea o una tabulacion ahi son legitimos y la columna los guarda sin problema.
     */
    private static void requireStorableText(String value, String what) {
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
    /**
     * Control ISO, mas los dos separadores de linea de Unicode que quedan afuera de esa definicion
     * y que el aplastado de la bitacora ({@code \R}) si trata como salto. Sin ellos, la guarda
     * dejaria pasar dos caracteres que son literalmente lo que dice rechazar.
     */
    private static boolean isControlOrLineSeparator(int codePoint) {
        return Character.isISOControl(codePoint) || codePoint == 0x2028 || codePoint == 0x2029;
    }

    private static void requireSingleLineText(String value, String what) {
        requireStorableText(value, what);
        if (value != null && value.chars().anyMatch(ServiceResourceMapper::isControlOrLineSeparator)) {
            throw CommonError.VALIDATION_FAILED.toException(
                what + " no puede tener saltos de línea ni caracteres de control");
        }
    }

    /**
     * La justificacion se mide DESPUES de recortarla. El minimo declarativo cuenta el texto CRUDO,
     * asi que un texto corto rellenado con espacios hasta llegar al largo pedido lo pasa entero
     * ({@code "corta     "} son diez caracteres y cinco de contenido) y dejaria la bitacora con
     * una justificacion que no justifica nada. El texto de PUROS espacios lo tapa antes la
     * anotacion de "no en blanco"; el rellenado, no.
     */
    private static String requireJustification(String justification) {
        String normalized = StringUtils.trimToNull(justification);
        if (normalized == null || normalized.length() < ServiceUpdateRequest.MIN_JUSTIFICATION_LENGTH) {
            throw CommonError.VALIDATION_FAILED.toException(
                "La justificación necesita al menos " + ServiceUpdateRequest.MIN_JUSTIFICATION_LENGTH
                    + " caracteres");
        }
        return normalized;
    }

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
     * Ventana admisible de CUALQUIER fecha del viaje, venga por filtro o por cuerpo. El formato
     * ISO acepta años de hasta nueve cifras y las columnas de fecha de PostgreSQL no: un valor
     * por fuera de su rango llega hasta el motor y revienta con un 500 donde el contrato promete
     * un 400. Los bordes son de NEGOCIO, no del motor (el tope real esta miles de años mas
     * arriba): ninguna fecha util de un viaje cae fuera de esto, asi que un valor asi siempre es
     * un error de quien llama.
     */
    LocalDate MIN_BUSINESS_DATE = LocalDate.of(1900, 1, 1);

    LocalDate MAX_BUSINESS_DATE = LocalDate.of(2999, 12, 31);

    // ---------- Otros -----------------------------------------------------------

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
     * El id del viaje, de texto a numero. Se recibe como texto por el mismo motivo que los
     * filtros: declarado con su tipo, un id que no parsea termina en un 404 SIN CUERPO del
     * framework, indistinguible del 404 legitimo de "ese viaje no existe" y sin decir por que.
     *
     * <p>Un id valido pero inexistente sigue siendo 404: eso lo resuelve la busqueda, no esto.
     */
    default long toServiceId(String id) {
        // Se valida el valor CRUDO, sin recortar: el recorte de Java se come todo lo que este por
        // debajo del espacio, con lo cual "/services/1", "/services/ 1" y hasta un id con un NUL
        // pegado serian la misma direccion. El endpoint hermano ya rechaza los controles en la
        // busqueda; el mismo criterio vale aca.
        if (id == null || !ASCII_INTEGER.matcher(id).matches()) {
            throw CommonError.VALIDATION_FAILED.toException(
                "El id del servicio tiene que ser un número entero");
        }
        try {
            return Long.parseLong(id);
        } catch (NumberFormatException e) {
            throw CommonError.VALIDATION_FAILED.toException(
                "El id del servicio está fuera de rango");
        }
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
                "El estado indicado no existe: " + ServiceLogText.abbreviate(normalized));
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
        if (parsed.isBefore(MIN_BUSINESS_DATE) || parsed.isAfter(MAX_BUSINESS_DATE)) {
            throw CommonError.VALIDATION_FAILED.toException(
                "El filtro " + field + " tiene que estar entre "
                    + MIN_BUSINESS_DATE + " y " + MAX_BUSINESS_DATE);
        }
        return parsed;
    }

    /**
     * Misma ventana que los filtros, aplicada a una fecha que llega YA TIPADA en el cuerpo. El
     * tipado no protege de nada acá: el lector de JSON acepta el año de nueve cifras del formato
     * ISO y lo entrega como una fecha perfectamente valida para Java, que recien revienta contra
     * la columna.
     */
    private static void requireDateWithinBusinessWindow(LocalDate date, String what) {
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
    private static void requireDateTimeWithinBusinessWindow(OffsetDateTime dateTime, String what) {
        if (dateTime == null) {
            return;
        }
        requireDateWithinBusinessWindow(dateTime.toLocalDate(), what);
        requireDateWithinBusinessWindow(
            dateTime.withOffsetSameInstant(ZoneOffset.UTC).toLocalDate(), what);
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

}
