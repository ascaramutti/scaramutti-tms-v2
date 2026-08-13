package com.scaramutti.tms.operations.service;

import com.scaramutti.tms.auth.security.CurrentUser;
import com.scaramutti.tms.operations.OperationsError;
import com.scaramutti.tms.operations.dto.ServiceDetailResponse;
import com.scaramutti.tms.operations.model.ServiceAuditChangeType;
import com.scaramutti.tms.operations.model.ServiceEventType;
import com.scaramutti.tms.operations.model.ServiceStatus;
import com.scaramutti.tms.operations.service.cmd.UpdateServiceCommand;
import com.scaramutti.tms.shared.entity.Currency;
import com.scaramutti.tms.shared.entity.Service;
import com.scaramutti.tms.shared.entity.ServiceAuditLog;
import com.scaramutti.tms.shared.entity.ServiceEvent;
import com.scaramutti.tms.shared.exception.CommonError;
import com.scaramutti.tms.shared.repository.CurrencyRepository;
import com.scaramutti.tms.shared.repository.ServiceAuditLogRepository;
import com.scaramutti.tms.shared.repository.ServiceEventRepository;
import com.scaramutti.tms.shared.repository.ServiceRepository;
import com.scaramutti.tms.shared.util.DateUtils;
import com.scaramutti.tms.shared.util.Etag;
import com.scaramutti.tms.shared.util.StringUtils;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Edicion de un servicio de transporte, con justificacion obligatoria.
 *
 * <p>El cliente, el ambito del viaje y el tipo de carga NO se editan: si se equivocaron, el viaje
 * se crea de nuevo, y si nunca debio existir se elimina. Las fechas reales de inicio y fin se
 * CORRIGEN pero no se fijan: cerrar un viaje sigue siendo la transicion de estado.
 *
 * <p>Orden del flow: identificar al usuario y exigirle que pueda ver los importes (el cuerpo los
 * trae), cargar el viaje, rechazar los dos estados inmutables, verificar la version,
 * validar la moneda y las fechas, y recien ahi comparar. Si no cambio nada, la transaccion
 * termina sin escribir una sola fila.
 */
@ApplicationScoped
public class UpdateServiceService {

    /**
     * Los dos estados que ya no admiten edicion. El COMPLETED queda AFUERA a proposito: corregir
     * los datos de un viaje ya cerrado es el caso que mas justifica este endpoint.
     */
    private static final Set<ServiceStatus> IMMUTABLE_STATUSES =
        EnumSet.of(ServiceStatus.CANCELLED, ServiceStatus.DELETED);

    /** Como se muestra en la bitacora un valor que no todos los roles pueden ver. */
    private static final String HIDDEN_VALUE_LABEL = "(no se muestra)";

    /** Formato de las marcas de tiempo en la bitacora, en hora de Peru y como se lee en es-PE. */
    private static final DateTimeFormatter LIMA_FORMAT =
        DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");

    @Inject ServiceRepository serviceRepository;
    @Inject ServiceEventRepository serviceEventRepository;
    @Inject ServiceAuditLogRepository serviceAuditLogRepository;
    @Inject CurrencyRepository currencyRepository;
    @Inject CurrentUser currentUser;
    @Inject GetServiceService getServiceService;
    @Inject ServiceRowLock serviceRowLock;
    @Inject ServicePriceVisibility servicePriceVisibility;

    /**
     * El conflicto de lock se traduce sobre TODA la edicion, no sobre un tramo.
     *
     * <p>El tope de espera lo pone la lectura con lock y rige hasta que la transaccion termina,
     * asi que desde ahi cualquier sentencia puede rendirse: las lecturas de catalogo, las
     * escrituras, y las media docena de consultas que arman el detalle de la respuesta. Todas
     * tocan tablas que este modulo no controla solo —la base la comparte el sistema anterior— y a
     * cualquiera la puede frenar un DDL durante un deploy. Traducirlas por tramos deja huecos que
     * salen como error del servidor donde el contrato declara un 409 transitorio; envolver la
     * operacion entera los cierra, con una excepcion conocida: el COMMIT ocurre despues de que
     * este metodo retorna, o sea afuera. Hoy no importa —ninguna restriccion del esquema es
     * DEFERRABLE, asi que el chequeo de claves corre dentro del volcado, que si esta adentro—
     * pero la primera que lo sea reabre ese hueco.
     */
    @Transactional
    public ServiceDetailResponse updateService(UpdateServiceCommand command) {
        Integer userId = currentUser.requireId();
        servicePriceVisibility.requireCanSeePrices();

        return serviceRowLock.runTranslatingLockConflicts(
            () -> updateLockedService(command, userId), command.serviceId());
    }

