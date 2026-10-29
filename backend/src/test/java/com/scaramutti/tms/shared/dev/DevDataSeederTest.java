package com.scaramutti.tms.shared.dev;

import com.scaramutti.tms.support.WarehouseTestData;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * La de-realificacion del sembrador vacia tambien el materno: uno puesto a mano en una base
 * restaurada es parte de un nombre real. El caso se lo pone por SQL al trabajador de una cuenta
 * sembrada y vuelve a correr el sembrador, que es idempotente y deja los apellidos como al arrancar.
 */
@QuarkusTest
class DevDataSeederTest {

    @Inject DevDataSeeder devDataSeeder;
    @Inject WarehouseTestData fixtures;
    @Inject EntityManager entityManager;

    @Test
    void theSeeder_emptiesAMaternalOfASeededAccount() {
        int workerId = QuarkusTransaction.requiringNew().call(() -> ((Number) entityManager.createNativeQuery(
            "SELECT worker_id FROM public.users WHERE username = 'inactivo'").getSingleResult()).intValue());
        try {
            fixtures.setWorkerMaternalLastName(workerId, "Núñez");
            assertEquals("Inactivo Núñez|Inactivo|Núñez", fixtures.workerSurnames(workerId), "precondicion");

            devDataSeeder.onStart(null);

            assertEquals("Inactivo|Inactivo|nulo", fixtures.workerSurnames(workerId));
        } finally {
            fixtures.setWorkerMaternalLastName(workerId, null);
        }
    }
}
