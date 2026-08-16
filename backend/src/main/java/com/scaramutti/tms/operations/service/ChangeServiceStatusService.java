package com.scaramutti.tms.operations.service;

import com.scaramutti.tms.auth.security.CurrentUser;
import com.scaramutti.tms.operations.OperationsError;
import com.scaramutti.tms.operations.dto.ServiceDetailResponse;
import com.scaramutti.tms.operations.model.ServiceAuditChangeType;
import com.scaramutti.tms.operations.model.ServiceEventType;
import com.scaramutti.tms.operations.model.ServiceStatus;
import com.scaramutti.tms.operations.model.ServiceStatusLabels;
import com.scaramutti.tms.operations.model.ServiceStatusTransition;
import com.scaramutti.tms.operations.service.cmd.ChangeServiceStatusCommand;
import com.scaramutti.tms.shared.entity.Service;
import com.scaramutti.tms.shared.entity.ServiceAuditLog;
import com.scaramutti.tms.shared.entity.ServiceEvent;
import com.scaramutti.tms.shared.exception.CommonError;
import com.scaramutti.tms.shared.repository.ServiceAuditLogRepository;
import com.scaramutti.tms.shared.repository.ServiceEventRepository;
import com.scaramutti.tms.operations.dto.ServiceResourceConflictResponse;
import com.scaramutti.tms.shared.repository.ServiceResourceConflictRepository;
import com.scaramutti.tms.shared.repository.ServiceRepository.ServiceAssignedResourcesRow;
import com.scaramutti.tms.shared.repository.ServiceRepository;
import com.scaramutti.tms.shared.util.DateUtils;
import com.scaramutti.tms.shared.util.Etag;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Transiciones de estado de un viaje (RN-OP1): iniciar, finalizar, cancelar, eliminar y reabrir.
 *
 * <p>Cancelar y eliminar NO son lo mismo. Cancelar es un viaje real que se abortó; eliminar
 * etiqueta el registro que nunca debió existir y lo saca de listados e indicadores. Por eso
 * eliminar solo se puede desde los dos pendientes: un viaje que ya salió a ruta ocurrió, y lo que
 * ocurrió se cancela. Nada se borra de la base.
 *
 * <p>El paso de "pendiente de asignación" a "pendiente de inicio" no se pide acá: es efecto de
 * asignar recursos. La máquina lo conoce igual, porque describe el dominio y no este endpoint.
 */
@ApplicationScoped
public class ChangeServiceStatusService {

    @Inject ServiceRepository serviceRepository;
    @Inject ServiceEventRepository serviceEventRepository;
    @Inject ServiceAuditLogRepository serviceAuditLogRepository;
    @Inject CurrentUser currentUser;
    @Inject GetServiceService getServiceService;
    @Inject ServiceRowLock serviceRowLock;
    @Inject ServiceStatusMachine serviceStatusMachine;
    @Inject ServiceStatusChangeAuthorization serviceStatusChangeAuthorization;
    @Inject ServiceResourceConflicts serviceResourceConflicts;

    /**
     * El veto de rol se resuelve ANTES de tocar la base: es función pura de (quién llama, qué
     * pide), no necesita el viaje y no vale la pena tomar un lock para negar algo que ya se sabe.
     * De paso, un rol sin permiso no puede usar los códigos de error para averiguar qué viajes
     * existen.
     *
     * <p>Todo lo demás va envuelto en la traducción de conflictos de lock, por el mismo motivo que
     * la edición y la asignación: el tope de espera lo fija la lectura con lock y rige hasta que
     * la transacción termina, así que desde ahí cualquier sentencia puede rendirse.
     */
    @Transactional
    public ServiceDetailResponse changeServiceStatus(ChangeServiceStatusCommand command) {
        Integer userId = currentUser.requireId();
        serviceStatusChangeAuthorization.requireCanRequest(command.transition());

        return serviceRowLock.runTranslatingLockConflicts(
            () -> changeLockedServiceStatus(command, userId), command.serviceId());
    }

