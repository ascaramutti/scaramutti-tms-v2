package com.scaramutti.tms.shared.entity;

import com.scaramutti.tms.shared.repository.DriverRepository;
import com.scaramutti.tms.support.WarehouseTestData;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Dos escrituras sin coordinar sobre la MISMA fila de ficha no se pisan. Gemelo exacto del caso de
 * la cuenta, y existe por la misma razon.
 *
 * <p>La edicion de un trabajador convirtio esta fila en una fila que se ESCRIBE: corrige la
 * licencia y apaga la ficha. La lee sin bloqueo, asi que con escritura de columnas completas
 * devolveria {@code status_id} e {@code is_active} a los valores que tenian cuando arranco su
 * transaccion. Hoy nadie mas escribe esas dos columnas y por eso no rompe nada; el dia que un viaje
 * ponga a un conductor NO DISPONIBLE al asignarlo, una correccion de telefono se lo revierte en
 * silencio y con 200.
 *
 * <p>Lo que lo evita es que la entidad escriba SOLO las columnas que cada transaccion cambio. Esa
 * decision vive en una anotacion de una linea, y este caso es lo unico que la sostiene: sin el,
 * borrarla no rompe nada. La ronda que trajo la anotacion a esta entidad se olvido de traer el
 * guardian, que es el patron que este PR ya repitio tres veces.
 */
@QuarkusTest
class DriverConcurrentWriteTest {

    @Inject DriverRepository driverRepository;
    @Inject WarehouseTestData fixtures;

    @BeforeEach
    @AfterEach
    void cleanupFixtures() {
        fixtures.deleteTestWorkerDependents();
        QuarkusTransaction.requiringNew().run(() -> fixtures.deleteTestWorkers());
    }

    @Test
    void twoTransactionsWritingDifferentColumns_doNotOverwriteEachOther() {
        int workerId = fixtures.seedWorker("ZTESTW002", "Ana", "Silva", "driver", true);
        fixtures.seedDriverProfileFor(workerId, "ZTESTL700", "A-IIb",
            WarehouseTestData.STATUS_AVAILABLE, true);

        QuarkusTransaction.requiringNew().run(() -> {
            // T1 carga la ficha y le cambia SOLO la licencia, sin confirmar todavia.
            Driver driver = driverRepository.findByWorkerIdOptional(workerId).orElseThrow();
            driver.licenseNumber = "ZTESTL701";

            // T2, en su propia transaccion, le cambia SOLO la disponibilidad y confirma.
            fixtures.setDriverStatus(workerId, WarehouseTestData.STATUS_NOT_AVAILABLE);

            // Al cerrar T1, su UPDATE tiene que tocar unicamente la columna que cambio.
        });

        assertEquals(fixtures.resourceStatusId(WarehouseTestData.STATUS_NOT_AVAILABLE),
            fixtures.driverRowOf(workerId).statusId(),
            "la disponibilidad que escribio la otra transaccion no se puede perder");
        assertEquals("ZTESTL701", fixtures.driverRowOf(workerId).licenseNumber(),
            "y la licencia de esta tampoco");
    }
}
