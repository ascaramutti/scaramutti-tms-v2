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
import com.scaramutti.tms.sharedcatalogs.model.FleetResourceStatus;
import com.scaramutti.tms.workers.WorkersError;
import com.scaramutti.tms.workers.dto.WorkerDetailResponse;
import com.scaramutti.tms.workers.mapper.WorkerServiceMapper;
import com.scaramutti.tms.workers.model.DriverProfileMode;
import com.scaramutti.tms.workers.model.WorkerAuditChangeType;
import com.scaramutti.tms.workers.service.cmd.CreateWorkerCommand;
import com.scaramutti.tms.workers.service.cmd.ListWorkersQuery;
import com.scaramutti.tms.workers.service.cmd.WorkerDriverProfileCommand;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.PersistenceException;
import jakarta.transaction.Transactional;
import com.scaramutti.tms.shared.exception.ConstraintViolations;
import org.hibernate.exception.ConstraintViolationException;
import org.jboss.logging.Logger;

import java.util.List;

/**
 * Trabajadores del catalogo compartido {@code public.workers}: el listado, el detalle y el
 * alta. Las lecturas no abren transaccion (misma convencion que los listados de catalogo de
 * conductores y unidades); el alta si, y es la RAIZ de la suya. El filtro y el orden los
 * resuelve {@link WorkerRepository#search}; el shaping al response vive en el mapper.
 */
@ApplicationScoped
public class WorkerService {

    private static final Logger LOG = Logger.getLogger(WorkerService.class);

    /** Los nombres reales que reporta Postgres; la comparacion es por nombre EXACTO. */
    private static final String DOCUMENT_NUMBER_CONSTRAINT = "workers_document_number_key";
    private static final String LICENSE_NUMBER_CONSTRAINT = "drivers_license_number_key";

    @Inject WorkerRepository workerRepository;
    @Inject DriverRepository driverRepository;
    @Inject RoleRepository roleRepository;
    @Inject DocumentTypeRepository documentTypeRepository;
    @Inject ResourceStatusRepository resourceStatusRepository;
    @Inject WorkerAuditLogRepository workerAuditLogRepository;
    @Inject WorkerDocumentSearchVisibility workerDocumentSearchVisibility;
    @Inject WorkerRankPolicy workerRankPolicy;
    @Inject WorkerServiceMapper workerServiceMapper;
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

    /**
     * La ficha nueva. El estado ausente significa DISPONIBLE, y esa omision se resuelve aca y
     * no en el mapper a proposito: en la edicion el mismo campo ausente va a significar otra
     * cosa, "se conserva el que tenia", y una sola regla en el mapper haria que una de las dos
     * operaciones mienta.
     */
    private Driver newDriver(WorkerDriverProfileCommand driverCommand, Integer workerId) {
        FleetResourceStatus status =
            driverCommand.status() != null ? driverCommand.status() : FleetResourceStatus.AVAILABLE;
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
                WorkersError.DUPLICATE_DOCUMENT, "document_number");
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
                WorkersError.DUPLICATE_LICENSE, "license_number");
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
        ConstraintViolationException cve = ConstraintViolations.of(ex).orElse(null);
        if (cve == null) {
            return ex;
        }
        String constraintName = ConstraintViolations.nameOf(cve);
        if (constraint.equals(constraintName)) {
            LOG.warnf("Race condition: UNIQUE %s violation on worker create", what);
            return duplicateError.toException();
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


}
