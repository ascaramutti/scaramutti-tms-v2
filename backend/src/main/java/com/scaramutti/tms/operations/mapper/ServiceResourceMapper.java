package com.scaramutti.tms.operations.mapper;

import com.scaramutti.tms.operations.dto.ServiceAddResourcesRequest;
import com.scaramutti.tms.operations.dto.ServiceAssignResourcesRequest;
import com.scaramutti.tms.operations.dto.ServiceCreateRequest;
import com.scaramutti.tms.operations.dto.ServiceStatusChangeRequest;
import com.scaramutti.tms.operations.dto.ServiceUpdateRequest;
import com.scaramutti.tms.operations.service.ServiceLogText;
import com.scaramutti.tms.operations.model.ServiceStatus;
import com.scaramutti.tms.operations.model.ServiceStatusTransition;
import com.scaramutti.tms.operations.service.cmd.AddServiceResourcesCommand;
import com.scaramutti.tms.operations.service.cmd.AssignServiceResourcesCommand;
import com.scaramutti.tms.operations.service.cmd.ChangeServiceStatusCommand;
import com.scaramutti.tms.operations.service.cmd.CreateServiceCommand;
import com.scaramutti.tms.operations.service.cmd.ListServicesQuery;
import com.scaramutti.tms.operations.service.cmd.UpdateServiceCommand;
import com.scaramutti.tms.operations.util.ServiceRequestParsing;
import com.scaramutti.tms.shared.exception.CommonError;
import com.scaramutti.tms.shared.mapper.SharedMapperConfig;
import com.scaramutti.tms.shared.util.StringUtils;
import org.mapstruct.BeforeMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Mapper de la capa REST del servicio de transporte.
 *
 * <p>Normaliza los textos libres con trim: el origen y el destino porque alimentan la busqueda
 * del listado, la comparacion de la guarda anti doble-click y lo que el usuario ve en el
 * detalle (un espacio de mas los volveria rutas "distintas"), y las observaciones que ademas
 * quedan en null cuando llegan vacias. NO se pasan a mayusculas: son nombres de lugares.
 *
 * <p>El parseo de la entrada cruda (textos guardables, numeros, fechas y su ventana de negocio,
 * termino de busqueda, bandera de forzado) vive en {@link ServiceRequestParsing}. Aca queda lo que
 * conoce los DTOs y el dominio: el estado, la transicion, el motivo, la justificacion y las
 * condiciones entre campos.
 *
 * <p>⚠️ Al agregar un {@code @Mapping} con destino {@code String}, ponerle SIEMPRE su
 * {@code qualifiedByName}. Las clases de {@code uses} aportan sus estaticos al pool de candidatos
 * AUTOMATICOS de MapStruct, y {@code ServiceRequestParsing.parseSearch} es {@code String -> String}:
 * un campo de texto nuevo que se mapee solo por coincidir el nombre lo elegiria y rechazaria con el
 * 400 de la busqueda ("necesita al menos 3 caracteres") un valor que no tiene nada que ver.
 *
 * <p>Lo que de verdad protege es el {@code @Named} de esos metodos, que los saca del pool: con el
 * puesto, un destino sin calificar NO puede elegirlos. Calificar igual todos los destinos es
 * higiene, no la reja. Las dos cosas las sostiene
 * {@code ServiceRequestParsingMapStructPoolTest}, con un mapper canario que tiene justamente un
 * destino {@code String} sin calificar y se pone en rojo si la anotacion desaparece. Es el mismo
 * defecto que ya documenta {@code StringUtils.escapeLikeWildcards}, donde fue un bug real.
 */
