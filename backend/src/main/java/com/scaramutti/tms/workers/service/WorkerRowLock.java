package com.scaramutti.tms.workers.service;

import com.scaramutti.tms.shared.entity.Worker;
import com.scaramutti.tms.shared.repository.WorkerRepository;
import com.scaramutti.tms.workers.WorkersError;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.persistence.PersistenceException;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.hibernate.Session;
import org.hibernate.engine.spi.EntityKey;
import org.jboss.logging.Logger;

import java.sql.SQLException;
import java.util.Set;
import java.util.TreeSet;

/**
 * Lectura con LOCK de la fila de un trabajador, con su tope de espera y la traduccion del choque.
 *
 * <p>Es un componente propio y no tres lineas dentro del servicio porque las tres van SIEMPRE
 * juntas, y las usan igual la edicion y el cambio de estado. Separadas se
 * heredan de memoria, y al que se olvide del tope PostgreSQL le hace esperar para siempre,
 * inmovilizando un hilo y una conexion del pool COMPARTIDO con los otros modulos.
 *
 * <p>EL LOCK ES {@code FOR NO KEY UPDATE} Y NO {@code FOR UPDATE}: los dos serializan a dos
 * editores entre si, pero el primero no choca contra el lock de clave que PostgreSQL toma sobre
 * la fila PADRE al insertar una hija, y esta fila es padre de cuatro tablas, una de ellas de OTRO
 * modulo (quien recibe un retiro de almacen). Con {@code FOR UPDATE}, registrar un retiro
 * recibido por alguien y editar a esa misma persona se bloquearian SIEMPRE.
 *
 * <p>OJO CON EL CRITERIO, porque es facil escribirlo mal: para que una escritura sea un cambio
 * de CLAVE no hace falta que una clave foranea referencie esa columna; alcanza con que este en un
 * indice UNICO que PODRIA ser referenciado por una (unico, sin expresion y sin predicado). Esta tabla tiene {@code workers_document_number_key}
 * sobre el numero de documento, y la edicion lo escribe. O sea que la rama que CAMBIA el numero
 * si es un cambio de clave y si se serializa contra el retiro: ahi el desacople no existe y el
 * choque sale como el conflicto transitorio del contrato. El lock debil sigue siendo el correcto
 * porque evita ese acople en todas las demas ediciones, que son la mayoria.
 *
 * <p>Por eso el presupuesto cuenta ONCE topes y no uno. La cuenta que importa es cuanto puede
 * retener su conexion UNA transaccion, y el detalle esta en
 * {@link #MAX_LOCK_WAITS_PER_TRANSACTION}.
 *
 * <p>EL TOPE DE POSTGRESQL RIGE POR INTENTO DE LOCK, NO POR SENTENCIA: una sola sentencia que
 * cambia una columna con indice unico puede gastarlo dos veces, una al tomar la tupla y otra al
 * insertar en el indice. Esas esperas se CUENTAN, una por una. Antes se daban por absorbidas "por
 * la holgura que queda entre el total contado y la espera del pool", y esa frase convivia con
 * esta, que dice que el mecanismo puede gastar dos topes: la holgura era de un segundo. Medido en
 * un PostgreSQL 16, una sola sentencia que cambia la columna unica tarda ~1,5s con el tope en 1s.
 *
 * <p>Las esperas por integridad referencial siguen sin contarse, y eso si es defendible: el INSERT
 * de una hija toma {@code FOR KEY SHARE} sobre el padre, que no choca con el lock debil de esta
 * clase, asi que no esperan contra la transaccion que las emite.
 *
 * <p>Deuda anotada: esta clase y la de operaciones comparten mecanica (los estados del choque, el
 * tope, la guarda de escrituras pendientes) y difieren en lo que importa (la tabla, los codigos y
 * el presupuesto). No se unifican todavia a proposito: una abstraccion sobre dos casos cuyo
 * parametro central es distinto esconde justamente lo que hay que leer.
 */
@ApplicationScoped
public class WorkerRowLock {

    private static final Logger LOG = Logger.getLogger(WorkerRowLock.class);