    /**
     * El ORDEN de las guardas es la decisión de diseño de este método, no un detalle. Son seis; las
     * dos que este PR agregó (los datos de la etapa anterior y los recursos que se llevó otro
     * viaje) entran en el medio, no al final.
     *
     * <ol>
     *   <li><b>La RESOLUCIÓN DEL DESTINO</b> va primera, porque sin destino no hay nada que
     *       preguntarle a las demás. En cuatro de las cinco lo dice la tabla; la reapertura lo saca
     *       del rastro, y de ahí salen sus tres rechazos propios (sin rastro, con un estado que el
     *       enum no conoce, o con uno del que no se vuelve). Eso pone esos tres <b>OPS-009</b>
     *       ANTES del 412, y es correcto: no se puede verificar la versión de algo cuyo destino
     *       todavía no se sabe, y recargar tampoco arregla un rastro que hay que sanear.</li>
     *   <li><b>OPS-004</b> (el viaje está cancelado o eliminado) habla de la FILA. Va segunda, y
     *       eso hace que a la máquina nunca se la pregunte por esos dos orígenes: sin esa
     *       precedencia, los doce pares que salen de un terminal cumplen las dos condiciones y el
     *       código de respuesta dependería del orden de dos {@code if}.</li>
     *   <li><b>OPS-001</b> (la transición no existe) habla del ARCO.</li>
     *   <li><b>412</b> va DESPUÉS de las dos anteriores: un 412 le dice al cliente "recargá y
     *       reintentá", y recargar no arregla nada cuando desde ese estado no se puede llegar al
     *       pedido. Es el mismo criterio ya ratificado para la edición, que contesta la
     *       inmutabilidad antes que la versión.</li>
     *   <li><b>OPS-009</b> por los datos de la etapa anterior: el arco ya es válido, así que lo
     *       que queda por mirar es si la fila trae lo que esa etapa tenía que haber dejado.</li>
     *   <li><b>OPS-002</b> por recursos que se llevó otro viaje, y solo al reabrir. Va después de
     *       todo lo anterior porque es la única que vuelve a consultar la base y toma más locks:
     *       ese costo no se paga por una transición que igual se iba a rechazar.</li>
     *   <li><b>400</b> de RN-OP13 va última porque es la única que depende de un valor calculado
     *       (la fecha resuelta), y calcularla antes de saber si la transición procede sería
     *       trabajo sobre una operación ya rechazada.</li>
     * </ol>
     */
    private ServiceDetailResponse changeLockedServiceStatus(
            ChangeServiceStatusCommand command, Integer userId) {
        Service service = serviceRowLock.findByIdForUpdate(command.serviceId());
        ServiceStatus previousStatus = ServiceStatus.valueOf(service.status);

        ServiceStatus target = resolveTarget(service, previousStatus, command.transition());
        requireValidTransition(previousStatus, command.transition(), target);
        requireVersionIfDemandedOrOffered(command, service);

        requirePreviousStageData(service, target);
        List<ServiceResourceConflictResponse> conflicts = findConflictsIfTheTripGoesBackToHolding(
            service, command, target);
        OffsetDateTime dateTime = resolveDateTime(command);
        requireRealDatesStayOrdered(service, command.transition(), dateTime);

        // Se leen ANTES de aplicar: despues de la transicion la entity ya tiene el valor nuevo y
        // la auditoria diria que el campo paso de si mismo a si mismo.
        String previousStart = ServiceLogText.asUtcText(service.startDateTime);
        String previousEnd = ServiceLogText.asUtcText(service.endDateTime);

        applyTransition(service, target, command.transition(), dateTime, userId);
        writeStatusAuditLogs(service, previousStatus, target, conflicts, previousStart,
            previousEnd, command, dateTime, userId);
        writeStatusChangeEvent(service, previousStatus, target, conflicts, command, dateTime, userId);
        // Mismo motivo que en la edición y en la asignación: el gancho que mueve la versión corre
        // AL VOLCAR, y sin esto el detalle sale con un ETag que la base ya no tiene, dejando al
        // cliente con un If-Match que responde 412 para siempre.
        serviceRepository.flush();

        return getServiceService.getService(service.id);
    }