    private ServiceDetailResponse updateLockedService(UpdateServiceCommand command, Integer userId) {
        Service service = serviceRowLock.findByIdForUpdate(command.serviceId());
        rejectIfImmutable(service);
        Etag.verify(command.ifMatch(), service.updatedAt);

        Currency newCurrency = resolveCurrency(service, command.currencyId());
        OffsetDateTime newStart = resolveRealDate(
            service.startDateTime, command.startDateTime(), "de inicio");
        OffsetDateTime newEnd = resolveRealDate(
            service.endDateTime, command.endDateTime(), "de fin");
        requireEndNotBeforeStart(newStart, newEnd);

        List<FieldChange> changes = diff(service, command, newCurrency, newStart, newEnd);
        // Sin cambios reales no se escribe NADA: ni auditoria, ni bitacora, ni la version. Se
        // devuelve antes de aplicar nada, que es lo que garantiza que no se escriba una sola
        // fila de rastro. (La entity gestionada, en cambio, NO se ensuciaria por reasignarle los
        // mismos valores con otra escala: Hibernate compara los importes por valor.)
        if (changes.isEmpty()) {
            return getServiceService.getService(service.id);
        }

        applyChanges(service, command, changes, newStart, newEnd, userId);
        writeAuditLogs(service, changes, command.justification(), userId);
        writeEditEvent(service, changes, command.justification(), userId);
        // Este volcado NO es defensivo: el gancho que mueve la version corre AL VOLCAR, y ningun
        // volcado automatico lo cubre. La consulta que arma la bitacora podria dispararlo, pero
        // para cuando corre ya no queda nada encolado: las filas de bitacora y de auditoria las
        // genera la base (id IDENTITY), asi que su INSERT se ejecuta en el acto al persistirlas, y
        // lo unico que sigue pendiente es el UPDATE del servicio. Sin esta linea el detalle se
        // arma con la version VIEJA: la respuesta sale con un ETag que la base ya no tiene, y el
        // proximo If-Match del cliente responde 412 para siempre hasta que vuelva a leer el
        // detalle. Verificado quitandola.
        serviceRepository.flush();

        return getServiceService.getService(service.id);
    }

    // ---------- Precondiciones -------------------------------------------------

    private void rejectIfImmutable(Service service) {
        if (IMMUTABLE_STATUSES.contains(ServiceStatus.valueOf(service.status))) {
            throw OperationsError.SERVICE_NOT_EDITABLE.toException();
        }
    }

    /**
     * La moneda solo tiene que estar ACTIVA cuando se la cambia. Exigirlo siempre dejaria sin
     * editar para siempre a todo servicio cuya moneda se dio de baja despues: ni siquiera se
     * podria corregirle una errata en el origen, que no tiene nada que ver con lo que se cobra.
     * Retirar una moneda del catalogo cierra su uso hacia adelante, no congela lo ya emitido.
     */
    private Currency resolveCurrency(Service service, Integer currencyId) {
        Currency currency = currencyRepository.findById(currencyId);
        boolean unchanged = Objects.equals(service.currencyId, currencyId);
        if (currency == null || (!unchanged && Boolean.FALSE.equals(currency.isActive))) {
            throw CommonError.VALIDATION_FAILED.toException(
                "La moneda indicada no existe o está inactiva");
        }
        return currency;
    }

    // ---------- Fechas reales --------------------------------------------------

