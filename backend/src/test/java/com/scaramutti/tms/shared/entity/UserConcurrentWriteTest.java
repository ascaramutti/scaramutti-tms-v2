package com.scaramutti.tms.shared.entity;

import com.scaramutti.tms.shared.repository.UserRepository;
import com.scaramutti.tms.support.WarehouseTestData;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Dos escrituras sin coordinar sobre la MISMA fila de usuario no se pisan.
 *
 * <p>La edicion de un trabajador le cambia el rol a su cuenta; el cambio de contrasena le cambia
 * el hash. Son dos transacciones distintas, sin bloqueo entre ellas, y con escritura de columnas
 * completas la que llegue segunda pisaria lo que la primera acababa de guardar: una de las dos se
 * pierde sin ningun error, y si la que se pierde es el cargo, queda una fila de rastro afirmando
 * un cambio de permisos que en la fila no esta.
 *
 * <p>Lo que lo evita es que la entidad escriba SOLO las columnas que cada transaccion cambio. Esa
 * decision vive en una anotacion de una linea, y este caso es lo unico que la sostiene: sin el,
 * borrarla no rompe nada.
 */
@QuarkusTest
class UserConcurrentWriteTest {

    @Inject UserRepository userRepository;
    @Inject WarehouseTestData fixtures;

    @BeforeEach
    @AfterEach
    void cleanupFixtures() {
        fixtures.deleteTestWorkerDependents();
        QuarkusTransaction.requiringNew().run(() -> fixtures.deleteTestWorkers());
    }

    @Test
    void twoTransactionsWritingDifferentColumns_doNotOverwriteEachOther() {
        int workerId = fixtures.seedWorker("ZTESTW001", "Ana", "Silva", "sales", true);
        int userId = fixtures.seedUserFor(workerId, "ztestuser700", "sales");

        QuarkusTransaction.requiringNew().run(() -> {
            // T1 carga la fila y le cambia SOLO el hash, sin confirmar todavia.
            User user = userRepository.findById(userId);
            user.passwordHash = "hash-nuevo-de-la-otra-operacion";

            // T2, en su propia transaccion, le cambia SOLO el rol y confirma.
            fixtures.setUserRole(userId, "finance_manager");

            // Al cerrar T1, su UPDATE tiene que tocar unicamente la columna que cambio.
        });

        assertEquals("finance_manager", fixtures.userRoleNameOf(userId),
            "el cargo que escribio la otra transaccion no se puede perder");
        assertEquals("hash-nuevo-de-la-otra-operacion",
            QuarkusTransaction.requiringNew().call(() -> userRepository.findById(userId).passwordHash),
            "y el hash de esta tampoco");
    }
}