    // ---------- Precondiciones -------------------------------------------------

    /**
     * La version se exige en las TRES transiciones que sacan el viaje del circuito o lo devuelven
     * (cancelar, eliminar y reabrir) y se HONRA en las otras dos cuando el cliente la manda.
     *
     * <p>Son dos reglas distintas y las dos hacen falta. La primera es de negocio: las tres mueven
     * el viaje entero de lugar y solo la gerencia las pide, asi que el estado que el usuario vio en
     * pantalla tiene que seguir siendo el actual. La segunda es de HTTP: RFC 9110 dice que un {@code If-Match} recibido se
     * evalua, y no hacerlo le daria al cliente una proteccion que cree tener y no tiene. El
     * contrato decia "se ignora en las demas transiciones" y quedo corregido en este PR.
     *
     * <p>Ausente donde no se exige es el unico caso que sigue de largo, que es lo que mantiene
     * andando a todo cliente que hoy no manda el header.
     */
    private void requireVersionIfDemandedOrOffered(
            ChangeServiceStatusCommand command, Service service) {
        if (command.transition().requiresIfMatch() || command.ifMatch() != null) {
            Etag.verify(command.ifMatch(), service.updatedAt);
        }
    }

    /**
     * A donde va el viaje. Casi siempre lo dice la tabla; la reapertura lo saca del rastro.
     *
     * <p>Reabrir es la unica operacion que ENTRA a un estado inmutable en vez de salir de el, asi
     * que es tambien la unica que no pasa por la guarda de inmutabilidad. Se compensa con una
     * guarda propia y mas estrecha: solo desde cancelado o eliminado. Un viaje completado no se
     * reabre —terminar no es un error que haya que deshacer, y si los datos estan mal se corrigen
     * editando, que en ese estado sigue permitido—.
     *
     * <p>Y si no hay rastro de donde venia, no se inventa: los viajes que traiga el cutover del
     * sistema anterior no tienen la fila, y devolverlos a un estado por defecto los pondria en uno
     * que quizas nunca tuvieron.
     */
    private ServiceStatus resolveTarget(Service service, ServiceStatus previousStatus,
            ServiceStatusTransition transition) {
        if (!transition.restoresPreviousStatus()) {
            ServiceStatusGuards.rejectIfImmutable(previousStatus);
            return transition.target();
        }
        if (!ServiceStatusGuards.IMMUTABLE_STATUSES.contains(previousStatus)) {
            throw OperationsError.INVALID_STATUS_TRANSITION.toException(
                "Solo se reabre un viaje cancelado o eliminado, y este está \""
                    + ServiceStatusLabels.of(previousStatus) + "\"");
        }
        String restored = serviceAuditLogRepository.findPreviousStatusOf(
            service.id, previousStatus.name());
        if (restored == null) {
            throw OperationsError.SERVICE_DATA_INCOMPLETE.toException(
                "El viaje no registra el estado que tenía antes de quedar \""
                    + ServiceStatusLabels.of(previousStatus)
                    + "\", así que no se puede reabrir: hay que sanear el dato");
        }
        ServiceStatus restoredStatus;
        try {
            restoredStatus = ServiceStatus.valueOf(restored);
        } catch (IllegalArgumentException unknownStatus) {
            // El rastro puede traer un estado que este enum ya no conoce (data migrada, o una
            // escritura a mano en la tabla de auditoria). Es el MISMO problema que no tener fila
            // —no se puede reconstruir a donde volver— y pide el mismo saneo, asi que contesta
            // igual en vez de salir como error del servidor.
            throw OperationsError.SERVICE_DATA_INCOMPLETE.toException(
                "El viaje registra un estado anterior que ya no existe (\""
                    + ServiceLogText.abbreviate(restored)
                    + "\"), así que no se puede reabrir: hay que sanear el dato");
        }
        // Y que sea un estado al que se PUEDA volver, no solo uno que el enum conozca. Guardar el
        // valor desconocido y dejar pasar el conocido-pero-ilegal es media guarda: con un rastro
        // que diga "eliminado" —alcanzable por el cutover, igual que el caso de arriba— la
        // reapertura aterrizaria ahi sin que nada la mire, porque no pasa por la maquina de estados
        // y ninguna de las otras guardas contempla los terminales como DESTINO. El viaje quedaria
        // fuera de listados e indicadores con una bitacora que dice "vuelve a eliminado".
        if (ServiceStatusGuards.NON_RESTORABLE_STATUSES.contains(restoredStatus)) {
            throw OperationsError.SERVICE_DATA_INCOMPLETE.toException(
                "El viaje registra como estado anterior uno del que no se vuelve (\""
                    + ServiceStatusLabels.of(restoredStatus) + "\"): hay que sanear el dato");
        }
        return restoredStatus;
    }

