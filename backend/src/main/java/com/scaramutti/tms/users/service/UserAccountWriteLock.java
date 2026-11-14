package com.scaramutti.tms.users.service;

import com.scaramutti.tms.shared.entity.User;
import com.scaramutti.tms.shared.entity.Worker;
import com.scaramutti.tms.shared.exception.ConstraintViolations;
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
import org.hibernate.exception.ConstraintViolationException;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * La fila que toman las escrituras de Usuarios: la de la cuenta, o la de su trabajador en el alta,
 * cuando la cuenta todavia no existe. Tomada antes de cargarla, con el tope de las cuentas, y el
 * choque como {@code USR-009}; en el alta, tambien los dos unicos de la cuenta.
 */
@ApplicationScoped
public class UserAccountWriteLock {

    private static final Logger LOG = Logger.getLogger(UserAccountWriteLock.class);

    private static final String PROPERTY = "app.accounts.lock-timeout-ms";

    /**
     * Las del alta, que es la que mas espera, contadas una por una: la tabla y la fila del trabajador
     * (con cola, dos), la tabla de cuentas, su secuencia y sus unicos del nombre y del trabajador, la
     * fila de quien firma (con cola, dos), y la tabla y la secuencia del rastro.
     */
    static final int MAX_LOCK_WAITS_PER_TRANSACTION = 11;

    private static final Map<String, UsersError> CREATE_CONSTRAINTS = Map.of(
        "users_username_key", UsersError.USERNAME_TAKEN,
        "users_worker_id_key", UsersError.WORKER_HAS_ACCOUNT);

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

    @Transactional(Transactional.TxType.MANDATORY)
    public Optional<Worker> lockWorkerThenFind(Integer workerId) {
        try {
            return rowLockSupport.lockThenFind(Worker.class, workerId, requireUsableLockTimeout());
        } catch (PersistenceException e) {
            throw asWorkerBusy(e, workerId);
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

    /**
     * Las altas del llamador, dentro del traductor por lo mismo que las escrituras. Ademas, otra alta
     * a la vez puede ganarle el nombre: eso es su 409, y no un error del servidor.
     */
    @Transactional(Transactional.TxType.MANDATORY)
    public void insertTranslatingConflicts(Integer workerId, Runnable inserts) {
        try {
            inserts.run();
            entityManager.flush();
        } catch (PersistenceException e) {
            throw asCreateConflict(e, workerId);
        }
    }

    private RuntimeException asCreateConflict(PersistenceException e, Integer workerId) {
        Optional<ConstraintViolationException> violation = ConstraintViolations.of(e);
        if (violation.isEmpty()) {
            return asWorkerBusy(e, workerId);
        }
        String constraintName = ConstraintViolations.nameOf(violation.get());
        UsersError known = constraintName == null ? null : CREATE_CONSTRAINTS.get(constraintName);
        if (known == null) {
            return e;
        }
        LOG.warnf("Alta de una cuenta: choco %s", constraintName);
        return known.toException();
    }

    private RuntimeException asAccountBusy(PersistenceException e, Integer userId) {
        return RowLockSupport.asLockConflictOrRethrow(e, UsersError.ACCOUNT_BUSY,
            "la cuenta id=" + userId + " (su fila, una de sus tablas o la secuencia del rastro) con un tope de "
                + lockTimeoutMillis + "ms");
    }

    private RuntimeException asWorkerBusy(PersistenceException e, Integer workerId) {
        return RowLockSupport.asLockConflictOrRethrow(e, UsersError.ACCOUNT_BUSY,
            "el alta de la cuenta del trabajador id=" + workerId
                + " (su fila, una de las tablas o secuencias del alta, el nombre que inserta otra alta"
                + " o la cuenta de quien firma) con un tope de "
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