@Mapper(config = SharedMapperConfig.class,
    uses = {StringUtils.class, ServiceRequestParsing.class})
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
        ServiceRequestParsing.requireDateWithinBusinessWindow(
            serviceCreateRequest.tentativeDate(), "La fecha tentativa");
        ServiceRequestParsing.requireSingleLineText(
            StringUtils.trimToNull(serviceCreateRequest.origin()), "El origen");
        ServiceRequestParsing.requireSingleLineText(
            StringUtils.trimToNull(serviceCreateRequest.destination()), "El destino");
        ServiceRequestParsing.requireStorableText(
            serviceCreateRequest.observations(), "Las observaciones");
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
        ServiceRequestParsing.requireDateWithinBusinessWindow(
            request.tentativeDate(), "La fecha tentativa");
        ServiceRequestParsing.requireDateTimeWithinBusinessWindow(
            request.startDateTime(), "La fecha de inicio real");
        ServiceRequestParsing.requireDateTimeWithinBusinessWindow(
            request.endDateTime(), "La fecha de fin real");
        ServiceRequestParsing.requireSingleLineText(
            StringUtils.trimToNull(request.origin()), "El origen");
        ServiceRequestParsing.requireSingleLineText(
            StringUtils.trimToNull(request.destination()), "El destino");
        ServiceRequestParsing.requireStorableText(request.observations(), "Las observaciones");
        ServiceRequestParsing.requireStorableText(request.justification(), "La justificación");
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
        OffsetDateTime dateTime =
            ServiceRequestParsing.parseDateTime(serviceStatusChangeRequest.dateTime());
        ServiceRequestParsing.requireDateTimeWithinBusinessWindow(
            dateTime, "La fecha de la transición");
        requireDateTimeApplies(transition, dateTime);
        ServiceRequestParsing.requireStorableText(
            serviceStatusChangeRequest.note(), noteSubject(transition));
        boolean force = ServiceRequestParsing.parseForce(serviceStatusChangeRequest.force());
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
        ServiceRequestParsing.requireStorableText(
            serviceAssignResourcesRequest.note(), "La nota");
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
     * Guarda de entrada de los refuerzos. Corre ANTES del mapeo de abajo, igual que la de la
     * asignacion y por el mismo motivo: escrita asi y no dentro de un metodo a mano, la validacion
     * no se puede saltear, porque no queda ningun mapeo publico "puro" al que llamar de costado.
     *
     * <p>Las TRES van juntas en un solo metodo, y no una por {@code @BeforeMapping}, porque el
     * ORDEN entre ellas es una decision y no un detalle: primero el motivo y despues los recursos.
     * Un cuerpo con los dos problemas contesta por el motivo, que es el campo que el usuario acaba
     * de escribir; empezar por los recursos lo mandaria a revisar los combos por un error que esta
     * en el texto. Repartidas en tres metodos, ese orden quedaria librado a MapStruct.
     */
    @BeforeMapping
    default void requireUsableReinforcement(ServiceAddResourcesRequest serviceAddResourcesRequest) {
        ServiceRequestParsing.requireStorableText(
            serviceAddResourcesRequest.reason(), "El motivo");
        requireReason(serviceAddResourcesRequest.reason());
        requireAtLeastOneResource(serviceAddResourcesRequest);
    }

    /**
     * Refuerzos. El id sale de la ruta y el resto del cuerpo; los tres ids de recurso se mapean por
     * nombre.
     *
     * <p>{@code reason} se recorta con el mismo qualifier que el resto de los textos del modulo: el
     * MINIMO ya lo verifico la guarda de arriba sobre el valor recortado, asi que aca solo queda
     * normalizar. {@code force} llega como TEXTO y se resuelve con {@code parseForce}, igual que en
     * la asignacion y en la transicion: declarado como {@code Boolean}, un valor que no parsea lo
     * rechaza el lector de JSON con un cuerpo que no es el Problem que el contrato promete.
     */
    @Mapping(target = "serviceId", source = "serviceId")
    @Mapping(target = "reason", source = "serviceAddResourcesRequest.reason",
        qualifiedByName = "trimToNull")
    @Mapping(target = "force", source = "serviceAddResourcesRequest.force",
        qualifiedByName = "parseForce")
    AddServiceResourcesCommand toAddServiceResourcesCommand(
        long serviceId, ServiceAddResourcesRequest serviceAddResourcesRequest);

    /**
     * El motivo del refuerzo, medido DESPUES de recortarlo, por el mismo motivo que la
     * justificacion de la edicion: el minimo declarativo cuenta el texto CRUDO, asi que uno corto
     * rellenado con espacios lo pasa entero y dejaria la bitacora —y el reporte semanal, que lo
     * muestra al lado del conductor adicional— con un motivo que no explica nada.
     */
    private static void requireReason(String reason) {
        String normalized = StringUtils.trimToNull(reason);
        if (normalized == null
                || normalized.length() < ServiceAddResourcesRequest.MIN_REASON_LENGTH) {
            throw CommonError.VALIDATION_FAILED.toException(
                "El motivo necesita al menos " + ServiceAddResourcesRequest.MIN_REASON_LENGTH
                    + " caracteres");
        }
    }

    /**
     * Al menos uno de los tres recursos. Es una condicion ENTRE campos, asi que no se puede
     * declarar en el esquema ni en una anotacion de campo: la sostiene esta guarda, y del lado de
     * la base el CHECK de la tabla.
     *
     * <p>Muerde sobre el VALOR y no sobre la presencia de la clave: un formulario que serializa el
     * objeto entero manda {@code null} en los combos que no se eligieron, y un pedido con los tres
     * en null es exactamente el mismo pedido vacio que uno sin ninguna clave.
     */
    private static void requireAtLeastOneResource(ServiceAddResourcesRequest request) {
        if (request.driverId() == null && request.tractorId() == null
                && request.trailerId() == null) {
            throw CommonError.VALIDATION_FAILED.toException(
                "Hay que indicar al menos un recurso: conductor, tracto o carreta");
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
            ServiceRequestParsing.parseSearch(q),
            parseStatus(status),
            ServiceRequestParsing.parseInteger(clientId, "clientId"),
            ServiceRequestParsing.parseDate(dateFrom, "dateFrom"),
            ServiceRequestParsing.parseDate(dateTo, "dateTo"),
            ServiceRequestParsing.parseRangedInt(
                page, "page", ServiceRequestParsing.DEFAULT_PAGE, 0, Integer.MAX_VALUE),
            ServiceRequestParsing.parseRangedInt(
                size, "size", ServiceRequestParsing.DEFAULT_PAGE_SIZE,
                1, ServiceRequestParsing.MAX_PAGE_SIZE));
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
        if (id == null || !ServiceRequestParsing.ASCII_INTEGER.matcher(id).matches()) {
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

}
