package com.scaramutti.tms.operations.service;

import com.scaramutti.tms.operations.model.ServiceResourceKind;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Las claves con las que se lockean los recursos, y su orden.
 *
 * <p>Este test existe porque las dos garantias que fija son invisibles en produccion hasta que
 * fallan, y cuando fallan lo hacen de la peor manera: dos claves que colisionan hacen esperar de
 * gusto a asignaciones que no tienen nada que ver, y un orden que no es estable deja a dos
 * asignaciones esperandose mutuamente hasta que el tope de espera las corta. Ninguna de las dos
 * cosas se ve en un test de endpoint.
 */
class ServiceResourceLockKeysTest {

    @Test
    void keyOf_forTheSameResource_isStable() {
        assertEquals(
            ServiceResourceLockKeys.of(ServiceResourceKind.DRIVER, 7),
            ServiceResourceLockKeys.of(ServiceResourceKind.DRIVER, 7));
    }

    /**
     * El tipo tiene que estar en la clave. Sin el, el conductor 7 y el tracto 7 compartirian lock
     * y se serializarian dos asignaciones que no comparten ningun recurso.
     */
    @Test
    void keyOf_forDifferentKindsWithTheSameId_differs() {
        String driver = ServiceResourceLockKeys.of(ServiceResourceKind.DRIVER, 7);
        String tractor = ServiceResourceLockKeys.of(ServiceResourceKind.TRACTOR, 7);
        String trailer = ServiceResourceLockKeys.of(ServiceResourceKind.TRAILER, 7);

        assertNotEquals(driver, tractor);
        assertNotEquals(tractor, trailer);
        assertNotEquals(driver, trailer);
    }

    @Test
    void keyOf_forDifferentIds_differs() {
        assertNotEquals(
            ServiceResourceLockKeys.of(ServiceResourceKind.DRIVER, 7),
            ServiceResourceLockKeys.of(ServiceResourceKind.DRIVER, 8));
    }

    /** El id se rellena para que el orden por texto sea el mismo que el orden numerico. */
    @Test
    void keyOf_padsTheIdSoTextOrderMatchesNumericOrder() {
        String nine = ServiceResourceLockKeys.of(ServiceResourceKind.DRIVER, 9);
        String ten = ServiceResourceLockKeys.of(ServiceResourceKind.DRIVER, 10);

        assertTrue(nine.compareTo(ten) < 0, nine + " deberia ordenar antes que " + ten);
    }

    @Test
    void keyOf_withTheTopOfTheIntRange_doesNotBreakTheOrder() {
        String big = ServiceResourceLockKeys.of(ServiceResourceKind.DRIVER, Integer.MAX_VALUE);
        String small = ServiceResourceLockKeys.of(ServiceResourceKind.DRIVER, 1);

        assertTrue(small.compareTo(big) < 0);
    }

    /**
     * EL invariante del orden: la secuencia no depende de como vengan los ids en el cuerpo.
     *
     * <p>Dos asignaciones que compartan dos recursos los van a pedir en la misma secuencia, asi
     * que la segunda espera a la primera en el primer recurso comun y nunca al reves. Si este
     * test se pone rojo, el abrazo mortal vuelve a ser posible.
     */
    @Test
    void ordered_isTheSameNoMatterHowTheIdsArrive() {
        List<String> first = ServiceResourceLockKeys.ordered(9, 2, 5);
        List<String> second = ServiceResourceLockKeys.ordered(9, 2, 5);

        assertEquals(first, second);
        // Se afirma el CONTENIDO de cada posicion, no la lista armada con el mismo metodo que
        // esta bajo prueba: construir lo esperado con `of()` hace que cualquier cambio en el
        // formato de la clave se cancele solo a los dos lados y el caso pase igual.
        assertEquals(3, first.size());
        assertTrue(first.get(0).contains("DRIVER"), first.get(0));
        assertTrue(first.get(1).contains("TRACTOR"), first.get(1));
        assertTrue(first.get(2).contains("TRAILER"), first.get(2));
    }

    /** La carreta es opcional: sin ella hay dos claves, no tres con un hueco. */
    @Test
    void ordered_withoutTrailer_hasNoThirdKey() {
        List<String> keys = ServiceResourceLockKeys.ordered(4, 7, null);

        assertEquals(2, keys.size());
        assertTrue(keys.contains(ServiceResourceLockKeys.of(ServiceResourceKind.DRIVER, 4)));
        assertTrue(keys.contains(ServiceResourceLockKeys.of(ServiceResourceKind.TRACTOR, 7)));
    }

    @Test
    void ordered_withNoResources_isEmpty() {
        assertEquals(List.of(), ServiceResourceLockKeys.ordered(null, null, null));
    }
}
