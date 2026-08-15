package com.scaramutti.tms.operations.service;

import com.scaramutti.tms.operations.OperationsError;
import com.scaramutti.tms.shared.entity.Service;
import com.scaramutti.tms.shared.repository.ServiceRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.persistence.PersistenceException;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.hibernate.Session;
import org.hibernate.engine.spi.EntityKey;
import org.jboss.logging.Logger;

import java.sql.SQLException;
import java.util.Set;
import java.util.TreeSet;

/**
 * Lectura con LOCK de la fila de un servicio, con su tope de espera y la traduccion del conflicto.
 *
 * <p>Es un componente propio y no tres lineas dentro del service que edita, porque las tres van
 * SIEMPRE juntas y el proximo endpoint que escriba sobre esta misma fila (asignar recursos,
 * cambiar de estado) las necesita igual. Separadas, se heredan de memoria: al que se olvide del
 * tope, PostgreSQL le hace esperar para siempre, y cada request en cola le inmoviliza un hilo y una
 * conexion del pool, que es COMPARTIDO con los otros modulos. El tope no elimina esa presion: le
 * pone un techo, y por eso se lo configura por DEBAJO del tiempo que el pool espera para entregar
 * una conexion (si fuera al reves, el resto de los modulos se quedaria sin conexiones antes de que
 * los que esperan se rindan). Juntas, las tres se heredan por defecto.
 */
@ApplicationScoped
public class ServiceRowLock {

    private static final Logger LOG = Logger.getLogger(ServiceRowLock.class);

    /**
     * Estados de PostgreSQL que significan "no pude tomar el lock": la espera agotada, el abrazo
     * mortal y el fallo de serializacion. Los tres son conflictos TRANSITORIOS y del negocio, no
     * fallas del servidor.
     *
     * <p>El tercero hoy no ocurre: con el nivel de aislamiento por defecto (READ COMMITTED) la
     * relectura de abajo ve la version ya confirmada y sigue. Esta igual porque el flow depende de
     * ese nivel sin declararlo, y subirlo —en este archivo o en el de configuracion, que comparten
     * los otros modulos— haria que el mismo choque saliera como 500.
     */
    private static final Set<String> LOCK_CONFLICT_SQL_STATES = Set.of("55P03", "40P01", "40001");

    /** La tabla, calificada con su schema: la consulta es nativa y no pasa por el metamodelo. */
    private static final String SERVICES_TABLE = "operaciones.services";

    @ConfigProperty(name = "app.operations.edit-lock-timeout-seconds")
    int lockTimeoutSeconds;

    /** Lo que el pool espera para entregar una conexion: el techo de la banda de arriba. */
    @ConfigProperty(name = "quarkus.datasource.jdbc.acquisition-timeout")
    java.time.Duration poolAcquisitionTimeout;

    @Inject ServiceRepository serviceRepository;
    @Inject EntityManager entityManager;

    /**
     * Toma el lock de la fila y la devuelve, o 404 si no existe.
     *
     * <p>El lock es lo que convierte el {@code If-Match} en un bloqueo real: sin el es un
     * consultar-y-despues-escribir, dos operaciones simultaneas leen la misma version, las dos la
     * dan por buena, y la segunda escribe encima con la foto que tomo antes de que la primera
     * terminara — dejando ademas escrito un rastro que afirma un cambio que en la fila ya no esta.
     * Con el lock, la segunda espera, relee la version ya confirmada y sale con el 412.
     *
     * <p><b>Se llama ANTES de tocar la entity.</b> El lock se toma releyendo la fila, y releer
     * DESCARTA lo que se le haya cambiado en memoria: quien mute primero y lockee despues pierde
     * su cambio sin ningun error, y termina con un 200, una version nueva y un rastro escrito
     * sobre una fila que no se movio. Es el mismo defecto que este componente existe para cerrar,
     * al reves. Por eso el metodo revienta ruidoso si lo llaman con cambios pendientes.
     *
     * <p>Se relee ADEMAS de lockear porque si la fila ya estaba cargada, un lock a secas la toma
     * pero devuelve la instancia CACHEADA, con las columnas viejas: la version que se compara
     * contra el {@code If-Match} seria la de antes y un ETag caduco pasaria la verificacion.
     *
     * <p>Conviene que sea lo PRIMERO que hace la transaccion, para que el tope de espera rija todo
     * lo que venga despues. Y TIENE que haber una: fuera de una transaccion, {@code SET LOCAL} no
     * hace nada y el lock se suelta al terminar la sentencia que lo toma. No hace falta declararlo
     * con una anotacion porque el requisito ya se hace cumplir solo: la primera linea del metodo
     * desenvuelve la sesion y la segunda ejecuta una consulta nativa, y las dos revientan sin
     * transaccion, antes de intentar tomar el lock.
     *
     * <p>El lock es {@code FOR NO KEY UPDATE} y no {@code FOR UPDATE}: los dos serializan a dos
     * editores entre si, pero el primero NO choca contra el lock de clave que PostgreSQL toma
     * sobre la fila padre al insertar una hija. Con {@code FOR UPDATE}, un endpoint que solo
     * agregue una linea de bitacora o una asignacion quedaria esperando detras de una edicion, con
     * el tope por defecto de su propia conexion (o sea, sin tope), inmovilizando un hilo y una
     * conexion del pool COMPARTIDO con los otros modulos. Es seguro: la edicion no toca el id ni
     * el codigo, que es la unica columna con indice unico, y nada referencia al codigo.
     */
    public Service findByIdForUpdate(long serviceId) {
        // el orden importa: cualquier consulta nativa —incluida la del tope— dispara el volcado
        // automatico de lo que este pendiente, y despues de eso ya no hay nada sucio que detectar
        requireNothingPendingToWrite();
        applyLockTimeout();

        // Desde que el tope esta puesto, CUALQUIER sentencia de la transaccion puede rendirse por
        // lock, no solo la que lo toma: las dos lecturas de abajo tocan la misma tabla y un DDL
        // que la tome (una migracion durante un deploy) las hace esperar igual. Traducirlas es lo
        // que evita que ese conflicto —transitorio, del contrato— salga como error del servidor.
        return runTranslatingLockConflicts(() -> readAndLock(serviceId), serviceId);
    }

