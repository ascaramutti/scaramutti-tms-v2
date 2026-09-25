package com.scaramutti.tms.workers.service;

import com.scaramutti.tms.auth.security.CurrentUser;
import com.scaramutti.tms.workers.mapper.WorkerServiceMapper;
import com.scaramutti.tms.shared.dto.WorkerResponse;
import com.scaramutti.tms.shared.entity.DocumentType;
import com.scaramutti.tms.shared.entity.Role;
import com.scaramutti.tms.shared.entity.Worker;
import com.scaramutti.tms.shared.exception.ApiException;
import com.scaramutti.tms.shared.repository.DocumentTypeRepository;
import com.scaramutti.tms.shared.repository.DriverRepository;
import com.scaramutti.tms.shared.repository.ResourceStatusRepository;
import com.scaramutti.tms.shared.repository.RoleRepository;
import com.scaramutti.tms.shared.repository.WorkerAuditLogRepository;
import com.scaramutti.tms.shared.repository.WorkerRepository;
import com.scaramutti.tms.workers.service.cmd.CreateWorkerCommand;
import com.scaramutti.tms.workers.service.cmd.ListWorkersQuery;
import com.scaramutti.tms.workers.service.cmd.UpdateWorkerCommand;
import com.scaramutti.tms.workers.service.cmd.WorkerDriverProfileCommand;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mapstruct.factory.Mappers;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests del service de trabajadores. Cubre la delegacion del filtro al repo y el
 * mapeo entidad a response (fullName compuesto por la entity). El SQL real (multi-palabra,
 * isActive, orden) lo cubren los integration tests (WorkersResourceTest).
 */
@ExtendWith(MockitoExtension.class)
class WorkerServiceTest {

    @Mock WorkerRepository workerRepository;
    @Mock DriverRepository driverRepository;
    @Mock RoleRepository roleRepository;
    @Mock DocumentTypeRepository documentTypeRepository;
    @Mock ResourceStatusRepository resourceStatusRepository;
    @Mock WorkerAuditLogRepository workerAuditLogRepository;
    @Mock WorkerDocumentSearchVisibility workerDocumentSearchVisibility;
    @Mock WorkerRankPolicy workerRankPolicy;
    @Mock com.scaramutti.tms.shared.repository.UserRepository userRepository;
    @Mock WorkerRowLock workerRowLock;
    @Mock CurrentUser currentUser;
    @InjectMocks WorkerService workerService;

