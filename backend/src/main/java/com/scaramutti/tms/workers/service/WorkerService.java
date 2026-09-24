package com.scaramutti.tms.workers.service;

import com.scaramutti.tms.auth.security.CurrentUser;
import com.scaramutti.tms.shared.dto.WorkerResponse;
import com.scaramutti.tms.shared.entity.Driver;
import com.scaramutti.tms.shared.entity.DocumentType;
import com.scaramutti.tms.shared.entity.ResourceStatus;
import com.scaramutti.tms.shared.entity.Role;
import com.scaramutti.tms.shared.entity.Worker;
import com.scaramutti.tms.shared.entity.WorkerAuditLog;
import com.scaramutti.tms.shared.repository.DocumentTypeRepository;
import com.scaramutti.tms.shared.repository.DriverRepository;
import com.scaramutti.tms.shared.repository.ResourceStatusRepository;
import com.scaramutti.tms.shared.repository.RoleRepository;
import com.scaramutti.tms.shared.repository.WorkerAuditLogRepository;
import com.scaramutti.tms.shared.repository.WorkerRepository;
import com.scaramutti.tms.shared.util.DateUtils;
import com.scaramutti.tms.sharedcatalogs.model.FleetResourceStatus;
import com.scaramutti.tms.workers.WorkersError;
import com.scaramutti.tms.workers.dto.WorkerDetailResponse;
import com.scaramutti.tms.workers.mapper.WorkerServiceMapper;
import com.scaramutti.tms.shared.entity.User;
import com.scaramutti.tms.shared.repository.UserRepository;
import com.scaramutti.tms.workers.dto.WorkerUpdateRequest;
import com.scaramutti.tms.workers.model.DriverProfileMode;
import com.scaramutti.tms.workers.model.DriverProfileTransition;
import com.scaramutti.tms.workers.model.WorkerAuditChangeType;
import com.scaramutti.tms.workers.model.WorkerAuditField;
import com.scaramutti.tms.workers.model.WorkerFieldChange;
import com.scaramutti.tms.workers.model.WorkerFieldChanges;
import com.scaramutti.tms.workers.service.cmd.CreateWorkerCommand;
import com.scaramutti.tms.workers.service.cmd.ListWorkersQuery;
import com.scaramutti.tms.workers.service.cmd.UpdateWorkerCommand;
import com.scaramutti.tms.workers.service.cmd.WorkerDriverProfileCommand;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.PersistenceException;
import jakarta.transaction.Transactional;
import com.scaramutti.tms.shared.exception.ConstraintViolations;
import org.hibernate.exception.ConstraintViolationException;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Objects;

/**
 * Trabajadores del catalogo compartido {@code public.workers}: el listado, el detalle, el alta,
 * la edicion y el cambio de estado. Las lecturas no abren transaccion (misma convencion que los listados de catalogo
 * de conductores y unidades); las escrituras si, y cada una es la RAIZ de la suya. El filtro y el orden los
 * resuelve {@link WorkerRepository#search}; el shaping al response vive en el mapper.
 */
@ApplicationScoped
public class WorkerService {

    private static final Logger LOG = Logger.getLogger(WorkerService.class);

    /** Los nombres reales que reporta Postgres; la comparacion es por nombre EXACTO. */
    private static final String DOCUMENT_NUMBER_CONSTRAINT = "workers_document_number_key";
    private static final String LICENSE_NUMBER_CONSTRAINT = "drivers_license_number_key";

    /**
     * Las dos que una descarga de la EDICION puede levantar. Es una sola constante y no dos mapas
     * sueltos porque toda descarga de esa operacion vacia el contexto entero: el trabajador, su
     * ficha y su bitacora salen juntos, venga de donde venga el grabado.
     */
    private static final java.util.Map<String, WorkersError> UPDATE_CONSTRAINTS = java.util.Map.of(
        DOCUMENT_NUMBER_CONSTRAINT, WorkersError.DUPLICATE_DOCUMENT,
        LICENSE_NUMBER_CONSTRAINT, WorkersError.DUPLICATE_LICENSE);

    @Inject WorkerRepository workerRepository;
    @Inject DriverRepository driverRepository;
    @Inject RoleRepository roleRepository;
    @Inject DocumentTypeRepository documentTypeRepository;
    @Inject ResourceStatusRepository resourceStatusRepository;
    @Inject WorkerAuditLogRepository workerAuditLogRepository;
    @Inject WorkerDocumentSearchVisibility workerDocumentSearchVisibility;
    @Inject WorkerRankPolicy workerRankPolicy;
    @Inject WorkerServiceMapper workerServiceMapper;
    @Inject UserRepository userRepository;
    @Inject WorkerRowLock workerRowLock;
    @Inject CurrentUser currentUser;

    public List<WorkerResponse> listWorkers(ListWorkersQuery query) {
        return workerServiceMapper.toWorkerResponseList(
            workerRepository.search(query.q(), query.isActive(),
                workerDocumentSearchVisibility.includeDocumentNumber())
        );
    }

    /**
     * El detalle de un trabajador, o WRK-001 si no existe.
     *
     * <p>NO filtra por activo: un trabajador desactivado se abre igual, que es justamente
     * como se lo vuelve a activar. Filtrar aca obligaria a hacerlo por SQL, que es lo que
     * este modulo viene a eliminar.
     */
    public WorkerDetailResponse getWorker(Integer workerId) {
        return workerRepository.findDetailById(workerId)
            .map(workerServiceMapper::toWorkerDetailResponse)
            .orElseThrow(WorkersError.NOT_FOUND::toException);
    }

