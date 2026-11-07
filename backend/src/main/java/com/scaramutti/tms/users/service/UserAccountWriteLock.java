package com.scaramutti.tms.users.service;

import com.scaramutti.tms.shared.entity.User;
import com.scaramutti.tms.shared.repository.RowLockSupport;
import com.scaramutti.tms.users.UsersError;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;
import java.util.Optional;

/**
 * La fila de la cuenta para las escrituras de Usuarios: tomada antes de cargarla, con el tope de las
 * cuentas, y el choque como {@code USR-009}.
 */
@ApplicationScoped
public class UserAccountWriteLock {

    private static final String PROPERTY = "app.accounts.lock-timeout-ms";

    /**
     * Contadas y no medidas: la tabla al tomar la fila, la fila con cola (dos), la tabla y la
     * secuencia del rastro, la de cuentas en el UPDATE y la fila de quien firma (con cola, dos).
     */
    static final int MAX_LOCK_WAITS_PER_TRANSACTION = 8;

    @ConfigProperty(name = PROPERTY)
    int lockTimeoutMillis;

    @ConfigProperty(name = "quarkus.datasource.jdbc.acquisition-timeout")
    Duration poolAcquisitionTimeout;

    @Inject RowLockSupport rowLockSupport;
    @Inject EntityManager entityManager;

    /** La cuenta, tomada y leida despues, o vacio si no existe. */
    @Transactional(Transactional.TxType.MANDATORY)
    public Optional<User> lockThenFind(Integer userId) {
        try {
            return rowLockSupport.lockThenFind(User.class, userId, requireUsableLockTimeout());
        } catch (PersistenceException e) {
            throw asAccountBusy(e, userId);
        }
    }

    /**
     * Los cambios del llamador y su escritura, dentro del traductor: el INSERT del rastro sale al
     * persistirlo, y su espera fuera de aca seria un error del servidor.
     */
    @Transactional(Transactional.TxType.MANDATORY)
    public void writeTranslatingConflicts(Integer userId, Runnable changes) {
        try {
            changes.run();
            entityManager.flush();
        } catch (PersistenceException e) {
            throw asAccountBusy(e, userId);
        }
    }

    private RuntimeException asAccountBusy(PersistenceException e, Integer userId) {
        return RowLockSupport.asLockConflictOrRethrow(e, UsersError.ACCOUNT_BUSY,
            "la cuenta id=" + userId + " (su fila, una de sus tablas o la secuencia del rastro) con un tope de "
                + lockTimeoutMillis + "ms");
    }

    /** Sin esto, un tope invalido levanta la aplicacion y cada escritura falla con 500. */
    void validateLockTimeoutOnStartup(@Observes StartupEvent startup) {
        requireUsableLockTimeout();
    }

    int requireUsableLockTimeout() {
        return RowLockSupport.requireUsableLockTimeout(PROPERTY, lockTimeoutMillis, MAX_LOCK_WAITS_PER_TRANSACTION,
            poolAcquisitionTimeout);
    }
}