    /**
     * El detalle nombra los DOS estados en es-PE. El mensaje genérico del catálogo no le dice al
     * usuario desde dónde estaba intentando ir, que es la mitad de la información que necesita
     * para entender por qué el botón que apretó no correspondía.
     */
    private void requireValidTransition(ServiceStatus previousStatus,
            ServiceStatusTransition transition, ServiceStatus target) {
        // La reapertura ya se valido en resolveTarget: su arco es "desde un terminal hacia donde
        // el rastro diga", que no es una fila de la maquina y no se puede preguntar ahi.
        if (transition.restoresPreviousStatus()) {
            return;
        }
        if (!serviceStatusMachine.canTransition(previousStatus, target)) {
            throw OperationsError.INVALID_STATUS_TRANSITION.toException(
                "No se puede pasar de \"" + ServiceStatusLabels.of(previousStatus)
                    + "\" a \"" + ServiceStatusLabels.of(target) + "\"");
        }
    }

    /**
     * La fecha resuelta, o null cuando la transición no fecha nada.
     *
     * <p>Ausente significa "ahora". La que llega en el cuerpo se canonicaliza con el mismo helper
     * compartido que usa la edición sobre estas MISMAS dos columnas: si cada puerta normalizara
     * por su cuenta, la divergencia sería silenciosa porque nadie compara las dos rutas.
     */
    private OffsetDateTime resolveDateTime(ChangeServiceStatusCommand command) {
        if (command.transition().dateColumn() == ServiceStatusTransition.DateColumn.NONE) {
            return null;
        }
        if (command.dateTime() == null) {
            return DateUtils.nowUtcMicros();
        }
        return DateUtils.toStorableUtc(command.dateTime());
    }

    /**
     * Cada etapa exige los datos que la etapa anterior tenia que haber dejado.
     *
     * <p>El viaje acumula sus datos por etapa: el alta deja lo basico, la asignacion pone conductor
     * y tracto (la carreta es opcional: hay carga que no la lleva), iniciar fija el inicio real y
     * finalizar el fin. Cada transicion es responsable de SU pedazo, y esta guarda verifica que el
     * pedazo anterior este.
     *
     * <p>Es una ASERCION DE INVARIANTE, no una regla que le complique la vida a nadie: por la
     * aplicacion las dos ramas son inalcanzables. A "pendiente de inicio" solo se llega asignando,
     * y asignar exige conductor y tracto; a "completado" solo se llega desde "en ruta", y arrancar
     * siempre escribe el inicio. Si esto dispara, la fila entro por fuera —el cutover del sistema
     * anterior, o SQL a mano— y lo que hay que hacer es sanear el dato, no reintentar.
     *
     * <p>Se pregunta por el target YA RESUELTO y no por la transicion pedida, y esa diferencia solo
     * se ve en la reapertura: su {@code target()} es null porque lo saca del rastro, asi que
     * mirando la transicion las dos ramas quedaban muertas y un viaje se podia reabrir hacia "en
     * ruta" sin conductor. Restaurar un estado exige los mismos datos que alcanzarlo por primera vez.
     *
     * <p>Va ANTES de RN-OP13 a proposito: con el inicio en null, aquella no tiene nada que comparar
     * y dejaria pasar el cierre. Y va DESPUES de la maquina porque primero importa si la transicion
     * existe: a un viaje cancelado hay que contestarle que esta cancelado, no que le falta un dato.
     *
     * <p>Cancelar y eliminar NO piden nada: son las salidas, y tienen que estar disponibles
     * justamente para los viajes que quedaron a medias.
     */
    private void requirePreviousStageData(Service service, ServiceStatus target) {
        // El conjunto sale de la MISMA constante que usa la reja de conflictos, y no de dos
        // literales: el 500 de mas abajo se justifica con que esta guarda ya probo lo contrario
        // sobre la misma fila, y eso solo es cierto mientras los dos conjuntos sean identicos.
        if (ServiceResourceConflictRepository.RESOURCE_HOLDING_STATUSES.contains(target)
                && (service.driverId == null || service.tractorId == null)) {
            throw OperationsError.SERVICE_DATA_INCOMPLETE.toException(
                "El viaje no tiene conductor y tracto asignados, así que no se puede pasar a \""
                    + ServiceStatusLabels.of(target) + "\": hay que sanear el dato");
        }
        if (target == ServiceStatus.COMPLETED && service.startDateTime == null) {
            throw OperationsError.SERVICE_DATA_INCOMPLETE.toException(
                "El viaje no registra cuándo inició, así que no se puede cerrar: "
                    + "hay que sanear el dato");
        }
    }