    /**
     * Estados de PostgreSQL que significan "no pude tomar el lock": la espera agotada, el abrazo
     * mortal y el fallo de serializacion. Los tres son conflictos TRANSITORIOS y del negocio.
     */
    private static final Set<String> LOCK_CONFLICT_SQL_STATES = Set.of("55P03", "40P01", "40001");

    private static final String WORKERS_TABLE = "public.workers";

    /**
     * Los topes de espera que una transaccion de este modulo puede gastar, CONTADOS UNO POR UNO con
     * UN titular y una cola sobre cada fila. El tope del motor rige por cada espera, y no cuestan lo
     * mismo (medido en un PostgreSQL 16 con tope de 600ms, con una sesion que retiene y otra en cola):
     *
     * <ul>
     *   <li>tomar una fila, o actualizar una que no se tomo antes, gasta DOS si hay cola: primero el
     *       bloqueo de la tupla que tiene el primero de la cola y, cuando ese se rinde, otro tope
     *       entero contra quien la retiene (1211ms);
     *   <li>esperar en un indice unico, o cambiar la clave de una fila ya tomada, gasta UNO por
     *       titular (659ms y 601ms).
     * </ul>
     *
     * <p>La edicion es la que mas gasta, ONCE: la fila del destino (2), la de quien actua (2), el
     * documento (tupla 1 e indice 1), la ficha (tupla 2 e indice de la licencia 1) y la cuenta (2).
     * Desactivar gasta ocho (destino, quien actua, ficha y cuenta, dos cada una); reactivar, seis
     * (destino, quien actua y ficha); el alta, cuatro (quien actua 2, documento 1, licencia 1).
     *
     * <p>NO ES UN TECHO: la misma espera se repite con cada titular distinto. Tres caminos lo pasan
     * sin error: cambiar una clave con varios bloqueos de clave de hijas en curso (se espera a cada
     * uno, medido 936ms con tope de 400), insertadores del mismo valor que abortan uno tras otro en
     * el indice, y la cadena de versiones de una fila que cambia mientras se la espera. PostgreSQL 16
     * no tiene techo por transaccion y el margen bajo el pool es lo que absorbe esos casos; el techo
     * real llega con {@code transaction_timeout} al subir a PostgreSQL 17.
     *
     * <p>ESTE NUMERO FUE 4 Y ESTABA MAL CONTADO: las dos esperas de indice se daban por absorbidas
     * "por la holgura", y la holgura era de un segundo mientras el mecanismo podia gastar dos. Se
     * midio en un PostgreSQL 16: una sola sentencia que cambia la columna unica tarda ~1,5s con el
     * tope en 1s.
     *
     * <p>LO QUE CUENTA ES LA COLUMNA QUE ESTA EDICION CAMBIA, no cuantos indices unicos tenga la
     * tabla. Las tres tablas tienen mas: {@code drivers_worker_id_key}, {@code users_worker_id_key}
     * y {@code users_username_key} —verificados contra el esquema, no supuestos— y NO suman, porque
     * esta operacion no toca esas columnas y una insercion de indice que repite el valor anterior
     * de la propia fila no espera contra nadie. Sumar una columna unica obliga a recontar aca solo
     * si la edicion la escribe.
     *
     * <p>El INSERT de la ficha que nace no suma: es excluyente con su UPDATE.
     */
    static final int MAX_LOCK_WAITS_PER_TRANSACTION = 11;

    /**
     * En MILISEGUNDOS y no en segundos: con once topes, el valor entero mas chico expresable en
     * segundos ya se pasa del techo del pool. La unidad nativa del motor para esta opcion es el
     * milisegundo, asi que ademas se escribe tal cual.
     */
    @ConfigProperty(name = "app.workers.edit-lock-timeout-ms")
    int lockTimeoutMillis;

    /** Lo que el pool espera para entregar una conexion: el techo del presupuesto. */
    @ConfigProperty(name = "quarkus.datasource.jdbc.acquisition-timeout")
    java.time.Duration poolAcquisitionTimeout;

    @Inject WorkerRepository workerRepository;
    @Inject EntityManager entityManager;

