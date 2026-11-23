package com.scaramutti.tms.auth.service;

import com.scaramutti.tms.auth.AuthError;
import com.scaramutti.tms.shared.entity.User;
import com.scaramutti.tms.shared.repository.RowLockSupport;
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
 * La fila de la cuenta para el cambio de la propia contrasena: tomada antes de cargarla, con el tope
 * de las cuentas, y el choque como {@code AUTH-010}. Asi el cambio decide contra la fila confirmada:
 * si otra escritura cambio el hash mientras se esperaba, ya no es el que se verifico.
 */
@ApplicationScoped
public class OwnAccountRowLock {

    private static final String PROPERTY = "app.accounts.lock-timeout-ms";

    /**
     * Los topes que el cambio propio puede gastar, medidos en un PostgreSQL 16: la tabla al tomar la
     * fila (uno, solo con EXCLUSIVE: la lectura del hash ya la tiene compartida); la fila con cola (DOS:
     * la tupla del primero y, al rendirse, quien la retiene); la lectura (uno por tabla: trabajador y
     * cargo); el INSERT del rastro (DOS: su tabla, con SHARE o mas, y despues su secuencia); la de
     * cuentas en el UPDATE (uno, con SHARE o SHARE ROW EXCLUSIVE). La lectura del hash corre antes y sin
     * tope. Una cola de escrituras suma una por cada una: la cubre que retienen la fila milisegundos, sin BCrypt.
     */
    static final int MAX_LOCK_WAITS_PER_TRANSACTION = 8;

    /** El tope de las filas de cuentas: quien las escriba lo multiplica por su propio presupuesto. */
    @ConfigProperty(name = PROPERTY)
    int lockTimeoutMillis;

    /** Lo que el pool espera para entregar una conexion: el techo del presupuesto. */
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
     * Corre las altas del llamador y escribe lo pendiente dentro del traductor: el INSERT del rastro
     * (su id lo genera la base, asi que sale al persistirlo) y el UPDATE tambien pueden esperar una
     * tabla o la secuencia, y fuera de el su choque saldria como error del servidor.
     */
    @Transactional(Transactional.TxType.MANDATORY)
    public void flushTranslatingConflicts(Integer userId, Runnable inserts) {
        try {
            inserts.run();
            entityManager.flush();
        } catch (PersistenceException e) {
            throw asAccountBusy(e, userId);
        }
    }

    /**
     * El choque puede ser con la fila, una tabla o la secuencia que el cambio lee o escribe: el
     * registro nombra la cuenta.
     */
    private RuntimeException asAccountBusy(PersistenceException e, Integer userId) {
        return RowLockSupport.asLockConflictOrRethrow(e, AuthError.ACCOUNT_BUSY,
            "la cuenta id=" + userId + " (su fila, una de sus tablas o la secuencia del rastro) con un tope de "
                + lockTimeoutMillis + "ms");
    }

    /**
     * La banda, tambien al arrancar: sin esto, un tope invalido por variable de entorno levantaba
     * la aplicacion y cada cambio de contrasena fallaba con 500.
     */
    void validateLockTimeoutOnStartup(@Observes StartupEvent startup) {
        requireUsableLockTimeout();
    }

    int requireUsableLockTimeout() {
        return RowLockSupport.requireUsableLockTimeout(PROPERTY, lockTimeoutMillis, MAX_LOCK_WAITS_PER_TRANSACTION,
            poolAcquisitionTimeout);
    }
}