    /**
     * RN-OP13: el fin no puede quedar antes del inicio, mirando el PAR RESULTANTE y no solo la
     * fecha que se está escribiendo.
     *
     * <p>Las dos direcciones hacen falta. Que finalizar no quede antes del inicio guardado es la
     * obvia. La otra no: una fila puede llegar del cutover del sistema anterior con el FIN puesto
     * y todavía sin arrancar, y entonces iniciarla con una fecha posterior a ese fin deja el par
     * invertido. Mirando solo el campo que se escribe, eso pasa sin que nadie lo vea, y lo agarra
     * el CHECK de la tabla al volcar — que produce un 500, no el 400 que el contrato promete,
     * porque la traducción de errores solo cubre los conflictos de bloqueo. Es la misma asimetría
     * que la edición ya documenta para su propio camino.
     *
     * <p>Se mide SIEMPRE, también cuando la fecha salió del valor por defecto: un viaje con el
     * inicio en el futuro se cerraría "ahora", o sea antes de haber empezado, y esa es justamente
     * la combinación que se escapa si la validación solo corre cuando el cuerpo trae la fecha.
     *
     * <p>Con la otra marca en null se acepta: no hay nada contra qué comparar. Es alcanzable de
     * verdad —las filas del cutover pueden llegar en ruta y sin inicio— y rechazarlo dejaría
     * viajes reales que no se pueden cerrar por la aplicación. Sanear esa data es del script de
     * migración, no de este endpoint.
     */
    private void requireRealDatesStayOrdered(
            Service service, ServiceStatusTransition transition, OffsetDateTime dateTime) {
        // Las tres que no fechan nada salen antes: comparar las dos fechas GUARDADAS entre si es
        // trabajo sobre un dato que la tabla ya garantiza ordenado (chk_services_times impide
        // almacenar el par invertido — se verifico intentandolo: el UPDATE del fixture rebota).
        // Es decir, la comparacion nunca podia fallar ahi; sale antes para que eso quede escrito y
        // nadie tenga que volver a deducirlo.
        if (transition.dateColumn() == ServiceStatusTransition.DateColumn.NONE) {
            return;
        }
        OffsetDateTime start = transition.dateColumn() == ServiceStatusTransition.DateColumn.START
            ? dateTime : service.startDateTime;
        OffsetDateTime end = transition.dateColumn() == ServiceStatusTransition.DateColumn.END
            ? dateTime : service.endDateTime;
        if (start == null || end == null || !end.isBefore(start)) {
            return;
        }
        throw CommonError.VALIDATION_FAILED.toException(
            transition.dateColumn() == ServiceStatusTransition.DateColumn.END
                ? "La fecha de fin no puede ser anterior a la de inicio"
                : "La fecha de inicio no puede ser posterior a la de fin ya registrada");
    }