    /**
     * Da de alta un trabajador y, si su cargo la lleva, su ficha de conductor, EN UNA SOLA
     * TRANSACCION: si la ficha falla, el trabajador no queda. Un trabajador de un cargo que
     * exige ficha sin su ficha no es media alta, es una fila que la pantalla no sabe mostrar
     * y que nadie va a salir a buscar.
     *
     * <p>EL ORDEN DE ESTAS SENTENCIAS ES EL ORDEN PUBLICADO de los errores, y no lo garantiza
     * ningun mecanismo del framework: es el orden en que estan escritas. Los tres primeros
     * escalones (sin sesion, rol sin permiso, cuerpo invalido) los resuelve el contenedor
     * antes de entrar aca. Mover un bloque cambia que error ve quien manda un cuerpo con dos
     * problemas a la vez, y cada par contiguo tiene su caso que lo fija.
     *
     * <p>Los dos chequeos previos de unicidad corren con el contexto de persistencia LIMPIO,
     * antes de cualquier grabado. No es cosmetico: si algo estuviera pendiente de grabar, la
     * descarga automatica que precede a una consulta dispararia la violacion DENTRO de la
     * consulta, fuera del bloque que la traduce, y saldria un 500 sin cuerpo. Se cumple
     * mientras este metodo sea la raiz de la transaccion, que hoy lo es porque su unico
     * llamador es el recurso.
     *
     * <p>La respuesta se RELEE de la fila con la misma consulta del detalle, en vez de armarse
     * con lo que quedo en memoria: asi el 201 y el GET siguiente no pueden discrepar, y lo que
     * puso la base (el id, las marcas de tiempo) viaja como quedo.
     */
    @Transactional
    public WorkerDetailResponse createWorker(CreateWorkerCommand createWorkerCommand) {
        Role role = roleRepository.findByName(createWorkerCommand.role())
            .filter(candidate -> Boolean.TRUE.equals(candidate.isActive))
            .orElseThrow(WorkersError.ROLE_INVALID::toException);

        workerRankPolicy.assertCanActOn(role);

        DocumentType documentType =
            documentTypeRepository.findByIdOptional(createWorkerCommand.documentTypeId())
                .filter(candidate -> Boolean.TRUE.equals(candidate.isActive))
                .orElseThrow(WorkersError.DOCUMENT_TYPE_INVALID::toException);

        assertDocumentNumberFitsItsType(createWorkerCommand.documentNumber(), documentType);

        assertDriverProfileMatchesRole(createWorkerCommand.driver(), role);

        if (workerRepository.existsByDocumentNumber(createWorkerCommand.documentNumber())) {
            throw WorkersError.DUPLICATE_DOCUMENT.toException();
        }
        if (createWorkerCommand.driver() != null
            && driverRepository.existsByLicenseNumber(createWorkerCommand.driver().licenseNumber())) {
            throw WorkersError.DUPLICATE_LICENSE.toException();
        }

        Integer currentUserId = currentUser.requireId();
        Worker worker = newWorker(createWorkerCommand, role, currentUserId);
        persistWorkerOrTranslateDuplicate(worker);

        if (createWorkerCommand.driver() != null) {
            persistDriverOrTranslateDuplicate(newDriver(createWorkerCommand.driver(), worker.id));
        }

        writeCreationAuditLog(worker.id, currentUserId);

        return getWorker(worker.id);
    }

    /**
     * El numero contra las reglas de SU tipo: primero el largo, despues el formato.
     *
     * <p>El formato tiene que coincidir con el numero ENTERO y no con un pedazo. Hoy el
     * padron tiene un solo tipo de documento y NO define formato (medido), asi que esta rama
     * no corre en ningun alta real: existe para el dia que alguien cargue un tipo con formato,
     * y si ese formato viniera sin anclas una coincidencia parcial se veria igual de bien.
     *
     * <p>El largo maximo no se comprueba contra nulo y el formato si, porque las columnas son
     * distintas: el largo es obligatorio y el formato admite nulo. Una guarda de nulo sobre el
     * largo seria una rama que ningun caso puede ejercitar.
     */
    private void assertDocumentNumberFitsItsType(String documentNumber, DocumentType documentType) {
        if (documentNumber.length() > documentType.maxLength) {
            throw WorkersError.DOCUMENT_NUMBER_INVALID.toException();
        }
        if (documentType.validationPattern != null
            && !documentNumber.matches(documentType.validationPattern)) {
            throw WorkersError.DOCUMENT_NUMBER_INVALID.toException();
        }
    }

    /**
     * La ficha contra la modalidad del cargo. Solo mira si la ficha ESTA o NO esta: lo que
     * traiga adentro ya lo valido el borde, y mezclar las dos cosas es lo que haria que una
     * licencia en blanco saliera con el error de correspondencia en vez del de formato.
     */
    private void assertDriverProfileMatchesRole(WorkerDriverProfileCommand driver, Role role) {
        DriverProfileMode mode = DriverProfileMode.fromColumn(role.driverProfile);
        boolean mismatch = (mode == DriverProfileMode.REQUIRED && driver == null)
            || (mode == DriverProfileMode.NONE && driver != null);
        if (mismatch) {
            throw WorkersError.DRIVER_PROFILE_MISMATCH.toException();
        }
    }

