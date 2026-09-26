package com.scaramutti.tms.support;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Sondeos para los tests que fuerzan una coincidencia: una conexion sostiene filas y el test mira
 * quien quedo esperando detras antes de soltarla. Solo cuentan las esperas ENCADENADAS a esa
 * conexion: la base es compartida y una espera ajena no puede dar por cumplido el sondeo.
 */
public final class LockWaiters {

    private LockWaiters() {
    }

    public static int backendPid(Connection connection) throws Exception {
        try (PreparedStatement query = connection.prepareStatement("SELECT pg_backend_pid()");
             ResultSet rows = query.executeQuery()) {
            rows.next();
            return rows.getInt(1);
        }
    }

    /**
     * Cuantas sesiones esperan DETRAS de la conexion que sostiene: a ella, o a alguien que la espera
     * (el segundo en la cola de una fila espera al primero, no al que la sostiene).
     */
    public static int waitersBehind(Connection observer, int holderPid) throws Exception {
        try (PreparedStatement query = observer.prepareStatement(
                "SELECT count(*) FROM pg_stat_activity a WHERE ? = ANY(pg_blocking_pids(a.pid)) OR EXISTS "
                    + "(SELECT 1 FROM unnest(pg_blocking_pids(a.pid)) b WHERE ? = ANY(pg_blocking_pids(b)))")) {
            query.setInt(1, holderPid);
            query.setInt(2, holderPid);
            try (ResultSet rows = query.executeQuery()) {
                rows.next();
                return rows.getInt(1);
            }
        }
    }

    /**
     * Plazo para ver LLEGAR al primer pedido: largo, porque su tope de espera recien corre cuando
     * empieza a esperar, y en frio el primer pedido de una clase tarda segundos en llegar.
     */
    public static final long UNTIL_IT_ARRIVES_MILLIS = 10_000;

    /** Plazo para ver al siguiente mientras otro YA espera: por debajo del tope del perfil de test (600ms). */
    public static final long WHILE_ANOTHER_WAITS_MILLIS = 300;

    /** Espera a ver esas sesiones encadenadas; corta si el pedido ya termino o se cumple el plazo. */
    public static boolean awaitWaiters(Connection observer, int holderPid, int waiters, Future<?> orDone,
            long deadlineMillis) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(deadlineMillis);
        while (!orDone.isDone() && System.nanoTime() < deadline) {
            if (waitersBehind(observer, holderPid) >= waiters) {
                return true;
            }
            Thread.sleep(5);
        }
        return false;
    }
}