    private Service readAndLock(long serviceId) {
        Service service = serviceRepository.findById(serviceId);
        if (service == null) {
            throw OperationsError.SERVICE_NOT_FOUND.toException();
        }
        try {
            entityManager.createNativeQuery(
                    "SELECT id FROM " + SERVICES_TABLE + " WHERE id = ?1 FOR NO KEY UPDATE")
                .setParameter(1, serviceId).getSingleResult();
            entityManager.refresh(service);
        } catch (NoResultException e) {
            // Alguien borro la fila en duro entre la primera lectura y el lock (cirugia manual o
            // el script del cutover): el lock se queda sin filas. Es el mismo caso que el 404 de
            // arriba, no un error del servidor; sin este catch sale como 500. De la relectura en
            // adelante ya no hace falta cubrirlo: con el lock tomado, nadie puede borrar la fila.
            throw OperationsError.SERVICE_NOT_FOUND.toException();
        }
        return service;
    }

    /**
     * Corta si la transaccion trae escrituras pendientes sobre alguna entity. Es la mitad
     * verificable del contrato de arriba: sin esto, el olvido de llamar al lock primero se paga
     * con un dato perdido en produccion en vez de con un test en rojo.
     */
    private void requireNothingPendingToWrite() {
        Session session = entityManager.unwrap(Session.class);
        if (session.isDirty()) {
            // La guarda dispara con CUALQUIER entity pendiente, no solo con el servicio, asi que
            // el mensaje lista lo que la transaccion tiene cargado: sin esa pista, el error no se
            // diagnostica desde el log.
            throw new IllegalStateException(
                "findByIdForUpdate tiene que llamarse ANTES de modificar la entity: al releer la "
                    + "fila para lockearla se descartan los cambios que esten pendientes. "
                    + "Entities cargadas en la transaccion: " + loadedEntityNames(session));
        }
    }

    /** Los tipos que la transaccion tiene cargados, para que el mensaje de arriba sea accionable. */
    private String loadedEntityNames(Session session) {
        Set<String> names = new TreeSet<>();
        for (Object key : session.getStatistics().getEntityKeys()) {
            String entityName = ((EntityKey) key).getEntityName();
            names.add(entityName.substring(entityName.lastIndexOf('.') + 1));
        }
        return names.isEmpty() ? "(ninguna)" : String.join(", ", names);
    }

    /**
     * Corre un bloque que puede chocar contra el lock de la fila: tomarlo, o escribir con el ya
     * tomado. Traduce ese choque al conflicto del contrato.
     *
     * <p>El tope de espera rige TODA la transaccion, no solo la lectura: una escritura posterior
     * tambien puede chocar, y sin traducirla el 500 vuelve por la ventana.
     */
    public <T> T runTranslatingLockConflicts(java.util.function.Supplier<T> action, long serviceId) {
        try {
            return action.get();
        } catch (PersistenceException e) {
            throw asLockConflictOrRethrow(e, serviceId);
        }
    }

    private void applyLockTimeout() {
        entityManager.createNativeQuery(
            "SET LOCAL lock_timeout = '" + requireUsableLockTimeout() + "s'").executeUpdate();
    }

