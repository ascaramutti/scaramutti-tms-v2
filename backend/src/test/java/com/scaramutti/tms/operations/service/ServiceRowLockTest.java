package com.scaramutti.tms.operations.service;

import com.scaramutti.tms.shared.exception.ApiException;
import jakarta.persistence.OptimisticLockException;
import jakarta.persistence.PersistenceException;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit de la traducción del conflicto de lock. Se prueba como función pura porque los dos caminos
 * que MÁS importan no se pueden provocar por HTTP de forma fiable: el abrazo mortal necesita dos
 * transacciones cruzándose sobre filas distintas, y el paso-de-largo necesita una falla de base
 * que no sea de lock.
 */
class ServiceRowLockTest {

    /** Lo que el pool espera por una conexión: el techo de la banda del tope. */
    private static final java.time.Duration POOL_WAIT = java.time.Duration.ofSeconds(5);

    private final ServiceRowLock rowLock = new ServiceRowLock();

    private static PersistenceException wrapping(String sqlState) {
        return new PersistenceException("fallo de base",
            new IllegalStateException("envoltorio de Hibernate",
                new SQLException("mensaje del motor", sqlState)));
    }

    /** La espera agotada: el caso que el tope de espera produce. */
    @Test
    void asLockConflictOrRethrow_withLockTimeout_translatesToTheBusinessConflict() {
        RuntimeException translated = rowLock.asLockConflictOrRethrow(wrapping("55P03"), 42L);

        assertInstanceOf(ApiException.class, translated);
        assertEquals("OPS-008", ((ApiException) translated).code());
    }

    /**
     * El abrazo mortal. Va por el mismo código porque también es "no pude tomar la fila" y también
     * se resuelve reintentando. Se decide por el ESTADO del motor y no por el tipo de la excepción:
     * Hibernate envuelve este caso en una excepción de bloqueo OPTIMISTA, que no describe nada de
     * lo que pasó, así que un catch por tipo lo dejaría escapar como error del servidor.
     */
    @Test
    void asLockConflictOrRethrow_withDeadlock_translatesToo() {
        PersistenceException misleadingType = new OptimisticLockException(
            new SQLException("deadlock detected", "40P01"));

        RuntimeException translated = rowLock.asLockConflictOrRethrow(misleadingType, 42L);

        assertInstanceOf(ApiException.class, translated);
        assertEquals("OPS-008", ((ApiException) translated).code());
    }

    /**
     * Lo que NO es conflicto de lock pasa de largo TAL CUAL. Enmascarar una falla real de base como
     * un 409 reintentable es peor que no traducir nada: el cliente reintentaría para siempre contra
     * un problema que no se arregla solo, y el error original se perdería.
     */
    @Test
    void asLockConflictOrRethrow_withAnyOtherFailure_letsItThrough() {
        PersistenceException other = wrapping("23505");   // violación de unicidad

        assertSame(other, rowLock.asLockConflictOrRethrow(other, 42L));
    }

    /** Sin ningún error de base en la cadena tampoco se traduce. */
    @Test
    void asLockConflictOrRethrow_withoutASqlCause_letsItThrough() {
        PersistenceException noSqlCause = new PersistenceException("algo se rompió arriba");

        assertSame(noSqlCause, rowLock.asLockConflictOrRethrow(noSqlCause, 42L));
    }

    /**
     * El helper traduce el conflicto venga de donde venga, también de un bloque que ESCRIBE.
     *
     * <p>Esto prueba el helper como función; que la edición lo USE alrededor de sus escrituras lo
     * prueba {@code ServiceUpdateResourceTest#update_whenTheLogTableIsLocked_returns409}, que
     * bloquea la tabla de la bitácora y espera el 409. Son dos mutaciones distintas.
     */
    @Test
    void runTranslatingLockConflicts_translatesTheConflictOfABlockThatWrites() {
        PersistenceException conflict = wrapping("55P03");

        ApiException translated = assertThrows(ApiException.class,
            () -> rowLock.runTranslatingLockConflicts(() -> { throw conflict; }, 42L));

        assertEquals("OPS-008", translated.code());
    }

    /** Y en su forma sin resultado, que es la que usa el bloque de escritura del endpoint. */
    @Test
    void runTranslatingLockConflicts_translatesItInTheFormWithoutResult() {
        PersistenceException conflict = wrapping("40P01");

        ApiException translated = assertThrows(ApiException.class,
            () -> rowLock.runTranslatingLockConflicts((Runnable) () -> { throw conflict; }, 42L));

        assertEquals("OPS-008", translated.code());
    }

    /**
     * Lo que NO es conflicto de lock pasa de largo TAL CUAL. Disfrazar una falla real de base como
     * un 409 reintentable es peor que no traducir: el cliente reintentaría para siempre contra algo
     * que no se arregla solo.
     */
    @Test
    void runTranslatingLockConflicts_letsAnyOtherFailureThrough() {
        PersistenceException other = wrapping("23505");

        assertSame(other, assertThrows(PersistenceException.class,
            () -> rowLock.runTranslatingLockConflicts(() -> { throw other; }, 42L)));
    }

    /**
     * PostgreSQL lee el cero como "sin tope", así que configurarlo desactiva la protección en vez
     * de endurecerla. Tiene que fallar, y el mensaje tiene que nombrar la propiedad: es lo único
     * que le dice a quien despliega dónde está el problema.
     */
    @Test
    void requireUsableLockTimeout_withZeroOrNegative_failsNamingTheProperty() {
        for (int invalid : new int[] {0, -1}) {
            rowLock.lockTimeoutSeconds = invalid;
            rowLock.poolAcquisitionTimeout = POOL_WAIT;

            IllegalStateException e = assertThrows(IllegalStateException.class,
                rowLock::requireUsableLockTimeout);
            assertTrue(e.getMessage().contains("app.operations.edit-lock-timeout-seconds"),
                "el mensaje tiene que nombrar la propiedad: " + e.getMessage());
        }
    }

    /**
     * Y por arriba: quien espera por el lock retiene su conexión todo ese tiempo, así que un tope
     * que llegue a lo que el pool espera para ENTREGAR una conexión invierte el orden de las
     * rendiciones y deja sin conexiones a los otros módulos. Es la forma típica de "arreglar" un
     * 409 molesto subiendo el número.
     */
    @Test
    void requireUsableLockTimeout_reachingThePoolWait_failsToo() {
        rowLock.lockTimeoutSeconds = (int) POOL_WAIT.toSeconds();
        rowLock.poolAcquisitionTimeout = POOL_WAIT;

        IllegalStateException e = assertThrows(IllegalStateException.class,
            rowLock::requireUsableLockTimeout);
        assertTrue(e.getMessage().contains("app.operations.edit-lock-timeout-seconds"),
            "el mensaje tiene que nombrar la propiedad: " + e.getMessage());
    }

    @Test
    void requireUsableLockTimeout_withAPositiveValue_returnsIt() {
        rowLock.lockTimeoutSeconds = 4;
        rowLock.poolAcquisitionTimeout = POOL_WAIT;

        assertEquals(4, rowLock.requireUsableLockTimeout());
    }
}
