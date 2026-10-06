package com.scaramutti.tms.shared.repository;

import com.scaramutti.tms.support.WarehouseTestData;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests de como se resuelve una fila del catalogo de disponibilidad por su nombre.
 *
 * <p>Van contra filas SEMBRADAS y no contra las del catalogo de la base a proposito. Las de la
 * base no sirven para medir esto: desarrollo tiene la misma disponibilidad escrita en las dos
 * cajas, y justo la version en mayuscula es la del id mas bajo, asi que una busqueda por
 * nombre exacto en mayuscula devuelve la misma fila que la busqueda sin distinguir caja. En
 * produccion, donde solo estan las minusculas, esa misma busqueda no devolveria nada y el alta
 * de una ficha de conductor se caeria. Sembrando la fila, la diferencia se ve en cualquier
 * ambiente.
 */
@QuarkusTest
class ResourceStatusRepositoryTest {

    @Inject ResourceStatusRepository resourceStatusRepository;
    @Inject WarehouseTestData fixtures;

    @BeforeEach
    @AfterEach
    void cleanupFixtures() {
        fixtures.deleteTestResourceStatuses();
    }

    /**
     * El caso que importa: la fila esta en MINUSCULA, como en produccion, y se la busca por el
     * nombre del enum, que esta en MAYUSCULA. Comparar por nombre exacto no la encuentra.
     */
    @Test
    void findByNameIgnoringCase_findsALowercaseRowByItsUppercaseName() {
        int seeded = fixtures.seedResourceStatus("ztest_available");

        var found = resourceStatusRepository.findByNameIgnoringCase("ZTEST_AVAILABLE");

        assertTrue(found.isPresent(), "un nombre en mayuscula tiene que encontrar la fila en minuscula");
        assertEquals(seeded, found.get().id);
    }

    /** Y al reves, porque desarrollo guarda la otra caja y tiene que resolver igual. */
    @Test
    void findByNameIgnoringCase_findsAnUppercaseRowByItsLowercaseName() {
        int seeded = fixtures.seedResourceStatus("ztest_MAINT");

        assertEquals(seeded,
            resourceStatusRepository.findByNameIgnoringCase("ztest_maint").orElseThrow().id);
    }

    /**
     * Con las dos cajas del MISMO nombre conviviendo, gana la de menor id. Es la situacion de
     * la base de desarrollo, y sin ese orden cual de las dos sale depende de como la base
     * devuelva las filas: dos altas seguidas podrian apuntar a estados distintos.
     *
     * <p>El nombre es unico en la tabla, pero como texto: la version en minuscula y la version
     * en mayuscula son dos filas distintas y la restriccion no lo impide.
     */
    @Test
    void findByNameIgnoringCase_whenBothSpellingsExist_takesTheLowestId() {
        int lowercaseFirst = fixtures.seedResourceStatus("ztest_dual");
        int uppercaseSecond = fixtures.seedResourceStatus("ztest_DUAL");

        assertTrue(lowercaseFirst < uppercaseSecond, "el fixture siembra en orden de id");
        assertEquals(lowercaseFirst,
            resourceStatusRepository.findByNameIgnoringCase("ZTEST_DUAL").orElseThrow().id);
    }

    @Test
    void findByNameIgnoringCase_withoutAnyRow_returnsEmpty() {
        assertTrue(resourceStatusRepository.findByNameIgnoringCase("ztest_no_existe").isEmpty());
    }
}