    private Worker newWorker(CreateWorkerCommand createWorkerCommand, Role role, Integer currentUserId) {
        Worker worker = new Worker();
        worker.firstName = createWorkerCommand.firstName();
        worker.lastName = createWorkerCommand.lastName();
        worker.documentTypeId = createWorkerCommand.documentTypeId();
        worker.documentNumber = createWorkerCommand.documentNumber();
        worker.phone = createWorkerCommand.phone();
        worker.role = role;
        worker.hireDate = createWorkerCommand.hireDate();
        worker.createdBy = currentUserId;
        worker.updatedBy = currentUserId;
        return worker;
    }

    /** La disponibilidad con que nace una ficha: la pedida, o disponible si no vino. */
    private static FleetResourceStatus statusOfNewProfile(WorkerDriverProfileCommand driverCommand) {
        return driverCommand.status() != null ? driverCommand.status() : FleetResourceStatus.AVAILABLE;
    }

    /**
     * La ficha nueva. El estado ausente significa DISPONIBLE, y esa omision se resuelve aca y
     * no en el mapper a proposito: en la edicion el mismo campo ausente va a significar otra
     * cosa, "se conserva el que tenia", y una sola regla en el mapper haria que una de las dos
     * operaciones mienta.
     */
    private Driver newDriver(WorkerDriverProfileCommand driverCommand, Integer workerId) {
        FleetResourceStatus status = statusOfNewProfile(driverCommand);
        ResourceStatus resourceStatus =
            resourceStatusRepository.findByNameIgnoringCase(status.name())
                .orElseThrow(() -> new IllegalStateException(
                    "public.resource_statuses has no row named " + status.name()));

        Driver driver = new Driver();
        driver.workerId = workerId;
        driver.licenseNumber = driverCommand.licenseNumber();
        driver.licenseCategory = driverCommand.licenseCategory();
        driver.statusId = resourceStatus.id;
        return driver;
    }

    /**
     * Una sola fila, sin campo y sin valores: el alta no cambio nada, lo creo. Las filas por
     * campo son de la edicion, y el catalogo de etiquetas que necesitan nace con ella.
     */
    private void writeCreationAuditLog(Integer workerId, Integer currentUserId) {
        WorkerAuditLog workerAuditLog = new WorkerAuditLog();
        workerAuditLog.workerId = workerId;
        workerAuditLog.changeType = WorkerAuditChangeType.CREATED.name();
        workerAuditLog.changedBy = currentUserId;
        // SIN descarga propia, a diferencia de las otras dos escrituras, y el motivo vale
        // escribirlo porque la simetria invita a agregarla: el id es generado por la base, asi
        // que el INSERT ya sale en esta linea, y una falla de esta fila no es traducible a
        // ningun codigo de negocio. Una descarga aca no cambiaria ni cuando falla ni como sale.
        workerAuditLogRepository.persist(workerAuditLog);
    }

    /**
     * Graba y descarga. Cubre la carrera que el chequeo previo no puede cubrir: dos altas que
     * pasan el chequeo a la vez y chocan recien contra el indice de la base. Sin esto seria un
     * 500 sin cuerpo.
     */
    private void persistWorkerOrTranslateDuplicate(Worker worker) {
        try {
            workerRepository.persist(worker);
            workerRepository.flush();
        } catch (PersistenceException ex) {
            throw translateDuplicateOrRethrow(ex, DOCUMENT_NUMBER_CONSTRAINT,
                WorkersError.DUPLICATE_DOCUMENT, "create");
        }
    }

    /**
     * Idem para la ficha. Si esto falla, la excepcion atraviesa la transaccion y deshace
     * TAMBIEN el trabajador ya grabado, que es el punto de que las dos escrituras sean una.
     *
     * <p>La otra unicidad de la tabla, la del trabajador, no puede violarse aca: el trabajador
     * al que se le cuelga la ficha se acaba de crear en esta misma transaccion, asi que no
     * puede tener otra. No se traduce lo que no puede pasar.
     */
    private void persistDriverOrTranslateDuplicate(Driver driver) {
        try {
            driverRepository.persist(driver);
            driverRepository.flush();
        } catch (PersistenceException ex) {
            throw translateDuplicateOrRethrow(ex, LICENSE_NUMBER_CONSTRAINT,
                WorkersError.DUPLICATE_LICENSE, "create");
        }
    }

    /** La ficha que nace durante una EDICION: su descarga puede levantar cualquiera de las dos. */
    private void persistDriverTranslatingBoth(Driver driver) {
        try {
            driverRepository.persist(driver);
            driverRepository.flush();
        } catch (PersistenceException ex) {
            throw translateDuplicateOrRethrow(ex, UPDATE_CONSTRAINTS, "update");
        }
    }

    /**
     * Traduce UNA violacion conocida a su codigo de negocio, o devuelve la original.
     *
     * <p>La comparacion es por nombre EXACTO y nunca por fragmento. La descarga vacia el
     * contexto de persistencia entero, no solo la entidad que se estaba grabando, asi que
     * puede aflorar aca la violacion de otro modulo; con un fragmento como "document" o
     * "license", el numero repetido de un proveedor saldria disfrazado de "ya existe un
     * trabajador con ese documento".
     *
     * <p>El aviso lleva el numero de la restriccion y NADA de la persona: ni documento, ni
     * telefono, ni licencia. En produccion este nivel se emite, y esos tres son datos de
     * alguien.
     *
     * <p>Devuelve la excepcion en vez de lanzarla para que quien llama escriba {@code throw} y
     * el compilador vea que el flujo termina ahi.
     */
    private RuntimeException translateDuplicateOrRethrow(PersistenceException ex,
        String constraint, WorkersError duplicateError, String what) {
        return translateDuplicateOrRethrow(ex, java.util.Map.of(constraint, duplicateError), what);
    }

