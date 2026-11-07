package com.scaramutti.tms.users.service;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.event.Observes;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.time.Duration;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** El tope de la fila de la cuenta en las escrituras de Usuarios: la configuracion real, el presupuesto y el arranque. */
class UserAccountWriteLockTest {

    private static UserAccountWriteLock lockWith(int lockTimeoutMillis, Duration poolWait) {
        UserAccountWriteLock lock = new UserAccountWriteLock();
        lock.lockTimeoutMillis = lockTimeoutMillis;
        lock.poolAcquisitionTimeout = poolWait;
        return lock;
    }

    private static UserAccountWriteLock lockFromTheFile(String profilePrefix) throws Exception {
        Properties config = new Properties();
        try (InputStream file = UserAccountWriteLockTest.class.getResourceAsStream("/application.properties")) {
            config.load(file);
        }
        return lockWith(Integer.parseInt(config.getProperty(profilePrefix + "app.accounts.lock-timeout-ms")),
            Duration.parse("PT" + config.getProperty(profilePrefix + "quarkus.datasource.jdbc.acquisition-timeout")
                .toUpperCase()));
    }

    @Test
    void theProductionAndTestConfigurations_passTheGuard() throws Exception {
        assertEquals(380, lockFromTheFile("").requireUsableLockTimeout());
        assertEquals(600, lockFromTheFile("%test.").requireUsableLockTimeout());
    }

    /** Ocho esperas: 625 x 8 llega a los 5 s del pool y no entra; 624, si. */
    @Test
    void requireUsableLockTimeout_multipliesByEightWaits() {
        assertEquals(8, UserAccountWriteLock.MAX_LOCK_WAITS_PER_TRANSACTION);
        assertThrows(IllegalStateException.class,
            () -> lockWith(625, Duration.ofSeconds(5)).requireUsableLockTimeout());
        assertEquals(624, lockWith(624, Duration.ofSeconds(5)).requireUsableLockTimeout());
    }

    @Test
    void validateLockTimeoutOnStartup_rejectsAValueOutsideTheBand_andIsWiredToTheStartupEvent() throws Exception {
        assertThrows(IllegalStateException.class,
            () -> lockWith(0, Duration.ofSeconds(5)).validateLockTimeoutOnStartup(null));
        assertDoesNotThrow(() -> lockWith(380, Duration.ofSeconds(5)).validateLockTimeoutOnStartup(null));
        var method = UserAccountWriteLock.class.getDeclaredMethod("validateLockTimeoutOnStartup", StartupEvent.class);
        assertTrue(method.getParameters()[0].isAnnotationPresent(Observes.class),
            "sin @Observes el metodo existe y nadie lo llama al arrancar");
    }
}