    /**
     * Una fecha real solo se CORRIGE: se admite unicamente si el servicio ya la tiene. Ausente y
     * null son lo mismo ("sin cambio") porque una fecha real no se borra, y un formulario que
     * serializa el objeto entero manda null en los campos que todavia no aplican.
     *
     * <p>Se normaliza a UTC y a la precision que guarda la columna. El truncado es el que hace el
     * trabajo: la columna guarda microsegundos, asi que una marca con mas precision se leeria como
     * distinta de la que la base va a devolver y ensuciaria la bitacora con una edicion que no
     * ocurrio (es el bug D-12, el mismo que obliga a truncar el {@code updatedAt} del ETag). El
     * cambio de huso hoy es redundante — el lector de JSON ya entrega la marca en UTC —, pero la
     * comparacion no puede depender de un ajuste por defecto que se configura en otro archivo.
     */
    private OffsetDateTime resolveRealDate(
            OffsetDateTime current, OffsetDateTime requested, String which) {
        if (requested == null) {
            return current;
        }
        // El mensaje nombra la condicion REAL (la fecha todavia no esta puesta) y no el estado que
        // normalmente la acompaña: un viaje migrado del sistema anterior puede estar en ruta sin
        // fecha de inicio, y ahi un mensaje que hable del estado contradice lo que el usuario tiene
        // en pantalla. Y NO promete un remedio: en un viaje migrado ya completado, la transicion
        // que habria fijado la fecha ya ocurrio y no vuelve, asi que esa fila se sanea por el
        // script de cutover; y en uno nacido en v2, el endpoint que las fija todavia no existe.
        // Prometer "lo pone el cambio de estado" seria mandar al usuario a una puerta cerrada.
        if (current == null) {
            throw CommonError.VALIDATION_FAILED.toException(
                "La fecha " + which + " todavía no está registrada, y acá se corrige, no se fija.");
        }
        return requested.withOffsetSameInstant(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }

    /**
     * El fin no puede quedar antes del inicio. Se compara el resultado FINAL de la edicion, no lo
     * que vino en el cuerpo: corregir solo el inicio y empujarlo mas alla del fin ya guardado es
     * la misma inconsistencia, y mirando solo los campos enviados pasaria sin que nadie la vea.
     */
    private void requireEndNotBeforeStart(OffsetDateTime start, OffsetDateTime end) {
        if (start != null && end != null && end.isBefore(start)) {
            throw CommonError.VALIDATION_FAILED.toException(
                "La fecha de fin no puede ser anterior a la de inicio");
        }
    }

    // ---------- Diff -----------------------------------------------------------

    /**
     * Un campo que cambio. Lleva los valores CANONICOS, que son los que van a la auditoria
     * (reconstruibles, en UTC, sin formato local), y aparte la linea ya redactada para la
     * bitacora, que la lee una persona y no siempre muestra lo mismo.
     */
    private record FieldChange(String name, String label, String oldValue, String newValue,
                               String logLine) {}

    /**
     * Compara campo por campo sobre el texto CANONICO de cada valor, no sobre el objeto: dos
     * importes iguales con distinta escala ({@code 1200} y {@code 1200.00}) no son iguales para
     * {@code BigDecimal.equals}, asi que comparar objetos reportaria como edicion un reenvio
     * identico del formulario y romperia el 200 sin cambios.
     *
     * <p>Este metodo no solo arma el rastro: es lo que DECIDE si la edicion se guarda, porque el
     * camino sin cambios devuelve antes de aplicar nada. Un campo que se caiga de esta lista deja
     * de persistirse en silencio, con 200 y el valor viejo. El test que edita de una vez los diez campos
     * que un viaje recien creado admite afirma la lista COMPLETA de la auditoria justamente para
     * que eso no pueda pasar sin ruido; las dos fechas reales, que ese viaje todavia no tiene,
     * las cubren sus casos propios.
     */
    private List<FieldChange> diff(Service service, UpdateServiceCommand command,
            Currency newCurrency, OffsetDateTime newStart, OffsetDateTime newEnd) {
        List<FieldChange> changes = new ArrayList<>();
        addChange(changes, "tentativeDate", "Fecha tentativa",
            asText(service.tentativeDate), asText(command.tentativeDate()));
        addChange(changes, "weight", "Peso (kg)",
            asText(service.weight), asText(command.weightKg()));
        addChange(changes, "length", "Largo (m)",
            asText(service.length), asText(command.lengthM()));
        addChange(changes, "width", "Ancho (m)",
            asText(service.width), asText(command.widthM()));
        addChange(changes, "height", "Alto (m)",
            asText(service.height), asText(command.heightM()));
        addMoneyChange(changes, "price", "Precio",
            asText(service.price), asText(command.price()));
        addCurrencyChange(changes, service.currencyId, command.currencyId(), newCurrency);
        // Los textos libres se comparan NORMALIZADOS de los dos lados. Lo que llega del cuerpo ya
        // viene recortado, pero lo guardado no siempre: la carga de datos del sistema anterior
        // puede dejar una cadena vacia donde el alta de v2 pondria null, y sin normalizar el
        // primer reenvio identico del formulario se leeria como una edicion de "" a null.
        //
        // Como se comparan normalizados, un dato guardado que difiera SOLO en espacios no es un
        // cambio, y por eso tampoco se reescribe: ver applyChanges. Si se reescribiera, la
        // limpieza entraria en la base sin fila de auditoria ni linea de bitacora, colada en la
        // edicion de otro campo — justo lo contrario de lo que promete el contrato.
        addTextChange(changes, "observations", "Observaciones",
            service.observations, command.observations());
        addTextChange(changes, "origin", "Origen", service.origin, command.origin());
        addTextChange(changes, "destination", "Destino",
            service.destination, command.destination());
        addInstantChange(changes, "startDateTime", "Inicio real", service.startDateTime, newStart);
        addInstantChange(changes, "endDateTime", "Fin real", service.endDateTime, newEnd);
        return changes;
    }

    /**
     * Texto libre: se COMPARA normalizado y se AUDITA crudo.
     *
     * <p>Lo normalizado decide si hubo cambio (ver arriba); lo crudo es lo que estaba en la
     * columna, que es lo unico con lo que se puede reconstruir el estado previo — y para eso
     * existe la tabla de auditoria. Guardar ahi el valor recortado afirmaria que el dato ya venia
     * limpio, justo del sistema anterior, que es de donde viene el relleno.
     */
    private void addTextChange(List<FieldChange> changes, String name, String label,
            String storedValue, String newValue) {
        if (Objects.equals(StringUtils.trimToNull(storedValue), newValue)) {
            return;
        }
        changes.add(new FieldChange(name, label, storedValue, newValue,
            logLine(label, storedValue, newValue)));
    }

    private void addChange(List<FieldChange> changes, String name, String label,
            String oldValue, String newValue) {
        if (!Objects.equals(oldValue, newValue)) {
            changes.add(new FieldChange(name, label, oldValue, newValue,
                logLine(label, oldValue, newValue)));
        }
    }

    /**
     * Campo de plata: el rastro estructurado guarda los importes, pero la bitacora dice UNICAMENTE
     * que el campo se tocó. La bitacora viaja dentro del detalle, y al detalle entra el rol de
     * despacho, al que la regla del negocio le oculta siempre lo que se cobra: con el importe en
     * el texto de la nota, esa regla se caeria por la puerta de al lado. Los valores siguen
     * estando en la auditoria, que hoy no expone ningun endpoint.
     */
    private void addMoneyChange(List<FieldChange> changes, String name, String label,
            String oldValue, String newValue) {
        if (!Objects.equals(oldValue, newValue)) {
            changes.add(new FieldChange(name, label, oldValue, newValue,
                label + ": " + HIDDEN_VALUE_LABEL));
        }
    }

    /**
     * La moneda decide si cambio mirando el ID GUARDADO, y usa el codigo solo como etiqueta del
     * rastro. Comparando codigos, dos filas del catalogo que compartieran uno harian que un cambio
     * real se leyera como "sin cambios" y el {@code currencyId} pedido se descartara con un 200.
     *
     * <p>Ningun test puede fijar esta diferencia: {@code currencies} tiene un indice unico sobre
     * el codigo, asi que hoy los dos caminos dan siempre lo mismo. Esta escrito asi para que la
     * correccion de este endpoint no dependa de una restriccion que vive en el schema del sistema
     * anterior y que nadie de este modulo controla.
     */
    private void addCurrencyChange(List<FieldChange> changes, Integer oldCurrencyId,
            Integer newCurrencyId, Currency newCurrency) {
        if (Objects.equals(oldCurrencyId, newCurrencyId)) {
            return;
        }
        changes.add(new FieldChange("currency", "Moneda",
            currencyCodeOf(oldCurrencyId), newCurrency.code,
            "Moneda: " + HIDDEN_VALUE_LABEL));
    }

    /**
     * Marca de tiempo: se compara y se audita en UTC (sin ambiguedad y reconstruible), pero en la
     * bitacora se muestra en hora de Peru. Mostrarla en UTC dejaria la misma pantalla diciendo dos
     * horas distintas para el mismo dato: el campo con la hora local y la bitacora con la de
     * Greenwich.
     */
    private void addInstantChange(List<FieldChange> changes, String name, String label,
            OffsetDateTime oldValue, OffsetDateTime newValue) {
        // Se comparan INSTANTES, no sus textos. El texto lleva el huso adentro, asi que comparar
        // texto hace depender el "sin cambios" de que el driver devuelva la marca guardada en el
        // mismo huso en el que se normaliza la entrante: el dia que eso cambie, cada reenvio
        // identico de un viaje con fechas reales se volveria una edicion fantasma, con su fila de
        // auditoria y una linea de bitacora que dice lo mismo de los dos lados.
        //
        // Ningun test puede fijar esta diferencia: hoy el driver devuelve las marcas en UTC y las
        // dos formas de comparar dan siempre lo mismo (verificado mutando esta linea). Esta
        // escrito asi para que la garantia no dependa de ese comportamiento.
        if (!Objects.equals(toInstant(oldValue), toInstant(newValue))) {
            // Los DOS lados se auditan en UTC. El nuevo ya viene normalizado, el viejo llega tal
            // como lo devuelva el driver: sin normalizarlo, la misma fila podria guardar el valor
            // anterior con un huso y el nuevo con otro, que es el defecto que la comparacion de
            // arriba evita y que aca volveria a entrar por el texto. Ningun test puede fijarlo
            // (hoy el driver ya devuelve UTC y las dos formas coinciden, verificado mutando la
            // linea); esta escrito asi para que el rastro no dependa de ese comportamiento.
            changes.add(new FieldChange(name, label, asUtcText(oldValue), asUtcText(newValue),
                logLine(label, asLimaText(oldValue), asLimaText(newValue))));
        }
    }

    /** {@code Etiqueta: viejo → nuevo}; un valor ausente se nombra, porque "Alto (m): → 12" no se entiende. */
    private String logLine(String label, String oldDisplay, String newDisplay) {
        return label + ": " + ServiceLogText.display(oldDisplay) + " → " + ServiceLogText.display(newDisplay);
    }

    /**
     * El codigo de la moneda GUARDADA, solo como etiqueta del rastro. Si la fila ya no existe se
     * devuelve null y la auditoria queda con el valor viejo vacio, que es preferible a no poder
     * editar: la moneda que importa para la validacion es la nueva, y esa ya se comprobo.
     */
    private String currencyCodeOf(Integer currencyId) {
        Currency currency = currencyRepository.findById(currencyId);
        return currency != null ? currency.code : null;
    }

    /**
     * Texto canonico de un importe: sin ceros de relleno y sin notacion cientifica, para que el
     * mismo numero se escriba siempre igual en la auditoria y se compare siempre igual.
     */
    private String asText(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }

    private String asText(LocalDate value) {
        return value == null ? null : value.toString();
    }

    /** Texto canonico de una marca de tiempo: siempre en UTC, para que el rastro sea comparable. */
    private String asUtcText(OffsetDateTime value) {
        return value == null ? null : value.withOffsetSameInstant(ZoneOffset.UTC).toString();
    }

    private java.time.Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    /** La misma marca, en hora de Peru, para que la lea una persona sin traducir el huso. */
    private String asLimaText(OffsetDateTime value) {
        return value == null ? null : value.atZoneSameInstant(DateUtils.LIMA).format(LIMA_FORMAT);
    }

    // ---------- Escritura ------------------------------------------------------

    /**
     * Escribe EXACTAMENTE los campos que el diff conto como cambio, ninguno mas.
     *
     * <p>Se recorre la lista de cambios y no la de campos, para que el nombre de cada campo viva
     * en UN solo lugar por campo. Con dos listas paralelas, separarlas —un renombre a medias, un
     * campo agregado de un lado solo— no falla: escribe la auditoria y la bitacora afirmando un
     * cambio, deja la fila sin tocar y mueve la version igual. Es el "rastro que afirma un cambio
     * que no ocurrio" que este modulo existe para impedir, y encima es un rastro que despues nadie
     * puede editar ni borrar. Asi, el desalineo revienta en la primera edicion que lo toque.
     *
     * <p>Tiene que correr ANTES de escribir la auditoria y la bitacora, y no solo por prolijidad:
     * si el {@code default} de abajo llegara a dispararse con las escrituras ya hechas, la
     * transaccion muere dejando rastro de una edicion que reventó. Con este orden, revienta antes
     * de que se ejecute un solo INSERT.
     *
     * <p>Escribir SOLO lo contado tambien es necesario para los tres textos libres: un valor
     * guardado que difiere unicamente en espacios (dato tipico del sistema anterior) no es un
     * cambio, y reescribirlo limpiaria la base colado en la edicion de otro campo.
     */
    private void applyChanges(Service service, UpdateServiceCommand command,
            List<FieldChange> changes, OffsetDateTime newStart, OffsetDateTime newEnd,
            Integer userId) {
        for (FieldChange change : changes) {
            switch (change.name()) {
                case "tentativeDate" -> service.tentativeDate = command.tentativeDate();
                case "origin" -> service.origin = command.origin();
                case "destination" -> service.destination = command.destination();
                case "weight" -> service.weight = command.weightKg();
                case "length" -> service.length = command.lengthM();
                case "width" -> service.width = command.widthM();
                case "height" -> service.height = command.heightM();
                case "price" -> service.price = command.price();
                case "currency" -> service.currencyId = command.currencyId();
                case "observations" -> service.observations = command.observations();
                case "startDateTime" -> service.startDateTime = newStart;
                case "endDateTime" -> service.endDateTime = newEnd;
                default -> throw new IllegalStateException(
                    "el diff conto un cambio que esta escritura no sabe aplicar: " + change.name());
            }
        }
        // Este NO sale del diff: no es un campo del viaje, es quien lo toco.
        service.updatedBy = userId;
        // La version tampoco: la mueve el @PreUpdate de la entity, que es una sola fuente para
        // todos los endpoints que van a escribir sobre esta misma fila. Solo se llega aca con
        // algun campo distinto, asi que la fila queda sucia y el gancho dispara seguro.
    }

    /** Una fila por campo cambiado: es el registro estructurado, con el viejo y el nuevo valor. */
    private void writeAuditLogs(Service service, List<FieldChange> changes,
            String justification, Integer userId) {
        for (FieldChange change : changes) {
            ServiceAuditLog auditLog = new ServiceAuditLog();
            auditLog.serviceId = service.id;
            auditLog.changedBy = userId;
            auditLog.changeType = ServiceAuditChangeType.FIELD_EDIT.name();
            auditLog.fieldName = change.name();
            auditLog.fieldLabel = change.label();
            auditLog.oldValue = change.oldValue();
            auditLog.newValue = change.newValue();
            auditLog.description = justification;
            serviceAuditLogRepository.persist(auditLog);
        }
    }

    /**
     * UNA entrada de bitacora por edicion, no una por campo: la bitacora es la linea de tiempo de
     * las ACCIONES del viaje, y una linea por campo repetiria la misma justificacion tantas veces
     * como campos se hayan tocado. El detalle campo a campo ya vive en la auditoria.
     */
    private void writeEditEvent(Service service, List<FieldChange> changes,
            String justification, Integer userId) {
        StringBuilder note = new StringBuilder();
        for (FieldChange change : changes) {
            note.append(change.logLine()).append('\n');
        }
        note.append("Justificación: ").append(ServiceLogText.display(justification));

        ServiceEvent event = new ServiceEvent();
        event.serviceId = service.id;
        event.eventType = ServiceEventType.FIELD_EDIT.name();
        event.note = note.toString();
        event.createdBy = userId;
        serviceEventRepository.persist(event);
    }

}