    /**
     * La misma traduccion cuando la descarga puede levantar CUALQUIERA de varias restricciones.
     *
     * <p>El alta descarga dos veces, una por tabla, asi que cada una sabe cual espera. La edicion
     * descarga UNA sola vez y esa descarga vacia el contexto entero, asi que puede aflorar la del
     * documento o la de la licencia sin que el codigo sepa de antemano cual.
     */
    private RuntimeException translateDuplicateOrRethrow(PersistenceException ex,
        java.util.Map<String, WorkersError> knownConstraints, String what) {
        ConstraintViolationException cve = ConstraintViolations.of(ex).orElse(null);
        if (cve == null) {
            return ex;
        }
        String constraintName = ConstraintViolations.nameOf(cve);
        WorkersError known = constraintName == null ? null : knownConstraints.get(constraintName);
        if (known != null) {
            // El nombre de la restriccion y la operacion, y NADA de la persona: este nivel
            // se emite en produccion y el mensaje del servidor trae el valor que choco.
            LOG.warnf("Race condition: UNIQUE %s violation on worker %s", constraintName, what);
            return known.toException();
        }
        // SIN la excepcion como argumento, y no es un descuido: el mensaje del servidor trae
        // el VALOR que choco ("Key (document_number)=(...) already exists"), asi que volcar la
        // cadena de causas escribiria el documento de una persona en el registro de produccion,
        // que es justo lo que el aviso de arriba se cuida de no hacer. El estado SQL y el nombre
        // de la restriccion alcanzan para que operaciones sepa que paso.
        LOG.errorf("Unhandled DB constraint violation [constraint=%s, sqlState=%s]",
            constraintName, cve.getSQLState());
        return ex;
    }