    /**
     * Los recursos que el viaje conserva y que, mientras estuvo afuera, se llevo otro viaje.
     *
     * <p>Solo puede pasar al REABRIR, y es el agujero que abre la combinacion de dos decisiones
     * que por separado son correctas: cancelar NO limpia los recursos (para no perder quien estaba
     * asignado) y reabrir devuelve el viaje al estado EXACTO que tenia. En el medio, cancelado deja
     * de retener, asi que otro viaje se puede llevar al conductor sin conflicto y sin forzar.
     * Devolver el primero a un estado que retiene los pone a compartirlo, y sin este chequeo eso
     * pasaria sin que nadie lo decida y sin la linea de bitacora que dice que se forzo.
     *
     * <p>Va DESPUES del lock de la fila, porque la consulta toma ella misma el lock de los
     * recursos y el tope de espera lo puso aquel. Presupuesto: la reapertura pasa a 1 + 3 = 4
     * esperas, que es exactamente {@code MAX_LOCK_WAITS_PER_TRANSACTION} — entra, sin holgura.
     *
     * <p>Las otras cuatro transiciones no lo necesitan: ninguna MOVILIZA recursos. Avanzar de
     * pendiente de inicio a en ruta no cambia quien los tiene, y las dos salidas los liberan.
     */
    private List<ServiceResourceConflictResponse> findConflictsIfTheTripGoesBackToHolding(
            Service service, ChangeServiceStatusCommand command, ServiceStatus target) {
        if (!command.transition().restoresPreviousStatus()
                || !ServiceResourceConflictRepository.RESOURCE_HOLDING_STATUSES.contains(target)) {
            return List.of();
        }
        // Los REFUERZOS del propio viaje tambien vuelven a quedar retenidos, y esta consulta mira
        // solo los tres principales: validar tres y restablecer N seria el mismo agujero que este
        // bloque cierra, entrando por la otra mitad. Se rechaza en vez de mirar de menos.
        //
        // Desde que existe el endpoint de refuerzos esto es ALCANZABLE por la API, con esta
        // secuencia: sumar un refuerzo, cancelar el viaje, intentar reabrirlo. Deja de ser un
        // rechazo defensivo y pasa a ser un callejon sin salida real: ese viaje ya no se reabre.
        //
        // Lo que lo sostiene NO es una regla de negocio sino el presupuesto de esperas de lock.
        // Ampliar la consulta a la lista completa pide una espera por cada recurso DISTINTO del
        // viaje (volver a pedir un lock que la transaccion ya tiene no espera: solo incrementa un
        // contador), o sea 1 por la fila + los principales + los de refuerzo que no repitan. El
        // viaje tipico —tres principales y un relevo de conductor— pide 5, una por encima del
        // techo de MAX_LOCK_WAITS_PER_TRANSACTION, que hoy es 4 (1s x 4 < los 5s que el pool
        // espera por una conexion).
        //
        // Dicho de otro modo: NO hace falta rediseñar el lockeo para levantar esto. Hace falta
        // subir el tope del pool y la constante en la misma proporcion, con la banda recalculada
        // para el peor caso que se decida soportar (cuantos refuerzos por viaje). Sigue siendo un
        // cambio propio —toca configuracion compartida con cotizaciones y almacen— pero es un
        // ajuste de numeros, no una reingenieria.
        if (serviceRepository.countAdditionalResources(service.id) > 0) {
            throw OperationsError.SERVICE_DATA_INCOMPLETE.toException(
                "El viaje tiene recursos de refuerzo y todavía no se puede verificar su "
                    + "disponibilidad al reabrirlo");
        }
        // No se revalida que los recursos sigan de ALTA, a diferencia de la asignacion, y la
        // asimetria es deliberada: asignar ELIGE un recurso —y elegir uno dado de baja es un error
        // que se corrige eligiendo otro— mientras que reabrir RESTAURA una decision ya tomada.
        // Rechazar ahi dejaria un viaje cancelado que no se puede deshacer porque el conductor
        // renuncio, y el remedio (reasignar) exige justamente haberlo reabierto primero.
        ServiceAssignedResourcesRow resources =
            serviceRepository.findAssignedResources(service.id);
        if (resources.driverId() == null || resources.tractorId() == null) {
            // Inalcanzable: requirePreviousStageData acaba de probar lo contrario sobre la MISMA
            // fila, ya lockeada. Se afirma igual porque el modo de falla seria saltear la reja de
            // conflictos en silencio —la consulta sin ids no arma ninguna rama y devuelve vacio—,
            // y este modulo reporta ruidoso en vez de seguir con media guarda.
            throw new IllegalStateException(
                "El viaje " + service.id + " perdio sus recursos entre dos lecturas de la misma "
                    + "transaccion, con la fila lockeada");
        }
        List<ServiceResourceConflictResponse> conflicts = serviceResourceConflicts.find(
            service.id, resources.driverId(), resources.tractorId(), resources.trailerId(),
            resources.driverFullName(), resources.tractorPlate(), resources.trailerPlate());
        if (!conflicts.isEmpty() && !command.force()) {
            throw serviceResourceConflicts.asForcibleConflict(conflicts);
        }
        return conflicts;
    }

