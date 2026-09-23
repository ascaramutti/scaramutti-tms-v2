package com.scaramutti.tms.workers.service;

import org.junit.jupiter.api.Test;

import com.scaramutti.tms.shared.exception.ApiException;
import jakarta.persistence.OptimisticLockException;
import jakarta.persistence.PersistenceException;

import java.sql.SQLException;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * La BANDA valida del tope de espera del bloqueo.
 *
 * <p>No es un piso, son dos limites, y los dos tienen un modo de falla distinto. Por abajo, cero
 * significa "sin tope" para el motor: se desactivaria en silencio, que es exactamente la espera
 * infinita que la propiedad existe para evitar. Por arriba, quien espera retiene su conexion todo
 * ese tiempo, asi que el acumulado de una transaccion tiene que quedar debajo de lo que el pool
 * espera para entregar una: al reves, los otros modulos se quedan sin conexiones antes de que los
 * que esperan se rindan.
 *
 * <p>Se mide sin contenedor porque es aritmetica sobre dos propiedades.
 */
class WorkerRowLockTest {

    private WorkerRowLock lockWith(int timeoutMillis, int poolSeconds) {
        WorkerRowLock lock = new WorkerRowLock();
        lock.lockTimeoutMillis = timeoutMillis;
        lock.poolAcquisitionTimeout = Duration.ofSeconds(poolSeconds);
        return lock;
    }

    /** El valor que se mergea: 6 x 700ms = 4,2s, con holgura bajo los 5s del pool. */
    @Test
    void requireUsableLockTimeout_acceptsTheConfiguredValue() {
        assertEquals(700, lockWith(700, 5).requireUsableLockTimeout());
    }

    /**
     * El presupuesto multiplica por las esperas REALES, y por eso un valor que parece chico puede
     * no entrar: con seis esperas, 900ms ya se comen 5,4s de los 5s que el pool tolera. Este es el
     * caso que separa la cuenta buena de la mala — con las cuatro esperas que este modulo conto
     * antes, 900ms daban 3,6s y el guarda lo aprobaba.
     */
    @Test
    void requireUsableLockTimeout_rejectsAValueThatOnlyFitsIfTheWaitsAreMiscounted() {
        assertThrows(IllegalStateException.class, () -> lockWith(900, 5).requireUsableLockTimeout());
    }

    /**
     * Y el valor que este modulo tenia antes de recontar: un segundo entero. Con la cuenta vieja
     * de cuatro esperas entraba con holgura; con la de seis no entra, y por eso el tope tuvo que
     * bajar de unidad. Si alguien vuelve a poner 1000, la aplicacion no arranca.
     */
    @Test
    void requireUsableLockTimeout_rejectsTheValueThatTheOldMiscountAccepted() {
        assertThrows(IllegalStateException.class, () -> lockWith(1000, 5).requireUsableLockTimeout());
    }