    /**
     * Edita un trabajador, su ficha de conductor y, si tiene usuario, el rol de ese usuario, EN
     * UNA SOLA TRANSACCION.
     *
     * <p>Es un REEMPLAZO y no un parche: cada campo del cuerpo pisa al guardado, y un opcional
     * que no viene queda vacio. Por eso no hay comprobaciones de nulo antes de asignar; tratarlas
     * como "no lo mandaron, no lo toco" convertiria el endpoint en otra cosa y borrar un telefono
     * dejaria de ser posible.
     *
     * <p>EL ORDEN DE ESTAS SENTENCIAS ES EL ORDEN PUBLICADO. Los tres primeros escalones los
     * resuelve el contenedor antes de entrar. Cada par contiguo tiene su caso, porque mover un
     * bloque cambia que error ve quien manda un cuerpo con dos problemas a la vez.
     *
     * <p>LA FILA SE TOMA CON BLOQUEO ANTES DE DECIDIR NADA, y eso no es cosmetico: todo lo que
     * sigue decide sobre un estado que una desactivacion simultanea cambia (el cargo guardado, la
     * fila del usuario, la de la ficha). Tomar el bloqueo relee la fila, asi que tiene que ocurrir
     * antes de tocar la entidad: quien mute primero y bloquee despues pierde su cambio sin ningun
     * error y deja un rastro que afirma algo que en la fila no esta.
     *
     * <p>DEUDA ANOTADA: la fila de la CUENTA se lee SIN bloqueo, se le mide el rango y se escribe
     * al final. Hoy no es alcanzable —el unico otro escritor de esa columna es el sembrador de dev,
     * y dos ediciones del mismo trabajador ya se serializan en el bloqueo de arriba— pero el primer
     * endpoint del modulo de usuarios que la escriba convierte esto en un rango medido sobre un
     * valor que otra transaccion movio. El presupuesto de esperas YA cuenta esa fila, asi que
     * tomarla con bloqueo no obliga a recontar. El disparador es ese, y esta escrito ACA porque es
     * donde lo va a leer quien toque este metodo: un puntero a un documento fuera del repositorio
     * seria una deuda que solo existe si alguien encuentra el documento.
     */
    @Transactional
    public WorkerDetailResponse updateWorker(UpdateWorkerCommand updateWorkerCommand) {
        Worker worker = workerRowLock.findByIdForUpdate(updateWorkerCommand.workerId());

        workerRankPolicy.assertCanActOn(worker.role);

        Role newRole = roleRepository.findByName(updateWorkerCommand.role())
            .filter(candidate -> Boolean.TRUE.equals(candidate.isActive))
            .orElseThrow(WorkersError.ROLE_INVALID::toException);

        workerRankPolicy.assertCanActOn(newRole);

        // La cuenta se carga ANTES de la guarda del cargo propio porque esa guarda mira las DOS
        // filas que esta operacion mueve. Cargar no es comprobar: el orden de errores publicado
        // sigue siendo WRK-006 (actual) -> WRK-005 -> WRK-006 (nuevo) -> WRK-012 -> WRK-006
        // (cuenta) -> WRK-011.
        User user = userRepository.findByWorkerIdOptional(worker.id).orElse(null);
        workerRankPolicy.assertCanChangeRoleOf(worker, user, newRole);
        if (user != null) {
            // Tambien sobre el rol de la CUENTA, que es la fila que esta operacion escribe. Hoy
            // siempre coincide con el del trabajador, pero NADA lo obliga: no hay restriccion que
            // los ate, y el modulo de usuarios que viene puede asignar el rol de la cuenta por su
            // lado. Sin esto, la jerarquia se mediria sobre una fila y la escritura caeria sobre
            // otra. Mientras coincidan, esta linea no cambia ninguna respuesta.
            workerRankPolicy.assertCanActOn(user.role);
        }
        assertUserKeepsLogin(user, newRole);

        DocumentType newDocumentType =
            documentTypeRepository.findByIdOptional(updateWorkerCommand.documentTypeId())
                .filter(candidate -> Boolean.TRUE.equals(candidate.isActive))
                .orElseThrow(WorkersError.DOCUMENT_TYPE_INVALID::toException);

        assertDocumentNumberFitsItsType(updateWorkerCommand.documentNumber(), newDocumentType);

        Driver existingDriver = driverRepository.findByWorkerIdOptional(worker.id).orElse(null);
        DriverProfileTransition transition = DriverProfileTransition.decide(
            existingDriver != null,
            existingDriver != null && Boolean.TRUE.equals(existingDriver.isActive),
            DriverProfileMode.fromColumn(newRole.driverProfile),
            updateWorkerCommand.driver() != null,
            Boolean.TRUE.equals(worker.isActive));
        if (transition == DriverProfileTransition.REJECT) {
            throw WorkersError.DRIVER_PROFILE_MISMATCH.toException();
        }

        assertReasonWhenDocumentChanged(updateWorkerCommand, worker);

        if (workerRepository.existsByDocumentNumberExcluding(
                updateWorkerCommand.documentNumber(), worker.id)) {
            throw WorkersError.DUPLICATE_DOCUMENT.toException();
        }
        // La condicion es "el valor se va a ESCRIBIR", no "vino ficha". Hoy las dos coinciden: desde
        // que la ficha nace apagada sobre un dado de baja, ningun cuerpo con ficha que pase el
        // rechazo deja de escribirse. Se conserva la condicion declarada porque es la que tiene
        // que seguir valiendo el dia que una transicion nueva descarte datos: si ese dia vuelve a
        // aparecer, comparar una licencia descartada haria fallar la edicion por un duplicado que
        // nunca se iba a guardar.
        if (transition.writesBodyData()
            && driverRepository.existsByLicenseNumberExcludingWorker(
                updateWorkerCommand.driver().licenseNumber(), worker.id)) {
            throw WorkersError.DUPLICATE_LICENSE.toException();
        }

        Integer currentUserId = currentUser.requireId();
        WorkerFieldChanges changes = new WorkerFieldChanges();
        // TODA la fase de escritura bajo el traductor del bloqueo, no solo la descarga final. La
        // edicion DESCARGA MAS DE UNA VEZ: la ficha que nace se graba con una descarga explicita
        // propia, y esa descarga vacia el contexto ENTERO, asi que arrastra el UPDATE del
        // trabajador que ya quedo sucio. Con el traductor solo al final, un choque de bloqueo o
        // una carrera del documento en ese punto salian como error del servidor, y la MISMA
        // situacion respondia distinto segun el trabajador tuviera ficha o no.
        //
        // Esa descarga explicita NO es redundante y no se saca: el id generado por la base emite
        // en el acto SOLO el INSERT de la ficha —medido, no supuesto— asi que sin ella la
        // violacion del documento saldria mucho despues, fuera del traductor que la nombra.
        workerRowLock.runTranslatingLockConflicts(() -> {
            applyWorkerChanges(worker, updateWorkerCommand, newRole, newDocumentType, changes, currentUserId);
            applyDriverProfile(worker, existingDriver, transition, updateWorkerCommand.driver(), changes);
            applyUserRole(user, newRole, changes);

            writeAuditLogs(worker.id, WorkerAuditChangeType.FIELD_EDIT, changes.asList(),
                updateWorkerCommand.reason(), currentUserId);
            flushTranslatingDuplicates();
            return null;
        }, worker.id);

        return getWorker(worker.id);
    }

    /**
     * Desactiva al trabajador y, en la MISMA transaccion, a su ficha y a su cuenta si existen.
     * Las guardas corren ANTES del corte idempotente: repetir sobre alguien fuera de rango es un
     * 403. Y la repeticion no escribe nada, ni la marca de modificacion: una firma sin fila de
     * rastro que la respalde afirmaria un cambio que no hubo.
     */
    @Transactional
    public WorkerDetailResponse deactivateWorker(Integer workerId) {
        Worker worker = lockTargetAndOwnWorker(workerId);
        workerRankPolicy.assertIsNotOwnWorker(worker);
        User user = userRepository.findByWorkerIdOptional(worker.id).orElse(null);
        assertCanChangeStatusOf(worker, user);
        if (!Boolean.TRUE.equals(worker.isActive)) {
            return getWorker(worker.id);
        }

        Driver driver = driverRepository.findByWorkerIdOptional(worker.id).orElse(null);
        Integer currentUserId = currentUser.requireId();
        WorkerFieldChanges changes = new WorkerFieldChanges();
        workerRowLock.runTranslatingLockConflicts(() -> {
            changes.compare(WorkerAuditField.IS_ACTIVE, "true", "false");
            worker.isActive = false;
            worker.updatedBy = currentUserId;
            // La cascada solo audita lo que MOVIO: una ficha o una cuenta que ya estaban
            // apagadas no dejan fila, porque no cambiaron.
            // Una asignacion de viaje o un retiro de almacen simultaneos leen la vigencia sin
            // bloqueo, y pueden quedar a nombre de alguien ya dado de baja. Se acepta: el estado
            // final es el de registrar y despues desactivar (no el mismo orden en el rastro), y la
            // baja de un conductor con viajes pendientes esta permitida por decision de producto.
            if (driver != null && Boolean.TRUE.equals(driver.isActive)) {
                changes.compare(WorkerAuditField.DRIVER_IS_ACTIVE, "true", "false");
                driver.isActive = false;
            }
            if (user != null && Boolean.TRUE.equals(user.isActive)) {
                changes.compare(WorkerAuditField.USER_IS_ACTIVE, "true", "false");
                user.isActive = false;
            }
            writeAuditLogs(worker.id, WorkerAuditChangeType.DEACTIVATED, changes.asList(), null,
                currentUserId);
            workerRepository.flush();
            return null;
        }, worker.id);

        return getWorker(worker.id);
    }

