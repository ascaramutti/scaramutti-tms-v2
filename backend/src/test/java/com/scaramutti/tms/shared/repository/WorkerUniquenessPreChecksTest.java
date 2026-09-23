package com.scaramutti.tms.shared.repository;

import com.scaramutti.tms.support.WarehouseTestData;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests de los dos chequeos previos de unicidad del alta.
 *
 * <p>Existen porque por HTTP esto NO se puede medir: el indice unico de la base no mira el
 * estado, asi que si estos metodos filtraran por vigente el alta pasaria el chequeo, chocaria
 * contra el indice y saldria el MISMO conflicto, con las mismas filas. Los casos de integracion
 * no pueden distinguir un camino del otro; aca se llama al metodo y se ve directo.
 *
 * <p>Por que importa que no filtren: un documento y una licencia son unicos en toda la tabla,
 * estado incluido. Dar de baja a alguien no libera su documento.
 */
@QuarkusTest
class WorkerUniquenessPreChecksTest {

    @Inject WorkerRepository workerRepository;
    @Inject DriverRepository driverRepository;
    @Inject UserRepository userRepository;
    @Inject WarehouseTestData fixtures;

    @BeforeEach
    @AfterEach
    void cleanupFixtures() {
        fixtures.deleteTestWorkerDependents();
        QuarkusTransaction.requiringNew().run(() -> fixtures.deleteTestWorkers());
    }

    @Test
    void existsByDocumentNumber_seesAnInactiveWorker() {
        fixtures.seedWorker("ZTESTU001", "Ana", "Silva", "operator", false);

        assertTrue(workerRepository.existsByDocumentNumber("ZTESTU001"),
            "dar de baja a alguien no libera su numero de documento");
    }

    @Test
    void existsByDocumentNumber_seesAnActiveWorker() {
        fixtures.seedWorker("ZTESTU002", "Ana", "Silva", "operator", true);

        assertTrue(workerRepository.existsByDocumentNumber("ZTESTU002"));
    }

    @Test
    void existsByDocumentNumber_withoutAnyWorker_isFalse() {
        assertFalse(workerRepository.existsByDocumentNumber("ZTESTU003"));
    }

    @Test
    void existsByLicenseNumber_seesAnInactiveProfile() {
        int workerId = fixtures.seedWorker("ZTESTU004", "Ana", "Silva", "driver", true);
        fixtures.seedDriverProfileFor(workerId, "ZTESTUL004", null,
            WarehouseTestData.STATUS_AVAILABLE, false);

        assertTrue(driverRepository.existsByLicenseNumber("ZTESTUL004"),
            "una ficha apagada sigue ocupando su numero de licencia");
    }

    @Test
    void existsByLicenseNumber_seesAnActiveProfile() {
        int workerId = fixtures.seedWorker("ZTESTU005", "Ana", "Silva", "driver", true);
        fixtures.seedDriverProfileFor(workerId, "ZTESTUL005", null,
            WarehouseTestData.STATUS_AVAILABLE, true);

        assertTrue(driverRepository.existsByLicenseNumber("ZTESTUL005"));
    }

    @Test
    void existsByLicenseNumber_withoutAnyProfile_isFalse() {
        assertFalse(driverRepository.existsByLicenseNumber("ZTESTUL006"));
    }

    // ---------- las variantes "excluyendo", que son las de la edicion ----------

    /**
     * ESTOS son los que miden la exclusion de verdad. Por HTTP no se puede: reenviar el propio
     * documento sin cambios no produce violacion aunque el chequeo desaparezca, y el caso pasaria
     * igual. Aca se llama al metodo y se ve directo.
     */
    @Test
    void existsByDocumentNumberExcluding_doesNotSeeItself() {
        int id = fixtures.seedWorker("ZTESTU010", "Ana", "Silva", "operator", true);

        assertFalse(workerRepository.existsByDocumentNumberExcluding("ZTESTU010", id),
            "el propio documento no es un duplicado de si mismo");
    }

    @Test
    void existsByDocumentNumberExcluding_seesAnotherWorker() {
        fixtures.seedWorker("ZTESTU011", "Ana", "Silva", "operator", true);
        int other = fixtures.seedWorker("ZTESTU012", "Juan", "Perez", "operator", true);

        assertTrue(workerRepository.existsByDocumentNumberExcluding("ZTESTU011", other));
    }