    // ---------- Escritura ------------------------------------------------------

    private void applyTransition(Service service, ServiceStatus target,
            ServiceStatusTransition transition, OffsetDateTime dateTime, Integer userId) {
        service.status = target.name();
        switch (transition.dateColumn()) {
            case START -> service.startDateTime = dateTime;
            case END -> service.endDateTime = dateTime;
            // Cancelar y eliminar no fechan el viaje: fechan la decisión, y esa queda en la
            // bitácora con su propia marca de creación.
            case NONE -> { }
        }
        service.updatedBy = userId;
    }

    /**
     * Una fila por COLUMNA cambiada, que es la regla que ya siguen la edición y la asignación: la
     * auditoría existe para reconstruir el estado, así que cada columna que se movió deja su
     * rastro. Una transición mueve el estado y, en dos de los cinco casos, además una fecha.
     *
     * <p>Los valores son los CANÓNICOS, no las etiquetas: el nombre del enum para el estado y el
     * texto UTC para la fecha. La lectura humana ya la cubre la bitácora.
     *
     * <p>El valor anterior de la fecha es el que la fila TENÍA, no un null fijo. Con un null fijo,
     * una fila que ya traía la marca (dato del cutover) quedaba con una auditoría afirmando que
     * antes no había nada, y ese rastro no se puede corregir después.
     */
    private void writeStatusAuditLogs(Service service, ServiceStatus previousStatus,
            ServiceStatus target, List<ServiceResourceConflictResponse> conflicts,
            String previousStart, String previousEnd,
            ChangeServiceStatusCommand command, OffsetDateTime dateTime, Integer userId) {
        String description = auditDescription(command, target, conflicts);
        writeAuditLog(service, "status", "Estado", previousStatus.name(),
            target.name(), description, userId);
        switch (command.transition().dateColumn()) {
            case START -> writeAuditLog(service, "startDateTime", "Inicio real",
                previousStart, ServiceLogText.asUtcText(dateTime), description, userId);
            case END -> writeAuditLog(service, "endDateTime", "Fin real",
                previousEnd, ServiceLogText.asUtcText(dateTime), description, userId);
            case NONE -> { }
        }
    }

    /**
     * El texto base de la transición, con la nota AGREGADA y no reemplazante.
     *
     * <p>Si la nota pisara el texto base, matar un viaje sin que quede constancia de qué se hizo
     * sería tan fácil como escribir cualquier cosa en el motivo.
     */
    private String auditDescription(ChangeServiceStatusCommand command, ServiceStatus target,
            List<ServiceResourceConflictResponse> conflicts) {
        // La auditoria distingue la reapertura FORZADA de la comun, igual que la asignacion. Sin
        // esto, el unico registro de que se piso un conflicto vive en la bitacora, y la auditoria
        // —que es la tabla reconstruible— no lo sabe.
        String base = command.transition().restoresPreviousStatus()
            ? (conflicts.isEmpty()
                ? "Reapertura del viaje, que vuelve a " + ServiceStatusLabels.of(target)
                // Cuenta FILAS y no recursos distintos, a diferencia del 409 que el usuario
                // acaba de ver: acá el número tiene que coincidir con las líneas de bitácora que
                // se escriben abajo, que son una por fila pisada.
                : "Reapertura FORZADA del viaje, que vuelve a " + ServiceStatusLabels.of(target)
                    + " pisando " + conflicts.size() + " conflicto(s) de recursos")
            : "Cambio de estado a " + ServiceStatusLabels.of(target);
        return command.note() == null ? base : base + ". " + command.note();
    }