    /**
     * Reactiva al trabajador y enciende su ficha solo si existe y el cargo actual la lleva. La
     * cuenta NO: eso lo decide el modulo de usuarios, que exigira trabajador activo. Tampoco
     * crea una ficha que no existe; si el cargo la exige, el siguiente PUT la pide.
     */
    @Transactional
    public WorkerDetailResponse reactivateWorker(Integer workerId) {
        Worker worker = workerRowLock.findByIdForUpdate(workerId);
        User user = userRepository.findByWorkerIdOptional(worker.id).orElse(null);
        assertCanChangeStatusOf(worker, user);
        if (Boolean.TRUE.equals(worker.isActive)) {
            return getWorker(worker.id);
        }

        Driver driver = driverRepository.findByWorkerIdOptional(worker.id).orElse(null);
        boolean roleCarriesProfile =
            DriverProfileMode.fromColumn(worker.role.driverProfile) != DriverProfileMode.NONE;
        Integer currentUserId = currentUser.requireId();
        WorkerFieldChanges changes = new WorkerFieldChanges();
        workerRowLock.runTranslatingLockConflicts(() -> {
            changes.compare(WorkerAuditField.IS_ACTIVE, "false", "true");
            worker.isActive = true;
            worker.updatedBy = currentUserId;
            if (driver != null && !Boolean.TRUE.equals(driver.isActive) && roleCarriesProfile) {
                changes.compare(WorkerAuditField.DRIVER_IS_ACTIVE, "false", "true");
                driver.isActive = true;
            }
            writeAuditLogs(worker.id, WorkerAuditChangeType.REACTIVATED, changes.asList(), null,
                currentUserId);
            workerRepository.flush();
            return null;
        }, worker.id);

        return getWorker(worker.id);
    }

    /**
     * Toma la fila del destino Y la del trabajador de la sesion, en orden de id, antes de validar
     * al actor. Sin la segunda, dos administradores que se desactivan entre si a la vez pasaban los
     * dos la validacion y quedaban los dos apagados; con ella, el segundo espera y encuentra su
     * propia cuenta ya apagada. El orden por id es lo que impide el abrazo mortal.
     */
    private Worker lockTargetAndOwnWorker(Integer targetId) {
        Integer ownWorkerId = userRepository.findWorkerIdByUserId(currentUser.requireId()).orElse(null);
        if (ownWorkerId == null || ownWorkerId.equals(targetId)) {
            return workerRowLock.findByIdForUpdate(targetId);
        }
        if (ownWorkerId < targetId) {
            workerRowLock.findByIdForUpdate(ownWorkerId);
            return workerRowLock.findByIdForUpdate(targetId);
        }
        Worker target = workerRowLock.findByIdForUpdate(targetId);
        workerRowLock.findByIdForUpdate(ownWorkerId);
        return target;
    }

    /**
     * El rango del cambio de estado: el cargo ACTUAL del trabajador y, si tiene cuenta, el de la
     * cuenta, que es la que da los permisos. Mismo criterio que la edicion.
     */
    private void assertCanChangeStatusOf(Worker worker, User user) {
        workerRankPolicy.assertCanActOn(worker.role);
        if (user != null) {
            workerRankPolicy.assertCanActOn(user.role);
        }
    }

    /**
     * Una cuenta no puede quedar con un cargo que no inicia sesion: iniciaria igual, recibiria un
     * token con un grupo que ningun endpoint acepta y el frontend no sabria donde llevarla. El
     * camino para dejar a alguien sin acceso es darlo de baja, que apaga la cuenta y deja rastro.
     *
     * <p>Solo aplica si el cargo DE LA CUENTA cambia. Sin esa condicion, alguien que ya estuviera
     * en ese estado no se podria editar nunca mas, ni para corregirle una errata en el telefono.
     * Que la condicion mire la cuenta tiene un precio escrito: si las dos filas divergen y la
     * cuenta esta en un cargo con sesion mientras el trabajador esta en uno sin ella, todo cuerpo
     * que reenvie el del trabajador sale por aca, y esa ficha no se puede editar sin moverle
     * tambien el cargo. Es deliberado: converger o rechazar, nunca mover la cuenta en silencio.
     *
     * <p>Y cuenta que la fila de usuario EXISTA, no que este vigente: una cuenta apagada se puede
     * volver a encender, y filtrarla aca dejaria armada justo la cuenta rota que esto previene.
     */
    private void assertUserKeepsLogin(User user, Role newRole) {
        // Se compara contra el rol de la CUENTA por el mismo motivo que la cascada: la cuenta es
        // la que termina con el cargo nuevo, asi que es la que decide si el cambio la rompe.
        // Comparar el del trabajador dejaria pasar el caso en que los dos difieren y el cuerpo
        // reenvia el del trabajador: la cuenta se moveria a un cargo sin sesion sin que nadie lo
        // mire.
        if (user == null || user.role.id.equals(newRole.id)) {
            return;
        }
        if (!Boolean.TRUE.equals(newRole.canLogin)) {
            throw WorkersError.ROLE_CANNOT_LOGIN_WITH_USER.toException();
        }
    }