    /**
     * Cuantas esperas por locks que TOMA ESTE MODULO puede acumular una transaccion. El tope de
     * PostgreSQL se aplica POR INTENTO, no por transaccion, asi que el tiempo que una request
     * puede retener su conexion crece con la cantidad de intentos.
     *
     * <p>Hoy el peor caso lo empatan DOS caminos: la asignacion de recursos y la REAPERTURA, que
     * vuelve a mirar conflictos con los recursos que el viaje conservo. Los dos suman la fila del
     * viaje mas los tres recursos (conductor, tracto y carreta). La edicion usa una sola, pero la
     * banda se calcula con el peor caso porque el pool es UNO y lo comparten todos.
     *
     * <p>El presupuesto esta al 100%: cualquier lock adicional —el endpoint de refuerzos es el
     * candidato conocido— obliga a subir esta constante y, con ella, la espera del pool. La
     * validacion de abajo mira la constante contra el pool, NO contra el codigo, asi que ese
     * chequeo hay que hacerlo a mano al agregar una espera.
     *
     * <p><b>NO cuenta los chequeos de integridad referencial.</b> El volcado toca cuatro claves
     * foraneas del viaje y una por cada fila de rastro, y cada uno toma su lock de clave
     * compartida bajo el mismo tope. En la practica no esperan —nadie bloquea esos catalogos
     * desde v2—, y la holgura que queda entre el total contado y la espera del pool es la que los
     * absorbe. Si algun dia hay que contarlos, subir esta constante obliga a subir tambien la
     * espera del pool.
     */
    static final int MAX_LOCK_WAITS_PER_TRANSACTION = 4;

    /**
     * El tope tiene una BANDA valida, no solo un piso.
     *
     * <p>Por abajo: PostgreSQL lee {@code lock_timeout = 0} como "sin tope", que es exactamente la
     * espera infinita que esta propiedad existe para evitar; en cero se desactivaria en silencio.
     *
     * <p>Por arriba: quien espera por un lock retiene su conexion todo ese tiempo, asi que el
     * TOTAL acumulado de las esperas de una transaccion tiene que quedar por debajo de lo que el
     * pool espera para ENTREGAR una conexion. Si no, se invierte el orden de las rendiciones y los
     * otros modulos se quedan sin conexiones antes de que los que esperan se rindan.
     *
     * <p>Se multiplica por el peor caso y no se mide una sola espera: cuando este modulo tenia un
     * unico lock por transaccion las dos cuentas coincidian, y al sumar los locks por recurso la
     * version vieja habria seguido dando por buena una configuracion que ya no lo era.
     */
    int requireUsableLockTimeout() {
        long budget = (long) lockTimeoutSeconds * MAX_LOCK_WAITS_PER_TRANSACTION;
        if (lockTimeoutSeconds <= 0 || budget >= poolAcquisitionTimeout.toSeconds()) {
            throw new IllegalStateException(
                "app.operations.edit-lock-timeout-seconds tiene que ser mayor que cero, y su total "
                    + "acumulado (" + lockTimeoutSeconds + "s x " + MAX_LOCK_WAITS_PER_TRANSACTION
                    + " esperas = " + budget + "s) tiene que quedar por debajo de la espera del pool ("
                    + poolAcquisitionTimeout + "): en cero PostgreSQL desactiva el tope, y por arriba "
                    + "la transaccion se queda con la conexion mas tiempo del que el pool tolera. "
                    + "Valor configurado: " + lockTimeoutSeconds);
        }
        return lockTimeoutSeconds;
    }

    /**
     * Traduce el conflicto de lock al 409 del contrato, y deja pasar cualquier otra cosa.
     *
     * <p>Se decide por el ESTADO que reporta PostgreSQL y no por el tipo de la excepcion, porque
     * el tipo no es fiable: la espera agotada llega como una excepcion de lock pesimista, pero el
     * abrazo mortal llega —verificado— como una de bloqueo OPTIMISTA, que no tiene nada que ver
     * con lo que paso y que un catch por tipo dejaria escapar como 500. El estado lo pone el motor.
     *
     * <p>La transaccion ya quedo abortada cuando se llega aca, asi que este camino no vuelve a
     * tocar la base: solo registra y lanza. El cuerpo del error se arma en memoria.
     */
    RuntimeException asLockConflictOrRethrow(PersistenceException e, long serviceId) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sqlException
                    && LOCK_CONFLICT_SQL_STATES.contains(sqlException.getSQLState())) {
                // El tope rige TODA la transaccion, asi que el conflicto puede haber sido con
                // otra fila (la moneda o el usuario que el volcado referencia). Por eso el log
                // habla de la transaccion y arrastra el mensaje del motor, que nombra la relacion.
                LOG.warnf("Conflicto de lock (%s) en la transaccion que escribe el servicio id=%d "
                        + "con un tope de %ds: %s",
                    sqlException.getSQLState(), serviceId, lockTimeoutSeconds,
                    sqlException.getMessage());
                return OperationsError.SERVICE_LOCKED.toException();
            }
        }
        return e;
    }
}