    private void writeAuditLog(Service service, String fieldName, String fieldLabel,
            String oldValue, String newValue, String description, Integer userId) {
        ServiceAuditLog auditLog = new ServiceAuditLog();
        auditLog.serviceId = service.id;
        auditLog.changedBy = userId;
        auditLog.changeType = ServiceAuditChangeType.STATUS_CHANGE.name();
        auditLog.fieldName = fieldName;
        auditLog.fieldLabel = fieldLabel;
        auditLog.oldValue = oldValue;
        auditLog.newValue = newValue;
        auditLog.description = description;
        serviceAuditLogRepository.persist(auditLog);
    }

    /**
     * UNA entrada de bitácora por transición, con una línea por dato.
     *
     * <p>Todo lo que entra a una línea pasa por {@code ServiceLogText}: la bitácora tiene una línea
     * por dato, así que un salto de línea en la nota plantaría una línea FALSA con el mismo formato
     * que las que escribe el servidor, en un rastro que después nadie edita ni borra.
     *
     * <p>La fecha se escribe en hora de Perú, a diferencia de la auditoría: acá la lee gente.
     *
     * <p><b>Ojo con la línea del forzado</b>: nombra OTRO viaje (su código y su estado) y queda
     * PERSISTIDA, así que la lee todo el que lea el detalle de ESTE — cinco roles, incluido
     * {@code sales}, que ni siquiera entra a este endpoint. Los dos que la escriben son dos. Hoy no
     * filtra nada porque {@code getService} no autoriza por fila: cualquiera de los cinco puede
     * pedir ese otro viaje entero por la puerta de adelante. El día que se agregue autorización por
     * fila —por ejemplo, que ventas vea solo los viajes de sus clientes— hay que filtrar TAMBIÉN la
     * bitácora, porque este canal sobrevive a la respuesta y el del 409 no.
     */
    private void writeStatusChangeEvent(Service service, ServiceStatus previousStatus,
            ServiceStatus target, List<ServiceResourceConflictResponse> conflicts,
            ChangeServiceStatusCommand command, OffsetDateTime dateTime, Integer userId) {
        List<String> lines = new ArrayList<>();
        if (command.transition().restoresPreviousStatus()) {
            lines.add("Reapertura: el viaje vuelve a " + ServiceStatusLabels.of(target));
        }
        lines.add("Estado: " + ServiceStatusLabels.of(previousStatus)
            + " → " + ServiceStatusLabels.of(target));
        switch (command.transition().dateColumn()) {
            case START -> lines.add("Inicio real: " + ServiceLogText.asLimaText(dateTime));
            case END -> lines.add("Fin real: " + ServiceLogText.asLimaText(dateTime));
            case NONE -> { }
        }
        // Si se reabrio pisando un conflicto, cada recurso compartido deja su linea. Forzar sin
        // rastro seria peor que no dejar forzar: el dato quedaria y nadie sabria que se decidio.
        for (ServiceResourceConflictResponse conflict : conflicts) {
            lines.add("Reapertura forzada: " + serviceResourceConflicts.forcedLine(conflict));
        }
        if (command.note() != null) {
            lines.add(command.transition().noteLabel() + ": "
                + ServiceLogText.display(command.note()));
        }

        ServiceEvent event = new ServiceEvent();
        event.serviceId = service.id;
        event.eventType = ServiceEventType.STATUS_CHANGE.name();
        event.note = String.join("\n", lines);
        event.createdBy = userId;
        serviceEventRepository.persist(event);
    }
}