    /**
     * Cambiar el numero de documento de una persona exige decir por que.
     *
     * <p>El minimo se mide ACA y no en el borde porque es condicional: en cualquier otro cambio el
     * motivo es opcional y libre, y una regla del borde lo exigiria tambien ahi. El motivo ya
     * llega recortado, asi que diez espacios llegan nulos y no pasan por justificacion.
     */
    private void assertReasonWhenDocumentChanged(UpdateWorkerCommand command, Worker worker) {
        if (Objects.equals(worker.documentNumber, command.documentNumber())) {
            return;
        }
        String reason = command.reason();
        if (reason == null || reason.length() < WorkerUpdateRequest.MIN_REASON_LENGTH) {
            throw WorkersError.DOCUMENT_CHANGE_REASON_REQUIRED.toException();
        }
    }

    /**
     * Pisa los campos del trabajador y anota los que cambiaron.
     *
     * <p>El cargo se anota por su NOMBRE DE SISTEMA y el tipo de documento por su CODIGO, nunca
     * por su id ni por su nombre visible: el id no se lee, y el nombre visible lo cambia una
     * migracion, y entonces el historial pasaria a afirmar algo que nunca ocurrio.
     */
    private void applyWorkerChanges(Worker worker, UpdateWorkerCommand command, Role newRole,
            DocumentType newDocumentType, WorkerFieldChanges changes, Integer currentUserId) {
        changes.compare(WorkerAuditField.FIRST_NAME, worker.firstName, command.firstName());
        changes.compare(WorkerAuditField.LAST_NAME, worker.lastName, command.lastName());
        changes.compare(WorkerAuditField.DOCUMENT_TYPE,
            documentTypeRepository.findByIdOptional(worker.documentTypeId)
                .map(type -> type.code).orElse(null), newDocumentType.code);
        changes.compare(WorkerAuditField.DOCUMENT_NUMBER, worker.documentNumber, command.documentNumber());
        changes.compare(WorkerAuditField.PHONE, worker.phone, command.phone());
        changes.compare(WorkerAuditField.ROLE, worker.role.name, newRole.name);
        changes.compare(WorkerAuditField.HIRE_DATE,
            String.valueOf(worker.hireDate), String.valueOf(command.hireDate()));

        worker.firstName = command.firstName();
        worker.lastName = command.lastName();
        worker.documentTypeId = command.documentTypeId();
        worker.documentNumber = command.documentNumber();
        worker.phone = command.phone();
        worker.role = newRole;
        worker.hireDate = command.hireDate();
        // La sesion firma SIEMPRE, incluso si nada cambio: es la regla publicada, y quien mira la
        // fila tiene que poder saber quien la toco por ultima vez aunque no la haya movido.
        worker.updatedBy = currentUserId;
        // Se toca la marca a mano y no se deja al callback: el callback dispara solo cuando la
        // entidad quedo sucia, y una edicion que no cambio nada NO la ensucia. Esta linea es lo
        // que hace que un cuerpo identico igual mueva la marca, que es la regla publicada. El
        // callback la vuelve a poner con su propio instante, y esta bien: lo que importa es que
        // la escritura ocurra.
        worker.updatedAt = DateUtils.nowUtcMicros();
    }

