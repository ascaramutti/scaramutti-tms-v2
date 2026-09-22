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
}
