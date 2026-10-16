package com.scaramutti.tms.support;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.scaramutti.tms.support.LockWaiters.UNTIL_IT_ARRIVES_MILLIS;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Para los tests de las migraciones que empiezan tomando sus tablas en exclusivo con
 * {@code NOWAIT} y reintentos: leen ese bloque del archivo y miran si la migracion reintenta.
 */
public final class MigrationLocks {

    private MigrationLocks() {
    }

    /** El archivo de la migracion sin sus comentarios de linea. */
    public static String codeOf(String resource) throws IOException {
        try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(resource)) {
            assertNotNull(in, "no se encontro " + resource);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
                .filter(line -> !line.startsWith("--"))
                .collect(Collectors.joining("\n")).strip();
        }
    }

    /** La primera sentencia del archivo, sin sus comentarios: el bloque del bloqueo. */
    public static String firstStatementOf(String resource) throws IOException {
        String code = codeOf(resource);
        assertTrue(code.startsWith("DO $$"),
            "la primera sentencia no es el bloqueo: " + code.lines().findFirst().orElse(""));
        return code.substring(0, code.indexOf("END $$;") + "END $$;".length());
    }

    /** Las tablas que nombra el {@code LOCK TABLE} del bloque, en su orden. */
    public static List<String> tablesLockedBy(String lockBlock) {
        int start = lockBlock.indexOf("LOCK TABLE ");
        int end = lockBlock.indexOf(" IN ACCESS EXCLUSIVE MODE", start);
        assertTrue(start >= 0 && end > start, "el bloque no tiene LOCK TABLE ... IN ACCESS EXCLUSIVE MODE");
        return Arrays.stream(lockBlock.substring(start + "LOCK TABLE ".length(), end).split(","))
            .map(String::strip).toList();
    }

    /**
     * Cuantas veces, en ese lapso, la sesion de la migracion estuvo esperando un bloqueo en cola.
     * Reintentar no es hacer cola: un intento con tope de espera tambien duerme entre intentos.
     */
    public static int lockWaitSamples(Connection observer, int migrationPid, long millis) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        int waiting = 0;
        try (PreparedStatement query = observer.prepareStatement(
                "SELECT count(*) FROM pg_stat_activity WHERE pid = ? AND wait_event_type = 'Lock'")) {
            query.setInt(1, migrationPid);
            while (System.nanoTime() < deadline) {
                try (ResultSet rows = query.executeQuery()) {
                    rows.next();
                    waiting += rows.getInt(1);
                }
                Thread.sleep(5);
            }
        }
        return waiting;
    }

    /** Si la migracion llego a dormir entre dos intentos: probo y alguna tabla estaba tomada. */
    public static boolean awaitRetry(Connection observer, int migrationPid, Future<?> orDone)
            throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(UNTIL_IT_ARRIVES_MILLIS);
        try (PreparedStatement query = observer.prepareStatement(
                "SELECT count(*) FROM pg_stat_activity WHERE pid = ? AND wait_event = 'PgSleep'")) {
            query.setInt(1, migrationPid);
            while (!orDone.isDone() && System.nanoTime() < deadline) {
                try (ResultSet rows = query.executeQuery()) {
                    rows.next();
                    if (rows.getInt(1) == 1) {
                        return true;
                    }
                }
                Thread.sleep(5);
            }
        }
        return false;
    }
}