    /** Cero no es "sin limite": es el tope apagado, y el motor lo lee asi. */
    @Test
    void requireUsableLockTimeout_rejectsZero() {
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
            () -> lockWith(0, 5).requireUsableLockTimeout());
        assertTrue(thrown.getMessage().contains("mayor que cero"));
    }

    @Test
    void requireUsableLockTimeout_rejectsANegativeValue() {
        assertThrows(IllegalStateException.class, () -> lockWith(-1, 5).requireUsableLockTimeout());
    }

    /**
     * IGUALAR la espera del pool ya invierte el orden de las rendiciones, asi que el limite se
     * rechaza tambien cuando da exacto. Se elige un techo divisible por las esperas (4,8s / 6 =
     * 800ms) para que el caso mida la igualdad y no un redondeo: con 5s el cociente entero cae 2ms
     * por debajo y el caso pasaria por el motivo equivocado.
     */
    @Test
    void requireUsableLockTimeout_rejectsAValueWhoseBudgetExactlyReachesThePoolWait() {
        WorkerRowLock lock = new WorkerRowLock();
        lock.lockTimeoutMillis = 4800 / WorkerRowLock.MAX_LOCK_WAITS_PER_TRANSACTION;
        lock.poolAcquisitionTimeout = Duration.ofMillis(4800);
        assertThrows(IllegalStateException.class, lock::requireUsableLockTimeout);
    }

    /** Y un milisegundo por debajo del techo entra: el limite es el techo, no un margen inventado. */
    @Test
    void requireUsableLockTimeout_acceptsABudgetOneMillisecondBelowThePoolWait() {
        WorkerRowLock lock = new WorkerRowLock();
        lock.lockTimeoutMillis = 4800 / WorkerRowLock.MAX_LOCK_WAITS_PER_TRANSACTION;
        lock.poolAcquisitionTimeout = Duration.ofMillis(4801);
        assertEquals(800, lock.requireUsableLockTimeout());
    }

    @Test
    void requireUsableLockTimeout_rejectsAValueAboveThePoolWait() {
        assertThrows(IllegalStateException.class, () -> lockWith(6000, 5).requireUsableLockTimeout());
    }

    /**
     * El techo del pool se compara en MILISEGUNDOS: redondearlo a segundos perdia la fraccion que
     * decide si el presupuesto entra. Con un pool de 4,5s, 700ms x 6 = 4,2s entra; truncando el
     * techo a 4s, no entraria — y al reves, un truncado hacia arriba aprobaria lo que no cabe.
     */
    @Test
    void requireUsableLockTimeout_comparesAgainstThePoolWaitWithoutTruncatingItToSeconds() {
        WorkerRowLock lock = new WorkerRowLock();
        lock.lockTimeoutMillis = 700;
        lock.poolAcquisitionTimeout = Duration.ofMillis(4500);
        assertEquals(700, lock.requireUsableLockTimeout());
    }

    /**
     * El presupuesto se multiplica por la cantidad de esperas que este modulo puede acumular en una
     * transaccion. Son SEIS, y el numero no es la cantidad de sentencias: el tope del motor rige
     * por INTENTO de lock, y una sentencia que cambia una columna con indice unico gasta dos, la
     * tupla y el indice. El dia que se sume una septima, la misma configuracion deja de servir y
     * este caso es el que lo dice.
     */
    @Test
    void theBudget_countsTheWaitsThisModuleCanAccumulate() {
        assertEquals(6, WorkerRowLock.MAX_LOCK_WAITS_PER_TRANSACTION,
            "la fila al tomarla; el documento: tupla e indice unico; la licencia: tupla e indice "
                + "unico; y la del usuario si cambia el cargo, que no toca columna unica");
    }

    /**
     * LA GUARDA TAMBIEN CORRE AL ARRANCAR. Sin esto, un tope invalido que llegara por variable de
     * entorno levantaba la aplicacion limpia y cada edicion fallaba con 500.
     *
     * <p>Se mide sin levantar la aplicacion porque levantarla con un valor malo es justamente lo
     * que ya no se puede: la aplicacion no arranca. Son dos mitades, y hacen falta las dos: que el
     * metodo aplique la guarda, y que este CABLEADO al evento de arranque. Un metodo correcto que
     * nadie llama es la misma aplicacion arriba fallando cada edicion.
     */
    @Test
    void validateLockTimeoutOnStartup_rejectsAValueThatBustsTheBudget() {
        assertThrows(IllegalStateException.class,
            () -> lockWith(900, 5).validateLockTimeoutOnStartup(null));
        assertDoesNotThrow(() -> lockWith(700, 5).validateLockTimeoutOnStartup(null));
    }

    @Test
    void validateLockTimeoutOnStartup_isWiredToTheStartupEvent() throws Exception {
        var metodo = WorkerRowLock.class.getDeclaredMethod(
            "validateLockTimeoutOnStartup", io.quarkus.runtime.StartupEvent.class);
        assertTrue(metodo.getParameters()[0].isAnnotationPresent(jakarta.enterprise.event.Observes.class),
            "sin @Observes el metodo existe y nadie lo llama al arrancar");
    }

    // ---------- la traduccion del choque ----------

    /** El choque como llega de verdad: envuelto dos veces, con el estado del motor al fondo. */
    private PersistenceException wrapping(String sqlState) {
        return new PersistenceException("fallo de base",
            new IllegalStateException("envoltorio de Hibernate",
                new SQLException("mensaje del motor", sqlState)));
    }

    /** La espera agotada: el caso que produce el tope. */
    @Test
    void asLockConflictOrRethrow_withLockTimeout_translatesToTheBusinessConflict() {
        RuntimeException translated = lockWith(1, 5).asLockConflictOrRethrow(wrapping("55P03"), 42);

        assertInstanceOf(ApiException.class, translated);
        assertEquals("WRK-013", ((ApiException) translated).code());
    }

    /**
     * El abrazo mortal, por el mismo codigo: tambien es "no pude tomar la fila" y tambien se
     * resuelve reintentando.
     *
     * <p>Se decide por el ESTADO del motor y NO por el tipo de la excepcion, y este caso es el
     * unico que lo mide: Hibernate envuelve el abrazo mortal en una excepcion de bloqueo
     * OPTIMISTA, que no describe nada de lo que paso, asi que un bloque por tipo lo dejaria
     * escapar como error del servidor.
     */
    @Test
    void asLockConflictOrRethrow_withDeadlock_translatesToo() {
        PersistenceException misleadingType = new OptimisticLockException(
            new SQLException("deadlock detected", "40P01"));

        RuntimeException translated = lockWith(1, 5).asLockConflictOrRethrow(misleadingType, 42);

        assertInstanceOf(ApiException.class, translated);
        assertEquals("WRK-013", ((ApiException) translated).code());
    }

    /** El fallo de serializacion, el tercero del conjunto. */
    @Test
    void asLockConflictOrRethrow_withSerializationFailure_translatesToo() {
        RuntimeException translated = lockWith(1, 5).asLockConflictOrRethrow(wrapping("40001"), 42);

        assertEquals("WRK-013", ((ApiException) translated).code());
    }

    /**
     * Cualquier otra falla se propaga TAL CUAL. Es la mitad que impide que este traductor
     * convierta todo error de base en un conflicto reintentable, que seria mucho peor que no
     * traducir nada.
     */
    @Test
    void asLockConflictOrRethrow_withAnotherFailure_propagatesItUntouched() {
        PersistenceException original = wrapping("23505");

        assertSame(original, lockWith(1, 5).asLockConflictOrRethrow(original, 42));
    }

    /** Y una sin estado del motor tampoco se traduce: no hay nada que la identifique. */
    @Test
    void asLockConflictOrRethrow_withoutASqlState_propagatesIt() {
        PersistenceException original = new PersistenceException("sin causa de base");

        assertSame(original, lockWith(1, 5).asLockConflictOrRethrow(original, 42));
    }

}