    // El mapper es un colaborador REAL (impl generada por MapStruct), no un mock:
    // estos tests cubren justamente el shaping entidad a response.
    @BeforeEach
    void wireRealMapper() {
        workerService.workerServiceMapper = Mappers.getMapper(WorkerServiceMapper.class);
        // El destino existe y la traduccion del choque corre el bloque tal cual: lo que se mide
        // aca es lo de adentro, y el bloqueo y sus choques los miden los tests de integracion.
        org.mockito.Mockito.lenient().when(workerRepository.count(
                org.mockito.ArgumentMatchers.eq("id"), org.mockito.ArgumentMatchers.<Object>any()))
            .thenReturn(1L);
        org.mockito.Mockito.lenient().when(workerRowLock.runTranslatingLockConflicts(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
            .thenAnswer(invocation -> ((java.util.function.Supplier<?>) invocation.getArgument(0)).get());
    }

    private Worker worker(int id, String first, String last, String roleDescription, boolean isActive) {
        Worker w = new Worker();
        w.id = id;
        w.firstName = first;
        w.lastName = last;
        Role role = new Role();
        role.description = roleDescription;
        w.role = role;
        w.isActive = isActive;
        return w;
    }

    @Test
    void listWorkers_delegatesFilterToRepositoryAndMapsFullName() {
        when(workerDocumentSearchVisibility.includeDocumentNumber()).thenReturn(true);
        when(workerRepository.search("juan", true, true))
            .thenReturn(List.of(worker(8, "Juan", "Perez", "Operador", true)));

        List<WorkerResponse> result = workerService.listWorkers(new ListWorkersQuery("juan", true));

        verify(workerRepository).search("juan", true, true);
        assertEquals(1, result.size());
        WorkerResponse r = result.get(0);
        assertEquals(8, r.id());
        assertEquals("Juan Perez", r.fullName());
        assertEquals("Operador", r.position());
        assertEquals(true, r.isActive());
    }

    @Test
    void listWorkers_emptyRepositoryResult_returnsEmptyList() {
        when(workerDocumentSearchVisibility.includeDocumentNumber()).thenReturn(true);
        when(workerRepository.search(null, null, true)).thenReturn(List.of());

        List<WorkerResponse> result = workerService.listWorkers(new ListWorkersQuery(null, null));

        assertEquals(0, result.size());
    }

    /**
     * La gemela del caso de arriba. Las dos juntas son lo que distingue "el servicio le pasa
     * al repositorio lo que la guarda decidio" de "el servicio le pasa siempre lo mismo":
     * con una sola, devolver una constante pasa igual.
     */
    @Test
    void listWorkers_whenTheGuardDenies_asksTheRepositoryNotToSearchByDocument() {
        when(workerDocumentSearchVisibility.includeDocumentNumber()).thenReturn(false);
        when(workerRepository.search("juan", true, false)).thenReturn(List.of());

        workerService.listWorkers(new ListWorkersQuery("juan", true));

        verify(workerRepository).search("juan", true, false);
    }


    // ---------- el alta: lo que por HTTP no se alcanza ---------------------------

    private CreateWorkerCommand createCommand() {
        return new CreateWorkerCommand("Juan", "Pérez", 1, "45678912", null, "operator",
            java.time.LocalDate.of(2024, 3, 1), null);
    }

    // ---------- la traduccion de duplicados en la EDICION ----------
    //
    // El alta y la edicion NO comparten el mapa de restricciones, y no es un detalle: el alta
    // inserta y la edicion puede chocar contra el documento O contra la licencia. El mapa de la
    // edicion nacio con UNA sola entrada y esa fue exactamente la falla que devolvia un error del
    // servidor en la rama que crea la ficha. Estos casos existen para que no vuelva.

    /**
     * Prepara una edicion que llega hasta la fase de escritura: catalogos validos, la fila tomada,
     * y el envoltorio del bloqueo corriendo DE VERDAD el bloque que envuelve.
     *
     * <p>Que el envoltorio se comporte es lo que hace que estos casos midan algo: un mock que
     * devuelve nulo sin invocar el bloque los dejaria verdes con el traductor borrado.
     */
    private Worker anEditableWorker() {
        catalogsAreValid();
        Worker existing = worker(5, "Juan", "Pérez", "Operario", true);
        existing.documentNumber = "45678912";
        existing.role.id = 9;
        existing.role.name = "operator";
        existing.role.driverProfile = "NONE";
        when(workerRowLock.findByIdForUpdate(5)).thenReturn(existing);
        when(userRepository.findByWorkerIdOptional(5)).thenReturn(java.util.Optional.empty());
        when(driverRepository.findByWorkerIdOptional(5)).thenReturn(java.util.Optional.empty());
        return existing;
    }

    /**
     * El cargo NUEVO exige ficha. Se re-declara el del repositorio y no el de la fila: la
     * modalidad la decide el cargo al que se va, no el que se tenia.
     */
    private void theNewRoleRequiresADriverProfile() {
        Role role = new Role();
        role.id = 9;
        role.name = "operator";
        role.level = (short) 1;
        role.driverProfile = "REQUIRED";
        role.isActive = true;
        when(roleRepository.findByName("operator")).thenReturn(java.util.Optional.of(role));
    }

    /** El catalogo de disponibilidad que la ficha que nace necesita para su estado por omision. */
    private void driverProfileCatalogIsValid() {
        com.scaramutti.tms.shared.entity.ResourceStatus available =
            new com.scaramutti.tms.shared.entity.ResourceStatus();
        available.id = 1;
        available.name = "AVAILABLE";
        when(resourceStatusRepository.findByNameIgnoringCase("AVAILABLE"))
            .thenReturn(java.util.Optional.of(available));
    }

    private UpdateWorkerCommand updateCommand(WorkerDriverProfileCommand driver) {
        return new UpdateWorkerCommand(5, "Juan", "Pérez", 1, "45678912", null, "operator",
            java.time.LocalDate.of(2024, 3, 1), driver, null);
    }

    /** La carrera del documento en una edicion SIN ficha nueva: la descarga del trabajador. */
    @Test
    void updateWorker_whenTheFlushViolatesTheDocumentKey_throwsWRK002() {
        anEditableWorker();
        org.mockito.Mockito.doThrow(violationOf("workers_document_number_key"))
            .when(workerRepository).flush();

        ApiException thrown = org.junit.jupiter.api.Assertions.assertThrows(
            ApiException.class, () -> workerService.updateWorker(updateCommand(null)));

        assertEquals("WRK-002", thrown.code());
        assertEquals(409, thrown.status());
    }

    /**
     * Y la carrera de la LICENCIA por el mismo camino. Es la entrada que el mapa de la edicion
     * siempre tuvo: si estos dos casos fueran uno solo, quitar la otra entrada no se notaria.
     */
    @Test
    void updateWorker_whenTheFlushViolatesTheLicenseKey_throwsWRK007() {
        anEditableWorker();
        org.mockito.Mockito.doThrow(violationOf("drivers_license_number_key"))
            .when(workerRepository).flush();

        ApiException thrown = org.junit.jupiter.api.Assertions.assertThrows(
            ApiException.class, () -> workerService.updateWorker(updateCommand(null)));

        assertEquals("WRK-007", thrown.code());
        assertEquals(409, thrown.status());
    }

    /**
     * EL CASO DEL DEFECTO: la rama que CREA la ficha durante una edicion. Su descarga vacia el
     * contexto entero, asi que por ahi puede aflorar la violacion del DOCUMENTO del trabajador y
     * no solo la de la licencia. Con el mapa de una sola entrada que esta rama tuvo, esto salia
     * como error del servidor, y la misma carrera respondia distinto segun el trabajador tuviera
     * ficha o no.
     */
    @Test
    void updateWorker_whenTheNewProfileFlushViolatesTheDocumentKey_throwsWRK002() {
        anEditableWorker();
        theNewRoleRequiresADriverProfile();
        driverProfileCatalogIsValid();
        org.mockito.Mockito.doThrow(violationOf("workers_document_number_key"))
            .when(driverRepository).flush();

        ApiException thrown = org.junit.jupiter.api.Assertions.assertThrows(ApiException.class,
            () -> workerService.updateWorker(updateCommand(
                new WorkerDriverProfileCommand("Q45678912", "A-IIb", null))));

        assertEquals("WRK-002", thrown.code());
        assertEquals(409, thrown.status());
    }

    /** Y la licencia por esa misma rama, que es la entrada que ya estaba. */
    @Test
    void updateWorker_whenTheNewProfileFlushViolatesTheLicenseKey_throwsWRK007() {
        anEditableWorker();
        theNewRoleRequiresADriverProfile();
        driverProfileCatalogIsValid();
        org.mockito.Mockito.doThrow(violationOf("drivers_license_number_key"))
            .when(driverRepository).flush();

        ApiException thrown = org.junit.jupiter.api.Assertions.assertThrows(ApiException.class,
            () -> workerService.updateWorker(updateCommand(
                new WorkerDriverProfileCommand("Q45678912", "A-IIb", null))));

        assertEquals("WRK-007", thrown.code());
    }

    /**
     * La restriccion DESCONOCIDA se propaga tal cual, igual que en el alta. Es el caso que impide
     * relajar la comparacion a un fragmento: la descarga puede aflorar la violacion de otro
     * modulo, y traducirla la disfrazaria de un problema de este.
     */
    @Test
    void updateWorker_withAnUnknownConstraint_propagatesTheOriginalException() {
        anEditableWorker();
        var original = violationOf("suppliers_document_number_key");
        org.mockito.Mockito.doThrow(original).when(workerRepository).flush();

        jakarta.persistence.PersistenceException thrown =
            org.junit.jupiter.api.Assertions.assertThrows(
                jakarta.persistence.PersistenceException.class,
                () -> workerService.updateWorker(updateCommand(null)));
        assertEquals(original, thrown);
    }

    /** Un cargo activo, sin ficha, y un tipo de documento sin restricciones. */
    private void catalogsAreValid() {
        Role role = new Role();
        role.name = "operator";
        role.level = (short) 1;
        role.driverProfile = "NONE";
        role.isActive = true;
        when(roleRepository.findByName("operator")).thenReturn(java.util.Optional.of(role));

        DocumentType documentType = new DocumentType();
        documentType.id = 1;
        documentType.isActive = true;
        documentType.maxLength = 20;
        when(documentTypeRepository.findByIdOptional(1)).thenReturn(java.util.Optional.of(documentType));

        when(currentUser.requireId()).thenReturn(7);
    }

    private jakarta.persistence.PersistenceException violationOf(String constraintName) {
        return new jakarta.persistence.PersistenceException(
            new org.hibernate.exception.ConstraintViolationException(
                "duplicate key", new java.sql.SQLException("23505"), constraintName));
    }

    /**
     * La carrera del documento: dos altas pasan el chequeo previo a la vez y chocan recien
     * contra el indice. Por HTTP hace falta una transaccion sin confirmar para forzarlo; aca
     * se mide la traduccion sola.
     */
    @Test
    void createWorker_whenTheInsertViolatesTheDocumentKey_throwsWRK002() {
        catalogsAreValid();
        org.mockito.Mockito.doThrow(violationOf("workers_document_number_key"))
            .when(workerRepository).flush();

        ApiException thrown = org.junit.jupiter.api.Assertions.assertThrows(
            ApiException.class, () -> workerService.createWorker(createCommand()));

        assertEquals("WRK-002", thrown.code());
        assertEquals(409, thrown.status());
    }

    /**
     * ESTE es el caso que impide relajar la comparacion a un fragmento. La descarga vacia el
     * contexto de persistencia entero, asi que puede aflorar aca la violacion de otro modulo;
     * con un "contiene document", el numero repetido de un proveedor saldria disfrazado de
     * "ya existe un trabajador con ese documento". Tiene que propagarse, no traducirse.
     */
    @Test
    void createWorker_withAnUnknownConstraint_propagatesTheOriginalException() {
        catalogsAreValid();
        var original = violationOf("suppliers_document_number_key");
        org.mockito.Mockito.doThrow(original).when(workerRepository).flush();

        jakarta.persistence.PersistenceException thrown =
            org.junit.jupiter.api.Assertions.assertThrows(
                jakarta.persistence.PersistenceException.class,
                () -> workerService.createWorker(createCommand()));

        assertEquals(original, thrown);
    }

    /** Una violacion sin nombre de restriccion no puede caer en ninguna rama: se propaga. */
    @Test
    void createWorker_whenTheViolationHasNoConstraintName_propagatesIt() {
        catalogsAreValid();
        org.mockito.Mockito.doThrow(violationOf(null)).when(workerRepository).flush();

        org.junit.jupiter.api.Assertions.assertThrows(
            jakarta.persistence.PersistenceException.class,
            () -> workerService.createWorker(createCommand()));
    }

    /**
     * La regla de rango se pregunta ANTES de tocar ningun catalogo mas y, sobre todo, antes de
     * grabar: por HTTP el orden solo se ve cuando dos errores conviven, aca se ve siempre.
     */
    @Test
    void createWorker_whenTheRankPolicyRejects_persistsNothing() {
        Role role = new Role();
        role.name = "sales";
        role.level = (short) 2;
        role.driverProfile = "NONE";
        role.isActive = true;
        when(roleRepository.findByName("sales")).thenReturn(java.util.Optional.of(role));
        org.mockito.Mockito.doThrow(
            com.scaramutti.tms.workers.WorkersError.ROLE_OUT_OF_RANK.toException())
            .when(workerRankPolicy).assertCanActOn(role);

        CreateWorkerCommand command = new CreateWorkerCommand("Juan", "Pérez", 1, "45678912",
            null, "sales", java.time.LocalDate.of(2024, 3, 1), null);

        ApiException thrown = org.junit.jupiter.api.Assertions.assertThrows(
            ApiException.class, () -> workerService.createWorker(command));

        assertEquals("WRK-006", thrown.code());
        org.mockito.Mockito.verifyNoInteractions(
            workerRepository, driverRepository, workerAuditLogRepository, documentTypeRepository);
    }

    /** Un cargo inactivo es cargo invalido: se retira poniendolo inactivo, no borrandolo. */
    @Test
    void createWorker_inactiveRole_throwsWRK005_withoutPersisting() {
        Role retired = new Role();
        retired.name = "operator";
        retired.level = (short) 1;
        retired.isActive = false;
        when(roleRepository.findByName("operator")).thenReturn(java.util.Optional.of(retired));

        ApiException thrown = org.junit.jupiter.api.Assertions.assertThrows(
            ApiException.class, () -> workerService.createWorker(createCommand()));

        assertEquals("WRK-005", thrown.code());
        org.mockito.Mockito.verifyNoInteractions(workerRepository, workerAuditLogRepository);
    }

    /** Un cargo de ficha opcional SIN ficha no toca el repositorio de fichas. */
    @Test
    void createWorker_roleOptionalWithoutProfile_neverTouchesTheDriverRepository() {
        Role optional = new Role();
        optional.name = "assistant";
        optional.level = (short) 1;
        optional.driverProfile = "OPTIONAL";
        optional.isActive = true;
        when(roleRepository.findByName("assistant")).thenReturn(java.util.Optional.of(optional));

        DocumentType documentType = new DocumentType();
        documentType.id = 1;
        documentType.isActive = true;
        documentType.maxLength = 20;
        when(documentTypeRepository.findByIdOptional(1)).thenReturn(java.util.Optional.of(documentType));
        when(currentUser.requireId()).thenReturn(7);

        CreateWorkerCommand command = new CreateWorkerCommand("Juan", "Pérez", 1, "45678912",
            null, "assistant", java.time.LocalDate.of(2024, 3, 1), null);

        // El detalle se relee al final; con el repositorio mockeado devuelve vacio y sale
        // WRK-001. Se afirma ESE codigo y no solo que hubo excepcion: si el cargo opcional sin
        // ficha se rechazara mal con el error de correspondencia, la verificacion de abajo
        // seguiria valiendo y el caso pasaria igual.
        ApiException thrown = org.junit.jupiter.api.Assertions.assertThrows(
            ApiException.class, () -> workerService.createWorker(command));
        assertEquals("WRK-001", thrown.code(), "tiene que llegar hasta la relectura del detalle");

        org.mockito.Mockito.verifyNoInteractions(driverRepository, resourceStatusRepository);
    }


    /** Un cargo que lleva ficha, con su ficha, y el catalogo de estados resolviendo. */
    private void driverRoleIsValid() {
        Role role = new Role();
        role.name = "driver";
        role.level = (short) 1;
        role.driverProfile = "REQUIRED";
        role.isActive = true;
        when(roleRepository.findByName("driver")).thenReturn(java.util.Optional.of(role));

        DocumentType documentType = new DocumentType();
        documentType.id = 1;
        documentType.isActive = true;
        documentType.maxLength = 20;
        when(documentTypeRepository.findByIdOptional(1)).thenReturn(java.util.Optional.of(documentType));
        when(currentUser.requireId()).thenReturn(7);
    }

    private CreateWorkerCommand createDriverCommand() {
        return new CreateWorkerCommand("Juan", "Pérez", 1, "45678912", null, "driver",
            java.time.LocalDate.of(2024, 3, 1),
            new com.scaramutti.tms.workers.service.cmd.WorkerDriverProfileCommand(
                "Q12345678", null, null));
    }

    /**
     * El gemelo del lado de la licencia. Sin el, cambiar el nombre de esa restriccion por uno
     * que no existe deja los unitarios enteros en verde: la unica red que quedaba era el caso
     * de integracion de la transaccion sin confirmar, y ese depende de una espera.
     */
    @Test
    void createWorker_whenTheDriverInsertViolatesTheLicenseKey_throwsWRK007() {
        driverRoleIsValid();
        var resourceStatus = new com.scaramutti.tms.shared.entity.ResourceStatus();
        resourceStatus.id = 1;
        when(resourceStatusRepository.findByNameIgnoringCase("AVAILABLE"))
            .thenReturn(java.util.Optional.of(resourceStatus));
        org.mockito.Mockito.doThrow(violationOf("drivers_license_number_key"))
            .when(driverRepository).flush();

        ApiException thrown = org.junit.jupiter.api.Assertions.assertThrows(
            ApiException.class, () -> workerService.createWorker(createDriverCommand()));

        assertEquals("WRK-007", thrown.code());
        assertEquals(409, thrown.status());
    }

    /**
     * El estado de la ficha se resuelve por el metodo que NO distingue caja.
     *
     * <p>Por HTTP esto no se puede medir en esta maquina: la base de desarrollo tiene la fila en
     * mayuscula con el id mas bajo, asi que una busqueda por nombre exacto devuelve la misma
     * fila y el caso de integracion sigue en verde. En produccion, que solo tiene minusculas,
     * esa busqueda no encontraria nada y toda alta con ficha se caeria. Aca el mock solo
     * responde al metodo correcto: cualquier otro deja el estado sin resolver y el caso muere.
     */
    @Test
    void createWorker_resolvesTheProfileStatusIgnoringCase() {
        driverRoleIsValid();
        var resourceStatus = new com.scaramutti.tms.shared.entity.ResourceStatus();
        resourceStatus.id = 3;
        when(resourceStatusRepository.findByNameIgnoringCase("AVAILABLE"))
            .thenReturn(java.util.Optional.of(resourceStatus));

        // La relectura del detalle sale WRK-001 con el repositorio mockeado; para cuando llega
        // ahi, la ficha ya se armo, que es lo que este caso mide.
        org.junit.jupiter.api.Assertions.assertThrows(ApiException.class,
            () -> workerService.createWorker(createDriverCommand()));

        verify(resourceStatusRepository).findByNameIgnoringCase("AVAILABLE");
        var captor = org.mockito.ArgumentCaptor.forClass(com.scaramutti.tms.shared.entity.Driver.class);
        verify(driverRepository).persist(captor.capture());
        assertEquals(3, captor.getValue().statusId);
    }

}