    /** Lleva la ficha al estado que decidio la transicion, y anota lo que cambio de ella. */
    private void applyDriverProfile(Worker worker, Driver existingDriver,
            DriverProfileTransition transition, WorkerDriverProfileCommand driverCommand,
            WorkerFieldChanges changes) {
        switch (transition) {
            case NOTHING, STAY_OFF -> { }
            case CREATE, CREATE_OFF -> {
                // La ficha que NACE en una edicion deja su rastro igual que la que se reactiva:
                // el efecto de negocio es el mismo (esa persona pasa a estar en el catalogo de
                // conductores) y sin esto el mismo cambio dejaria dos historiales distintos
                // segun por donde llego. La que nace APAGADA, sobre alguien dado de baja, no deja
                // la fila del encendido porque no se encendio nada: un rastro que dijera "true"
                // afirmaria algo que la fila no tiene.
                boolean driverBornActive = transition == DriverProfileTransition.CREATE;
                Driver newProfile = newDriver(driverCommand, worker.id);
                newProfile.isActive = driverBornActive;
                if (driverBornActive) {
                    changes.compare(WorkerAuditField.DRIVER_IS_ACTIVE, null, "true");
                }
                changes.compare(WorkerAuditField.DRIVER_LICENSE_NUMBER, null, driverCommand.licenseNumber());
                changes.compare(WorkerAuditField.DRIVER_LICENSE_CATEGORY, null, driverCommand.licenseCategory());
                // Y la disponibilidad con que nace, la pedida o la de omision: es un dato que la
                // fila tiene desde el primer momento y sin esto el historial no dice cual.
                changes.compare(WorkerAuditField.DRIVER_STATUS, null, statusOfNewProfile(driverCommand).name());
                // Por el TRADUCTOR y no con un grabado pelado: el id lo genera la base, asi que
                // el INSERT sale aca y no en la descarga final. Sin esto, una carrera de licencia
                // en este camino sale como error del servidor en vez del conflicto del contrato.
                // Con el mapa de LAS DOS restricciones, no solo la de la licencia: esta descarga
                // vacia el contexto entero, y el trabajador ya quedo sucio con su documento nuevo.
                // El alta puede traducir una sola porque alli el trabajador se acaba de crear y no
                // puede chocar con nadie; en la edicion ese supuesto es falso.
                persistDriverTranslatingBoth(newProfile);
            }
            case DEACTIVATE -> {
                changes.compare(WorkerAuditField.DRIVER_IS_ACTIVE, "true", "false");
                existingDriver.isActive = false;
            }
            case UPDATE, REACTIVATE, UPDATE_OFF -> {
                changes.compare(WorkerAuditField.DRIVER_LICENSE_NUMBER,
                    existingDriver.licenseNumber, driverCommand.licenseNumber());
                changes.compare(WorkerAuditField.DRIVER_LICENSE_CATEGORY,
                    existingDriver.licenseCategory, driverCommand.licenseCategory());
                if (driverCommand.status() != null) {
                    ResourceStatus newStatus = requireResourceStatus(driverCommand.status());
                    changes.compare(WorkerAuditField.DRIVER_STATUS,
                        resourceStatusNameOf(existingDriver.statusId), driverCommand.status().name());
                    existingDriver.statusId = newStatus.id;
                }
                if (transition == DriverProfileTransition.REACTIVATE) {
                    changes.compare(WorkerAuditField.DRIVER_IS_ACTIVE, "false", "true");
                    existingDriver.isActive = true;
                }
                if (transition == DriverProfileTransition.UPDATE_OFF
                    && Boolean.TRUE.equals(existingDriver.isActive)) {
                    // Los datos se escriben, pero la ficha de quien se fue se apaga igual.
                    changes.compare(WorkerAuditField.DRIVER_IS_ACTIVE, "true", "false");
                    existingDriver.isActive = false;
                }
                existingDriver.licenseNumber = driverCommand.licenseNumber();
                existingDriver.licenseCategory = driverCommand.licenseCategory();
            }
            default -> throw new IllegalStateException("transicion de ficha sin resolver: " + transition);
        }
    }

    /**
     * Si el cargo cambio y hay usuario, su rol cambia en la MISMA transaccion.
     *
     * <p>Los permisos rigen en el proximo inicio de sesion o refresco: la sesion abierta conserva
     * el rol viejo hasta que venza su token, que es una ventana aceptada y escrita.
     */
    private void applyUserRole(User user, Role newRole, WorkerFieldChanges changes) {
        // Se compara contra el rol de la CUENTA y no contra el del trabajador, porque es el que
        // esta linea escribe y audita. Hoy los dos coinciden siempre, pero la guarda de rango de
        // mas arriba existe justamente porque nada lo obliga: si divergieran, comparar el del
        // trabajador dejaria la cuenta con su rol viejo y sin fila de rastro.
        if (user == null || user.role.id.equals(newRole.id)) {
            return;
        }
        changes.compare(WorkerAuditField.USER_ROLE, user.role.name, newRole.name);
        user.role = newRole;
    }

    /** Una fila por campo cambiado, todas con el mismo tipo de cambio y el mismo motivo si lo hay. */
    private void writeAuditLogs(Integer workerId, WorkerAuditChangeType changeType,
            List<WorkerFieldChange> changes, String reason, Integer currentUserId) {
        for (WorkerFieldChange change : changes) {
            WorkerAuditLog log = new WorkerAuditLog();
            log.workerId = workerId;
            log.changeType = changeType.name();
            log.fieldName = change.field().fieldName();
            log.fieldLabel = change.field().label();
            log.oldValue = change.oldValue();
            log.newValue = change.newValue();
            log.reason = reason;
            log.changedBy = currentUserId;
            workerAuditLogRepository.persist(log);
        }
    }

    /**
     * La descarga FINAL de la edicion —no la unica: la ficha que nace tiene la suya— y puede
     * levantar cualquiera de las dos restricciones porque vacia el contexto entero. Cubre la
     * carrera que los chequeos previos no pueden.
     *
     * <p>Va envuelta por el traductor del bloqueo ademas del de duplicados: el tope de espera rige
     * TODA la transaccion, asi que esta escritura tambien puede rendirse por un conflicto de
     * bloqueo, y sin traducirlo el error del servidor vuelve por la ventana.
     */
    private void flushTranslatingDuplicates() {
        try {
            workerRepository.flush();
        } catch (PersistenceException ex) {
            throw translateDuplicateOrRethrow(ex, UPDATE_CONSTRAINTS, "update");
        }
    }

    /** El estado del catalogo por su nombre de dominio; sin fila es una base rota, no un 4xx. */
    private ResourceStatus requireResourceStatus(FleetResourceStatus status) {
        return resourceStatusRepository.findByNameIgnoringCase(status.name())
            .orElseThrow(() -> new IllegalStateException(
                "public.resource_statuses has no row named " + status.name()));
    }

    /** El nombre de dominio del estado guardado, para el rastro. */
    private String resourceStatusNameOf(Integer statusId) {
        return resourceStatusRepository.findByIdOptional(statusId)
            .map(status -> FleetResourceStatus.fromCatalogName(status.name).name())
            .orElse(null);
    }

}