    /**
     * Toma el lock de la fila y la devuelve, o el 404 del modulo si no existe.
     *
     * <p>SE LLAMA ANTES DE TOCAR LA ENTIDAD. El lock se toma releyendo la fila, y releer DESCARTA
     * lo que se le haya cambiado en memoria: quien mute primero y bloquee despues pierde su cambio
     * sin ningun error, y termina con un 200 y un rastro escrito sobre una fila que no se movio.
     * Por eso el metodo revienta ruidoso si lo llaman con cambios pendientes.
     *
     * <p>Se relee ADEMAS de bloquear porque si la fila ya estaba cargada, el lock la toma pero
     * devuelve la instancia cacheada, con las columnas viejas.
     *
     * <p>EXIGE transaccion, y no es formalidad: fuera de una, la sentencia que pone el tope no
     * hace nada y PostgreSQL solo lo avisa por un aviso que la aplicacion no ve. Un llamador que
     * olvidara la anotacion obtendria exactamente la espera infinita que esta clase existe para
     * evitar, sin ningun error.
     */
    @Transactional(Transactional.TxType.MANDATORY)
    public Worker findByIdForUpdate(Integer workerId) {
        // El orden importa: cualquier consulta nativa, incluida la del tope, dispara el volcado
        // de lo que este pendiente, y despues de eso ya no hay nada sucio que detectar.
        requireNothingPendingToWrite();
        applyLockTimeout();

        return runTranslatingLockConflicts(() -> readAndLock(workerId), workerId);
    }

    private Worker readAndLock(Integer workerId) {
        Worker worker = workerRepository.findById(workerId);
        if (worker == null) {
            throw WorkersError.NOT_FOUND.toException();
        }
        try {
            entityManager.createNativeQuery(
                    "SELECT id FROM " + WORKERS_TABLE + " WHERE id = ?1 FOR NO KEY UPDATE")
                .setParameter(1, workerId).getSingleResult();
            entityManager.refresh(worker);
        } catch (NoResultException e) {
            // La fila se borro entre la primera lectura y el lock. Es el mismo caso que el 404 de
            // arriba, no un error del servidor; sin este bloque sale como 500.
            throw WorkersError.NOT_FOUND.toException();
        }
        return worker;
    }

    /**
     * Corre un bloque que puede chocar contra el lock: tomarlo, o escribir con el ya tomado.
     *
     * <p>El tope rige TODA la transaccion, no solo la lectura: una escritura posterior tambien
     * puede chocar, y sin traducirla el error del servidor vuelve por la ventana.
     */
    public <T> T runTranslatingLockConflicts(java.util.function.Supplier<T> action, Integer workerId) {
        try {
            return action.get();
        } catch (PersistenceException e) {
            throw asLockConflictOrRethrow(e, workerId);
        }
    }

    private void requireNothingPendingToWrite() {
        Session session = entityManager.unwrap(Session.class);
        if (session.isDirty()) {
            throw new IllegalStateException(
                "findByIdForUpdate tiene que llamarse ANTES de modificar la entidad: al releer la "
                    + "fila para bloquearla se descartan los cambios pendientes. "
                    + "Entidades cargadas en la transaccion: " + loadedEntityNames(session));
        }
    }

    private String loadedEntityNames(Session session) {
        Set<String> names = new TreeSet<>();
        for (Object key : session.getStatistics().getEntityKeys()) {
            String entityName = ((EntityKey) key).getEntityName();
            names.add(entityName.substring(entityName.lastIndexOf('.') + 1));
        }
        return names.isEmpty() ? "(ninguna)" : String.join(", ", names);
    }

    private void applyLockTimeout() {
        // Concatenado y no parametrizado porque el motor no admite parametro en esta sentencia.
        // Es seguro: el valor es un entero de configuracion ya validado, nunca viene del pedido.
        entityManager.createNativeQuery(
            "SET LOCAL lock_timeout = '" + requireUsableLockTimeout() + "ms'").executeUpdate();
    }

