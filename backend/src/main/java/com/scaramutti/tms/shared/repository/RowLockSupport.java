package com.scaramutti.tms.shared.repository;

import com.scaramutti.tms.shared.exception.ApiError;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.Table;
import jakarta.transaction.Transactional;
import org.hibernate.Session;
import org.hibernate.engine.spi.EntityKey;
import org.jboss.logging.Logger;

import java.sql.SQLException;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;

/**
 * Tomar una fila con tope de espera, para los que escriben cuentas: el tope con su banda, las guardas
 * de lo ya cargado o pendiente, el lock antes de la lectura y la traduccion del choque. Cada llamador
 * pone su propiedad del tope, su presupuesto de esperas y su codigo. El lock es {@code FOR NO KEY
 * UPDATE}, como el de trabajadores: no choca con el {@code FOR KEY SHARE} del alta de una fila que la
 * cuenta firma. {@code WorkerRowLock} y el de viajes siguen con la suya (pasarlos es otro refactor).
 */
@ApplicationScoped
public class RowLockSupport {

    private static final Logger LOG = Logger.getLogger(RowLockSupport.class);

    /**
     * Estados de PostgreSQL que significan "no pude tomar el lock": la espera agotada, el abrazo
     * mortal y el fallo de serializacion. Los tres son conflictos transitorios del negocio.
     */
    static final Set<String> LOCK_CONFLICT_SQL_STATES = Set.of("55P03", "40P01", "40001");

    @Inject EntityManager entityManager;

    /**
     * Toma la fila y RECIEN DESPUES la carga, o vacio si no existe.
     *
     * <p>El orden es el punto: cargada antes de esperar, la entidad trae las columnas de antes de
     * la espera, y quien la decide con ellas (una clave que otro acaba de cambiar) decide sobre una
     * fila que ya no es esa. Por eso falla ruidoso si la fila ya esta en memoria o si hay algo
     * pendiente de escribir: la sentencia del lock volcaria esos cambios antes de tomarlo.
     *
     * <p>Exige transaccion: el tope y el lock duran lo que ella, y sin una se rechaza de entrada.
     */
    @Transactional(Transactional.TxType.MANDATORY)
    public <T> Optional<T> lockThenFind(Class<T> entityType, Integer id, int lockTimeoutMillis) {
        String table = tableOf(entityType);
        requireNotLoadedYet(entityType, id);
        // Lo pendiente se mira antes del tope: cualquier consulta nativa lo vuelca.
        requireNothingPendingToWrite();
        applyLockTimeout(lockTimeoutMillis);

        entityManager.createNativeQuery("SELECT id FROM " + table + " WHERE id = ?1 FOR NO KEY UPDATE")
            .setParameter(1, id).getResultList();
        return Optional.ofNullable(entityManager.find(entityType, id));
    }

    /** La tabla sale del mapeo de la entidad y no de quien llama: asi no se toma una fila y se carga otra. */
    private static String tableOf(Class<?> entityType) {
        Table table = entityType.getAnnotation(Table.class);
        if (table == null || table.name().isEmpty()) {
            throw new IllegalArgumentException(entityType.getSimpleName() + " no declara su tabla con @Table");
        }
        return (table.schema().isEmpty() ? "public" : table.schema()) + "." + table.name();
    }

    private void requireNotLoadedYet(Class<?> entityType, Integer id) {
        Session session = entityManager.unwrap(Session.class);
        for (Object key : session.getStatistics().getEntityKeys()) {
            EntityKey entityKey = (EntityKey) key;
            if (entityKey.getEntityName().equals(entityType.getName()) && id.equals(entityKey.getIdentifier())) {
                throw new IllegalStateException("La fila " + entityType.getSimpleName() + " id=" + id
                    + " ya esta cargada: hay que tomarla ANTES de leerla, o se decide con la copia de antes"
                    + " de esperar.");
            }
        }
    }

    private void requireNothingPendingToWrite() {
        if (entityManager.unwrap(Session.class).isDirty()) {
            throw new IllegalStateException(
                "Hay cambios pendientes: la fila se toma antes de modificar nada en la transaccion.");
        }
    }

    private void applyLockTimeout(int lockTimeoutMillis) {
        // Cero es "sin tope" para el motor: quien llame sin pasar por su banda no espera para siempre.
        if (lockTimeoutMillis <= 0) {
            throw new IllegalArgumentException("El tope del lock tiene que ser mayor que cero: " + lockTimeoutMillis);
        }
        // Concatenado y no parametrizado porque el motor no admite parametro en esta sentencia. Es
        // seguro: el valor es un entero de configuracion ya validado, nunca viene del pedido.
        entityManager.createNativeQuery("SET LOCAL lock_timeout = '" + lockTimeoutMillis + "ms'").executeUpdate();
    }

    /**
     * La banda valida del tope, no solo un piso. Por abajo, PostgreSQL lee cero como "sin tope":
     * la espera infinita que la propiedad existe para evitar. Por arriba, quien espera retiene su
     * conexion, asi que el acumulado de la transaccion (el tope por las esperas que el llamador
     * puede gastar) tiene que quedar debajo de lo que el pool espera para entregar una; si no, los
     * otros modulos se quedan sin conexiones antes de que los que esperan se rindan.
     */
    public static int requireUsableLockTimeout(String property, int lockTimeoutMillis, int maxLockWaits,
            Duration poolAcquisitionTimeout) {
        long budget = (long) lockTimeoutMillis * maxLockWaits;
        if (lockTimeoutMillis <= 0 || budget >= poolAcquisitionTimeout.toMillis()) {
            throw new IllegalStateException(property + " tiene que ser mayor que cero, y su total acumulado ("
                + lockTimeoutMillis + "ms x " + maxLockWaits + " esperas = " + budget
                + "ms) tiene que quedar debajo de la espera del pool (" + poolAcquisitionTimeout
                + "). Valor configurado: " + lockTimeoutMillis);
        }
        return lockTimeoutMillis;
    }

    /**
     * Traduce el choque al conflicto del llamador y deja pasar cualquier otra cosa. Se decide por el
     * estado del motor y no por el tipo de la excepcion, que depende del camino y de la version de
     * Hibernate. Al registro va el mensaje primario del motor, que dice que paso (el tope, el abrazo
     * mortal o la serializacion); la fila la nombra quien llama. El detalle, que puede traer valores de
     * la fila, no va.
     */
    public static RuntimeException asLockConflictOrRethrow(PersistenceException e, ApiError conflict,
            String rowDescription) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sqlException
                    && LOCK_CONFLICT_SQL_STATES.contains(sqlException.getSQLState())) {
                LOG.warnf("Conflicto de lock (%s) sobre %s: %s", sqlException.getSQLState(), rowDescription,
                    primaryMessageOf(sqlException));
                return conflict.toException();
            }
        }
        return e;
    }

    private static String primaryMessageOf(SQLException sqlException) {
        if (sqlException instanceof org.postgresql.util.PSQLException psql
                && psql.getServerErrorMessage() != null) {
            return psql.getServerErrorMessage().getMessage();
        }
        return sqlException.getSQLState();
    }
}
