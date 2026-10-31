package com.scaramutti.tms.auth.service;

import com.scaramutti.tms.shared.entity.User;
import com.scaramutti.tms.shared.exception.ApiException;
import com.scaramutti.tms.shared.repository.RowLockSupport;
import jakarta.enterprise.event.Observes;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.InputStream;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * El tope de la fila de la cuenta en el cambio propio: la configuracion real, el presupuesto, la
 * guarda al arrancar y el codigo del choque. Sin contenedor: arrancar con un valor malo es
 * justamente lo que ya no se puede.
 */
class OwnAccountRowLockTest {

    private static OwnAccountRowLock lockWith(int lockTimeoutMillis, Duration poolWait) {
        OwnAccountRowLock lock = new OwnAccountRowLock();
        lock.lockTimeoutMillis = lockTimeoutMillis;
        lock.poolAcquisitionTimeout = poolWait;
        return lock;
    }

    private static OwnAccountRowLock lockFromTheFile(String profilePrefix) throws Exception {
        Properties config = new Properties();
        try (InputStream file = OwnAccountRowLockTest.class.getResourceAsStream("/application.properties")) {
            config.load(file);
        }
        return lockWith(Integer.parseInt(config.getProperty(profilePrefix + "app.accounts.lock-timeout-ms")),
            Duration.parse("PT" + config.getProperty(profilePrefix + "quarkus.datasource.jdbc.acquisition-timeout")
                .toUpperCase()));
    }

    /** La de produccion, leida del archivo: 380 y entra. Los tests corren con otra, asi que nada mas la fija. */
    @Test
    void theProductionConfiguration_isAt380_andPassesTheGuard() throws Exception {
        assertEquals(380, lockFromTheFile("").requireUsableLockTimeout());
    }

    /** La del perfil de test, mas holgada para los tests que retienen la fila, pasa la misma guarda. */
    @Test
    void theTestProfileConfiguration_passesTheSameGuard() throws Exception {
        assertEquals(600, lockFromTheFile("%test.").requireUsableLockTimeout());
    }

    /**
     * Seis: la tabla al tomar la fila (con EXCLUSIVE), la fila con cola (dos), trabajador y cargo al
     * leer, la tabla en el UPDATE. La lectura del hash, antes, no tiene tope.
     */
    @Test
    void theBudget_countsTheTableAndRowWaitsOfTheChange() {
        assertEquals(6, OwnAccountRowLock.MAX_LOCK_WAITS_PER_TRANSACTION);
    }

    /** El presupuesto es el del cambio propio: 834 x 6 pasa los 5s del pool y no entra; 833, si. */
    @Test
    void requireUsableLockTimeout_multipliesByItsOwnBudget() {
        assertThrows(IllegalStateException.class,
            () -> lockWith(834, Duration.ofSeconds(5)).requireUsableLockTimeout());
        assertEquals(833, lockWith(833, Duration.ofSeconds(5)).requireUsableLockTimeout());
    }

    @Test
    void validateLockTimeoutOnStartup_rejectsAValueOutsideTheBand() {
        assertThrows(IllegalStateException.class,
            () -> lockWith(0, Duration.ofSeconds(5)).validateLockTimeoutOnStartup(null));
        assertDoesNotThrow(() -> lockWith(380, Duration.ofSeconds(5)).validateLockTimeoutOnStartup(null));
    }

    /** Y esta cableada al arranque: un metodo correcto que nadie llama deja la aplicacion arriba fallando. */
    @Test
    void validateLockTimeoutOnStartup_isWiredToTheStartupEvent() throws Exception {
        var method = OwnAccountRowLock.class.getDeclaredMethod("validateLockTimeoutOnStartup",
            io.quarkus.runtime.StartupEvent.class);
        assertTrue(method.getParameters()[0].isAnnotationPresent(Observes.class),
            "sin @Observes el metodo existe y nadie lo llama al arrancar");
    }

    /** El choque sale como el conflicto del cambio propio, con el tope del llamador. */
    @Test
    void lockThenFind_translatesALockTimeoutToAccountBusy() {
        OwnAccountRowLock lock = lockWith(380, Duration.ofSeconds(5));
        lock.rowLockSupport = Mockito.mock(RowLockSupport.class);
        when(lock.rowLockSupport.lockThenFind(eq(User.class), eq(7), anyInt()))
            .thenThrow(new PersistenceException(new SQLException("canceling statement due to lock timeout", "55P03")));

        ApiException thrown = assertThrows(ApiException.class, () -> lock.lockThenFind(7));

        assertEquals("AUTH-010", thrown.code());
        assertEquals(409, thrown.status());
    }

    /** La banda rige tambien en cada llamada, no solo al arrancar: con un tope fuera, no toma nada. */
    @Test
    void lockThenFind_withATimeoutOutsideTheBand_failsBeforeTakingTheRow() {
        OwnAccountRowLock lock = lockWith(0, Duration.ofSeconds(5));
        lock.rowLockSupport = Mockito.mock(RowLockSupport.class);

        assertThrows(IllegalStateException.class, () -> lock.lockThenFind(7));
        Mockito.verifyNoInteractions(lock.rowLockSupport);
    }

    /** El UPDATE que espera la tabla tambien sale como el conflicto, no como error del servidor. */
    @Test
    void flushTranslatingConflicts_translatesALockTimeoutToAccountBusy() {
        OwnAccountRowLock lock = lockWith(380, Duration.ofSeconds(5));
        lock.entityManager = Mockito.mock(EntityManager.class);
        Mockito.doThrow(new PersistenceException(new SQLException("canceling statement due to lock timeout", "55P03")))
            .when(lock.entityManager).flush();

        ApiException thrown = assertThrows(ApiException.class, () -> lock.flushTranslatingConflicts(7));

        assertEquals("AUTH-010", thrown.code());
    }

    @Test
    void lockThenFind_passesTheConfiguredTimeout_andLetsAnotherFailureThrough() {
        OwnAccountRowLock lock = lockWith(380, Duration.ofSeconds(5));
        lock.rowLockSupport = Mockito.mock(RowLockSupport.class);
        PersistenceException other = new PersistenceException(new SQLException("otra", "23505"));
        when(lock.rowLockSupport.lockThenFind(User.class, 7, 380)).thenThrow(other);

        assertSame(other, assertThrows(PersistenceException.class, () -> lock.lockThenFind(7)));
    }
}