    @Test
    void existsByDocumentNumberExcluding_seesAnInactiveOtherWorker() {
        fixtures.seedWorker("ZTESTU013", "Ana", "Silva", "operator", false);
        int other = fixtures.seedWorker("ZTESTU014", "Juan", "Perez", "operator", true);

        assertTrue(workerRepository.existsByDocumentNumberExcluding("ZTESTU013", other),
            "dar de baja a alguien no libera su documento");
    }

    /** Mata "excluye siempre": con un id que no existe tiene que seguir viendo el documento. */
    @Test
    void existsByDocumentNumberExcluding_withAnIdThatDoesNotExist_stillSeesIt() {
        fixtures.seedWorker("ZTESTU015", "Ana", "Silva", "operator", true);

        assertTrue(workerRepository.existsByDocumentNumberExcluding("ZTESTU015", 999999));
    }

    @Test
    void existsByLicenseNumberExcludingWorker_doesNotSeeItsOwn() {
        int id = fixtures.seedWorker("ZTESTU020", "Ana", "Silva", "driver", true);
        fixtures.seedDriverProfileFor(id, "ZTESTUL020", null, WarehouseTestData.STATUS_AVAILABLE, true);

        assertFalse(driverRepository.existsByLicenseNumberExcludingWorker("ZTESTUL020", id));
    }

    @Test
    void existsByLicenseNumberExcludingWorker_seesAnotherProfile() {
        int id = fixtures.seedWorker("ZTESTU021", "Ana", "Silva", "driver", true);
        fixtures.seedDriverProfileFor(id, "ZTESTUL021", null, WarehouseTestData.STATUS_AVAILABLE, true);
        int other = fixtures.seedWorker("ZTESTU022", "Juan", "Perez", "driver", true);

        assertTrue(driverRepository.existsByLicenseNumberExcludingWorker("ZTESTUL021", other));
    }

    @Test
    void existsByLicenseNumberExcludingWorker_seesAnInactiveProfile() {
        int id = fixtures.seedWorker("ZTESTU023", "Ana", "Silva", "driver", true);
        fixtures.seedDriverProfileFor(id, "ZTESTUL023", null, WarehouseTestData.STATUS_AVAILABLE, false);
        int other = fixtures.seedWorker("ZTESTU024", "Juan", "Perez", "driver", true);

        assertTrue(driverRepository.existsByLicenseNumberExcludingWorker("ZTESTUL023", other),
            "una ficha apagada sigue ocupando su licencia");
    }

    /**
     * La exclusion es por el TRABAJADOR y no por la ficha, y esto lo mide: el trabajador todavia
     * no tiene ficha, asi que una exclusion por el id de la ficha no excluiria nada.
     */
    @Test
    void existsByLicenseNumberExcludingWorker_worksForAWorkerWithoutAProfileYet() {
        int id = fixtures.seedWorker("ZTESTU025", "Ana", "Silva", "driver", true);
        fixtures.seedDriverProfileFor(id, "ZTESTUL025", null, WarehouseTestData.STATUS_AVAILABLE, true);
        int withoutProfile = fixtures.seedWorker("ZTESTU026", "Juan", "Perez", "operator", true);

        assertTrue(driverRepository.existsByLicenseNumberExcludingWorker("ZTESTUL025", withoutProfile));
    }

    /** El usuario de un trabajador se encuentra este ACTIVO o no: apagarlo no lo hace desaparecer. */
    @Test
    void findByWorkerIdOptional_findsAnInactiveUser() {
        int id = fixtures.seedWorker("ZTESTU030", "Ana", "Silva", "sales", true);
        int userId = fixtures.seedUserFor(id, "ztestuser500", "sales");
        fixtures.setUserActive(userId, false);

        assertTrue(userRepository.findByWorkerIdOptional(id).isPresent(),
            "una cuenta apagada se puede volver a encender: sigue contando");
    }

    @Test
    void findByWorkerIdOptional_withoutUser_isEmpty() {
        int id = fixtures.seedWorker("ZTESTU031", "Ana", "Silva", "operator", true);

        assertTrue(userRepository.findByWorkerIdOptional(id).isEmpty());
    }

}
