package com.scaramutti.tms.workers;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.postgresql.util.PSQLException;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.scaramutti.tms.support.LockWaiters.UNTIL_IT_ARRIVES_MILLIS;
import static com.scaramutti.tms.support.LockWaiters.backendPid;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cubre la migracion que borra las dos columnas que repetian el cargo del trabajador, con los
 * disparadores y las funciones que mantenian igual la de la cuenta.
 *
 * <p>El archivo no se puede volver a correr: lo que borra ya no esta. Los casos miran lo que dejo
 * Flyway, salvo los dos del bloqueo, que corren esa sentencia del archivo (el del tope, con tres
 * intentos).
 */
@QuarkusTest
class RoleCopiesDroppedMigrationTest {

    private static final String MIGRATION = "db/migration/V013__public_drop_role_copies.sql";

    @Inject
    DataSource dataSource;

    @Test
    void theMigration_isAppliedExactlyOnce() throws SQLException {
        assertEquals(1, intOf(
            "SELECT count(*) FROM flyway_schema_history WHERE version = '013' AND success"));
    }

    @ParameterizedTest(name = "{0}.{1} ya no existe")
    @CsvSource({"users, role_id", "workers, position"})
    void theCopyOfTheRole_isGone(String table, String column) throws SQLException {
        assertEquals(1, intOf("SELECT count(*) FROM information_schema.tables"
                + " WHERE table_schema = 'public' AND table_name = '" + table + "'"),
            "la tabla tiene que existir para que el cero mida algo");
        assertEquals(0, intOf(
            "SELECT count(*) FROM information_schema.columns WHERE table_schema = 'public'"
                + " AND table_name = '" + table + "' AND column_name = '" + column + "'"));
    }

    @Test
    void theTriggersAndTheirFunctions_areGone() throws SQLException {
        assertEquals(0, intOf("SELECT count(*) FROM pg_trigger WHERE tgname IN "
            + "('users_role_from_worker', 'workers_role_to_account')"));
        assertEquals(0, intOf("SELECT count(*) FROM pg_proc WHERE pronamespace = 'public'::regnamespace"
            + " AND proname IN ('users_role_from_worker', 'workers_role_to_account')"));
    }

    /**
     * El bloqueo del archivo contra una transaccion que ya leyo una de sus tablas y despues lee
     * otra: el ingreso (la cuenta y despues el trabajador con su cargo), la baja (al reves) y el
     * alta (el cargo y despues la cuenta o el trabajador). La migracion reintenta sin hacer cola, la segunda lectura
     * no la espera y nadie se abraza; un bloqueo que espera en la cola se abraza con alguno de estos
     * ordenes.
     *
     * <p>Corre la primera sentencia del archivo, que tiene que ser el bloqueo, y termina en
     * {@code rollback}.
     */
    @ParameterizedTest(name = "lee {0} y despues {1}")
    @CsvSource({"users, workers", "workers, users", "roles, users", "roles, workers", "users, roles",
        "workers, roles"})
    void theLockOfTheFile_retriesWithoutQueueing_againstAReaderOfTwoOfItsTables(
            String first, String second) throws Exception {
        String lockBlock = firstStatementOf(MIGRATION);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection reader = dataSource.getConnection();
             Connection migration = dataSource.getConnection();
             Connection observer = dataSource.getConnection()) {
            reader.setAutoCommit(false);
            migration.setAutoCommit(false);
            execute(reader, "SET LOCAL lock_timeout = '3s'");
            execute(reader, "SELECT count(*) FROM public." + first);
            // Tope de sentencia y no de bloqueo: el bloque atrapa el fin de un lock_timeout y lo
            // reintenta. Asi, un bloqueo que vuelva a hacer cola hace fallar el caso, no la suite.
            execute(migration, "SET LOCAL statement_timeout = '20s'");
            int migrationPid = backendPid(migration);

            Future<?> locked = executor.submit(() -> {
                execute(migration, lockBlock);
                return null;
            });
            assertTrue(awaitRetry(observer, migrationPid, locked),
                "la migracion tiene que estar reintentando antes de la segunda lectura");

            long startNanos = System.nanoTime();
            assertDoesNotThrow(() -> execute(reader, "SELECT count(*) FROM public." + second));
            long waitedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
            assertTrue(waitedMillis < 1000, "la segunda lectura espero " + waitedMillis + " ms");
            reader.rollback();
            assertDoesNotThrow(() -> locked.get(10, TimeUnit.SECONDS));
            migration.rollback();
        } finally {
            executor.shutdownNow();
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                System.err.println("AVISO: quedo un hilo vivo del caso del bloqueo de la V013");
            }
        }
    }

    /**
     * El tope: si una tabla sigue tomada, el bloque se rinde con su mensaje en vez de seguir de largo
     * y hacer cola en la sentencia siguiente. Corre el bloque del archivo con tres intentos en vez
     * de seiscientos, contra una sesion que retiene {@code workers}.
     */
    @Test
    void theLockOfTheFile_givesUpWithItsMessage_whenATableStaysTaken() throws Exception {
        String lockBlock = firstStatementOf(MIGRATION);
        // 600 intentos con 50 ms entre uno y otro son los 30 s que dice el mensaje; y solo se
        // reintenta el bloqueo no disponible: cualquier otro error sale en el acto con su causa.
        assertTrue(lockBlock.contains("FOR attempt IN 1..600 LOOP"), "el tope del archivo cambio");
        assertTrue(lockBlock.contains("PERFORM pg_sleep(0.05);"), "la pausa del archivo cambio");
        assertTrue(lockBlock.contains("en 30 s"), "el mensaje ya no dice el tope");
        assertTrue(lockBlock.contains("EXCEPTION WHEN lock_not_available THEN"),
            "el bloque reintenta otros errores");
        String shortBlock = lockBlock.replace("FOR attempt IN 1..600 LOOP", "FOR attempt IN 1..3 LOOP");
        try (Connection holder = dataSource.getConnection();
             Connection migration = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            migration.setAutoCommit(false);
            execute(holder, "SELECT count(*) FROM public.workers");
            execute(migration, "SET LOCAL statement_timeout = '20s'");

            PSQLException gaveUp = assertThrows(PSQLException.class, () -> execute(migration, shortBlock));
            assertEquals("P0001", gaveUp.getSQLState());
            assertTrue(gaveUp.getServerErrorMessage().getMessage().startsWith("no se pudieron tomar"),
                gaveUp.getServerErrorMessage().getMessage());
            migration.rollback();
            holder.rollback();
        }
    }

    /** Si la migracion llego a dormir entre dos intentos: probo y alguna tabla estaba tomada. */
    private boolean awaitRetry(Connection observer, int migrationPid, Future<?> orDone)
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

    /** La primera sentencia del archivo, sin sus comentarios: el bloque del bloqueo. */
    private String firstStatementOf(String resource) throws IOException {
        try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(resource)) {
            assertNotNull(in, "no se encontro " + resource);
            String code = new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
                .filter(line -> !line.startsWith("--"))
                .collect(java.util.stream.Collectors.joining("\n")).strip();
            assertTrue(code.startsWith("DO $$"),
                "la primera sentencia no es el bloqueo: " + code.lines().findFirst().orElse(""));
            return code.substring(0, code.indexOf("END $$;") + "END $$;".length());
        }
    }

    private int intOf(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement st = connection.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (PreparedStatement st = connection.prepareStatement(sql)) {
            st.execute();
        }
    }
}
