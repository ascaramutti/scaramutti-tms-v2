package com.scaramutti.tms.shared.repository;

import com.scaramutti.tms.shared.entity.Worker;
import com.scaramutti.tms.support.WarehouseTestData;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.UserTransaction;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * El nombre completo tiene una regla escrita dos veces, en {@link Worker#fullName()} y en
 * {@link WorkerRepository#fullNameExpression}. Cada fila sintetica se graba como la graba la
 * aplicacion, con {@link Worker#assignSurnames}, y las dos reglas tienen que dar el nombre esperado,
 * igual que la de la imagen anterior sobre {@code last_name}, que la entidad ya no manda: la compone el
 * disparador al insertar. Reemplaza al caso que fijaba el texto de la expresion.
 */
@QuarkusTest
class WorkerFullNameRuleTest {

    @Inject EntityManager entityManager;
    @Inject WarehouseTestData fixtures;
    @Inject RoleRepository roleRepository;
    @Inject UserTransaction transaction;

    /**
     * Con materno y sin el, y con espacios al borde de cada parte: el paterno en blanco es el de
     * una ficha vieja copiada tal cual, y el materno con espacios al borde lo deja pasar el CHECK.
     */
    @ParameterizedTest(name = "[{0}] [{1}] [{2}]")
    @CsvSource(delimiter = '|', value = {
        "María José | De la Cruz | Núñez | María José De la Cruz Núñez",
        "Fabiola | Quispe | | Fabiola Quispe",
        "' Ana ' | 'Rojas ' | | Ana  Rojas",
        "Ana | ' ' | | Ana",
        "Ana | ' ' | Quispe | Ana   Quispe",
        "' ' | Rojas | Vega | Rojas Vega",
        "Ana | Rojas | ' Vega ' | Ana Rojas  Vega",
    })
    void theJavaRule_theSqlRule_andTheOldOne_giveTheSameFullName(String firstName, String paternal,
            String maternal, String fullName) throws Exception {
        transaction.begin();
        try {
            Worker worker = new Worker();
            worker.firstName = firstName;
            worker.assignSurnames(paternal, maternal);
            worker.documentTypeId = fixtures.dniDocumentTypeId();
            worker.documentNumber = "ZTESTFN01";
            worker.role = roleRepository.findByName("operator").orElseThrow();
            worker.hireDate = LocalDate.of(2024, 3, 1);
            entityManager.persist(worker);
            entityManager.flush();
            Object[] row = (Object[]) entityManager.createNativeQuery("SELECT w.last_name, "
                    + WorkerRepository.fullNameExpression("w") + ", trim(w.first_name || ' ' || w.last_name) "
                    + "FROM public.workers w WHERE w.id = :id")
                .setParameter("id", worker.id).getSingleResult();
            entityManager.clear();
            Worker reread = entityManager.find(Worker.class, worker.id);

            assertEquals(fullName, worker.fullName(), "la regla Java");
            assertEquals(fullName, reread.fullName(), "la regla Java sobre la fila releida");
            assertEquals(fullName, row[1], "la regla SQL");
            assertEquals(fullName, row[2], "la regla de la imagen anterior, sobre last_name");
            assertEquals(maternal == null ? paternal : paternal + " " + maternal, row[0],
                "last_name, compuesto por el disparador con las partes");
        } finally {
            transaction.rollback();
        }
    }
}
