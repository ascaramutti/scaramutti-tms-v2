package com.scaramutti.tms.users.service;

import com.scaramutti.tms.shared.exception.ApiException;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.event.Observes;
import jakarta.persistence.PersistenceException;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;

import java.io.InputStream;
import java.time.Duration;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * El tope de las escrituras de Usuarios (la configuracion real, el presupuesto y el arranque) y la
 * traduccion de lo que choca en el alta, con los errores del motor armados a mano.
 */
class UserAccountWriteLockTest {

    private static final char END_OF_FIELD = (char) 0;

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

    /** Once esperas: 455 x 11 pasa los 5 s del pool y no entra; 454, si. */
    @Test
    void requireUsableLockTimeout_multipliesByElevenWaits() {
        assertEquals(11, UserAccountWriteLock.MAX_LOCK_WAITS_PER_TRANSACTION);
        assertThrows(IllegalStateException.class,
            () -> lockWith(455, Duration.ofSeconds(5)).requireUsableLockTimeout());
        assertEquals(454, lockWith(454, Duration.ofSeconds(5)).requireUsableLockTimeout());
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

    // ---------- lo que choca en el alta ---------------------------------------------

    @ParameterizedTest
    @CsvSource({"users_username_key, USR-002", "users_worker_id_key, USR-003"})
    void insertTranslatingConflicts_anotherCreationThatWonTheKey_isItsConflict(String constraint, String code) {
        ApiException conflict = assertThrows(ApiException.class,
            () -> lockWith(380, Duration.ofSeconds(5)).insertTranslatingConflicts(31, () -> {
                throw violationOf(constraint);
            }));

        assertEquals(code, conflict.code());
    }

    /** Una restriccion que ninguna carrera del alta alcanza es un error de verdad: sale como llego. */
    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"chk_users_username_lowercase", "users_worker_id_fkey", "users_pkey"})
    void insertTranslatingConflicts_anyOtherViolation_propagatesAsIs(String constraint) {
        ConstraintViolationException violation = violationOf(constraint);

        assertSame(violation, assertThrows(ConstraintViolationException.class,
            () -> lockWith(380, Duration.ofSeconds(5)).insertTranslatingConflicts(31, () -> {
                throw violation;
            })));
    }

    @Test
    void insertTranslatingConflicts_aLockThatTimedOut_isTheTransientConflict() {
        ApiException conflict = assertThrows(ApiException.class,
            () -> lockWith(380, Duration.ofSeconds(5)).insertTranslatingConflicts(31, () -> {
                throw new PersistenceException(serverError("55P03", "canceling statement due to lock timeout", null));
            }));

        assertEquals("USR-009", conflict.code());
    }

    @Test
    void insertTranslatingConflicts_anErrorThatIsNeitherAViolationNorALock_propagatesAsIs() {
        PersistenceException error = new PersistenceException(serverError("22001", "value too long", null));

        assertSame(error, assertThrows(PersistenceException.class,
            () -> lockWith(380, Duration.ofSeconds(5)).insertTranslatingConflicts(31, () -> {
                throw error;
            })));
    }

    /** Hibernate sin el nombre, como con el motor en otro idioma: el del protocolo decide. */
    private static ConstraintViolationException violationOf(String constraint) {
        String sqlState = constraint == null || constraint.endsWith("_key") || constraint.endsWith("_pkey") ? "23505"
            : constraint.endsWith("_fkey") ? "23503" : "23514";
        return new ConstraintViolationException("violacion de prueba", serverError(sqlState, "violacion", constraint),
            null);
    }

    /** Un error como llega por el cable: cada campo con su letra y cerrado por el byte cero. */
    private static PSQLException serverError(String sqlState, String message, String constraint) {
        StringBuilder wire = new StringBuilder()
            .append('S').append("ERROR").append(END_OF_FIELD)
            .append('C').append(sqlState).append(END_OF_FIELD)
            .append('M').append(message).append(END_OF_FIELD);
        if (constraint != null) {
            wire.append('n').append(constraint).append(END_OF_FIELD);
        }
        return new PSQLException(new ServerErrorMessage(wire.toString()));
    }
}