    /**
     * La misma guarda, AL ARRANCAR.
     *
     * <p>La de cada transaccion sigue siendo la que protege, pero sola dejaba un modo de falla
     * silencioso: un tope invalido que llegara por variable de entorno levantaba la aplicacion
     * limpia, pasaba los chequeos de salud, y cada edicion de trabajadores fallaba con 500. Con
     * esto, el valor malo no deja la aplicacion arriba: el despliegue falla donde se ve.
     */
    void validateLockTimeoutOnStartup(@Observes StartupEvent startup) {
        requireUsableLockTimeout();
    }

    /**
     * El tope tiene una BANDA valida, no solo un piso.
     *
     * <p>Por abajo: PostgreSQL lee cero como "sin tope", que es exactamente la espera infinita que
     * esta propiedad existe para evitar; en cero se desactivaria en silencio.
     *
     * <p>Por arriba: quien espera retiene su conexion todo ese tiempo, asi que el total acumulado
     * de una transaccion tiene que quedar debajo de lo que el pool espera para ENTREGAR una
     * conexion. Si no, se invierte el orden de las rendiciones y los otros modulos se quedan sin
     * conexiones antes de que los que esperan se rindan.
     *
     * <p>La comparacion es en milisegundos de punta a punta: redondear el techo del pool a
     * segundos perdia la fraccion que justamente decide si el presupuesto entra.
     */
    int requireUsableLockTimeout() {
        long budget = (long) lockTimeoutMillis * MAX_LOCK_WAITS_PER_TRANSACTION;
        if (lockTimeoutMillis <= 0 || budget >= poolAcquisitionTimeout.toMillis()) {
            throw new IllegalStateException(
                "app.workers.edit-lock-timeout-ms tiene que ser mayor que cero, y su total "
                    + "acumulado (" + lockTimeoutMillis + "ms x " + MAX_LOCK_WAITS_PER_TRANSACTION
                    + " esperas = " + budget + "ms) tiene que quedar debajo de la espera del pool ("
                    + poolAcquisitionTimeout + "). Valor configurado: " + lockTimeoutMillis);
        }
        return lockTimeoutMillis;
    }

    /**
     * Traduce el choque de lock al conflicto del contrato, y deja pasar cualquier otra cosa.
     *
     * <p>Se decide por el ESTADO que reporta el motor y no por el tipo de la excepcion: el tipo no
     * es fiable, el abrazo mortal llega como una excepcion de bloqueo optimista que un bloque por
     * tipo dejaria escapar como error del servidor.
     */
    /**
     * El mensaje PRIMARIO del error, sin los campos de detalle.
     *
     * <p>El mensaje compuesto arrastra el campo de detalle, y ahi es donde el motor pone los
     * VALORES de la fila que choco. Para los tres estados que esta clase traduce ese campo viene
     * vacio, pero eso lo garantiza la lista de estados y no el logueo: la lista esta a cien lineas
     * de aca, y el dia que alguien le sume uno, el documento de una persona entraria al registro
     * de produccion sin que falle ningun caso. Tomando el primario, la garantia vive aca.
     */
    private String primaryMessageOf(SQLException sqlException) {
        if (sqlException instanceof org.postgresql.util.PSQLException psql
            && psql.getServerErrorMessage() != null) {
            return psql.getServerErrorMessage().getMessage();
        }
        return sqlException.getSQLState();
    }

    RuntimeException asLockConflictOrRethrow(PersistenceException e, Integer workerId) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sqlException
                    && LOCK_CONFLICT_SQL_STATES.contains(sqlException.getSQLState())) {
                // El tope rige TODA la transaccion, asi que el choque pudo ser con la fila del
                // trabajador, con la de su ficha o con un catalogo. El mensaje del motor es lo
                // unico que nombra la relacion, y no trae valores de la fila: dice que sentencia
                // se cancelo y sobre que tabla. El id va; datos de la persona, ninguno.
                LOG.warnf("Conflicto de lock (%s) %s con un tope de %dms: %s", sqlException.getSQLState(),
                    workerId == null ? "en un alta" : "sobre la fila del trabajador id=" + workerId,
                    lockTimeoutMillis, primaryMessageOf(sqlException));
                return WorkersError.WORKER_LOCKED.toException();
            }
        }
        return e;
    }
}
