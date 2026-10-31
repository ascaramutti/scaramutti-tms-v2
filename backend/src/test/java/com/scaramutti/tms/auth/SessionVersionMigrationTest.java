package com.scaramutti.tms.auth;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.scaramutti.tms.support.LockWaiters.backendPid;
import static com.scaramutti.tms.support.MigrationLocks.awaitRetry;
import static com.scaramutti.tms.support.MigrationLocks.firstStatementOf;
import static com.scaramutti.tms.support.MigrationLocks.lockWaitSamples;
import static com.scaramutti.tms.support.MigrationLocks.tablesLockedBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cubre la migracion de la version de las sesiones: la forma de la columna, el valor de las cuentas
 * que ya existen, su {@code CHECK} y el bloqueo.
 *
 * <p>Los casos de la columna la borran y vuelven a correr el archivo, dentro de una transaccion que
 * termina en {@code rollback}: asi se mide el archivo y no lo que dejo Flyway. Los del bloqueo miran
 * solo esa sentencia del archivo.
 */
@QuarkusTest
class SessionVersionMigrationTest {

    private static final String MIGRATION = "db/migration/V016__public_users_session_version.sql";

    @Inject
    DataSource dataSource;

    /** Lo que dejo Flyway. */
    @Test
    void theMigration_isAppliedExactlyOnce() throws SQLException {
        inRollback(connection -> assertEquals(1, intOf(connection,
            "SELECT count(*) FROM flyway_schema_history WHERE version = '016' AND success")));
    }

    @Test
    void theFile_leavesAnIntegerColumn_notNull_withZeroByDefault() throws SQLException {
        inFreshMigration(connection -> assertEquals("integer|NO|0", textOf(connection,
            "SELECT data_type || '|' || is_nullable || '|' || column_default FROM information_schema.columns"
                + " WHERE table_schema = 'public' AND table_name = 'users' AND column_name = 'session_version'")));
    }

    /** Las cuentas que ya existen quedan en 0, la version de un refresh emitido antes: ninguna queda afuera. */
    @Test
    void theFile_leavesEveryExistingAccountAtZero() throws SQLException {
        inRollback(connection -> {
            int accounts = intOf(connection, "SELECT count(*) FROM public.users");
            assertTrue(accounts > 0, "precondicion: hay cuentas");
            undoTheMigration(connection);
            runMigration(connection);

            assertEquals(accounts, intOf(connection, "SELECT count(*) FROM public.users WHERE session_version = 0"));
        });
    }

    @Test
    void aNegativeVersion_isRejectedByTheCheck() throws SQLException {
        inFreshMigration(connection -> {
            PSQLException rejected = assertThrows(PSQLException.class, () -> execute(connection,
                "UPDATE public.users SET session_version = -1 WHERE id = (SELECT min(id) FROM public.users)"));

            assertEquals("23514", rejected.getSQLState());
            assertEquals("chk_users_session_version", rejected.getServerErrorMessage().getConstraint());
        });
    }

    // ---------- el bloqueo -----------------------------------------------------------

    @Test
    void theLockOfTheFile_takesOnlyUsers() throws IOException {
        assertEquals(List.of("public.users"), tablesLockedBy(firstStatementOf(MIGRATION)));
    }

    /**
     * Contra una transaccion que ya leyo {@code users}: la migracion reintenta sin hacer cola, y una
     * lectura nueva de la tabla, como un ingreso, no la espera. Un exclusivo en la cola la frenaria
     * hasta el final de la migracion. Corre la primera sentencia del archivo y termina en rollback.
     */
    @Test
    void theLockOfTheFile_retriesWithoutQueueing_whileAReaderHoldsUsers() throws Exception {
        String lockBlock = firstStatementOf(MIGRATION);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection reader = dataSource.getConnection();
             Connection migration = dataSource.getConnection();
             Connection observer = dataSource.getConnection();
             Connection newReader = dataSource.getConnection()) {
            reader.setAutoCommit(false);
            migration.setAutoCommit(false);
            newReader.setAutoCommit(false);
            try {
                execute(reader, "SET LOCAL lock_timeout = '3s'");
                execute(reader, "SELECT count(*) FROM public.users");
                // Tope de sentencia y no de bloqueo: el bloque atrapa el fin de un lock_timeout y lo
                // reintenta. Asi, un bloqueo que vuelva a hacer cola hace fallar el caso, no la suite.
                execute(migration, "SET LOCAL statement_timeout = '20s'");
                int migrationPid = backendPid(migration);

                Future<?> locked = executor.submit(() -> {
                    execute(migration, lockBlock);
                    return null;
                });
                assertTrue(awaitRetry(observer, migrationPid, locked),
                    "la migracion tiene que estar reintentando antes de la lectura nueva");
                assertEquals(0, lockWaitSamples(observer, migrationPid, 300),
                    "la migracion espero en la cola en vez de reintentar");

                execute(newReader, "SET LOCAL lock_timeout = '3s'");
                long startNanos = System.nanoTime();
                assertDoesNotThrow(() -> execute(newReader, "SELECT count(*) FROM public.users"));
                long waitedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
                assertTrue(waitedMillis < 1000, "la lectura nueva espero " + waitedMillis + " ms");
                newReader.rollback();
                reader.rollback();
                assertDoesNotThrow(() -> locked.get(10, TimeUnit.SECONDS));
            } finally {
                newReader.rollback();
                reader.rollback();
                migration.rollback();
            }
        } finally {
            executor.shutdownNow();
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                System.err.println("AVISO: quedo un hilo vivo del caso del bloqueo de la V016");
            }
        }
    }

    /** Solo se reintenta el bloqueo no disponible: otro error sale en el acto con su causa. */
    @Test
    void theLockOfTheFile_retriesOnlyTheLockNotAvailable() throws Exception {
        String missingTable = shortLockBlock()
            .replace("LOCK TABLE public.users IN", "LOCK TABLE public.ztest_missing IN");
        try (Connection migration = dataSource.getConnection()) {
            migration.setAutoCommit(false);
            try {
                execute(migration, "SET LOCAL statement_timeout = '20s'");

                PSQLException failed = assertThrows(PSQLException.class, () -> execute(migration, missingTable));
                assertEquals("42P01", failed.getSQLState(), failed.getServerErrorMessage().getMessage());
            } finally {
                migration.rollback();
            }
        }
    }

    /** Si {@code users} sigue tomada, el bloque se rinde con su mensaje en vez de seguir de largo. */
    @Test
    void theLockOfTheFile_givesUpWithItsMessage_whenUsersStaysTaken() throws Exception {
        String lockBlock = firstStatementOf(MIGRATION);
        // 600 intentos con 50 ms entre uno y otro son los 30 s que dice el mensaje.
        assertTrue(lockBlock.contains("FOR attempt IN 1..600 LOOP"), "el tope del archivo cambio");
        assertTrue(lockBlock.contains("PERFORM pg_sleep(0.05);"), "la pausa del archivo cambio");
        try (Connection holder = dataSource.getConnection();
             Connection migration = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            migration.setAutoCommit(false);
            try {
                execute(holder, "SELECT count(*) FROM public.users");
                execute(migration, "SET LOCAL statement_timeout = '20s'");

                PSQLException gaveUp = assertThrows(PSQLException.class, () -> execute(migration, shortLockBlock()));
                assertEquals("P0001", gaveUp.getSQLState());
                assertEquals("no se pudo tomar public.users en 30 s", gaveUp.getServerErrorMessage().getMessage());
            } finally {
                migration.rollback();
                holder.rollback();
            }
        }
    }

    // ---------- fixtures ----------------------------------------------------------

    /** Borra la columna (el CHECK se va con ella) y corre el archivo entero, en la transaccion del caso. */
    private void inFreshMigration(SqlBlock block) throws SQLException {
        inRollback(connection -> {
            undoTheMigration(connection);
            runMigration(connection);
            block.run(connection);
        });
    }

    /** El bloque del bloqueo del archivo con tres intentos en vez de seiscientos. */
    private static String shortLockBlock() throws IOException {
        return firstStatementOf(MIGRATION).replace("FOR attempt IN 1..600 LOOP", "FOR attempt IN 1..3 LOOP");
    }

    private void undoTheMigration(Connection connection) throws SQLException {
        execute(connection, "SET LOCAL lock_timeout = '5s'");
        execute(connection, "ALTER TABLE public.users DROP COLUMN session_version");
    }

    private void runMigration(Connection connection) throws SQLException {
        try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(MIGRATION)) {
            assertNotNull(in, "no se encontro " + MIGRATION);
            execute(connection, new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new SQLException(e);
        }
    }

    private void inRollback(SqlBlock block) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                block.run(connection);
            } finally {
                connection.rollback();
            }
        }
    }

    @FunctionalInterface
    private interface SqlBlock {
        void run(Connection connection) throws SQLException;
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.execute(sql);
        }
    }

    private static int intOf(Connection connection, String sql) throws SQLException {
        try (Statement st = connection.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            assertTrue(rs.next(), "sin resultado: " + sql);
            int value = rs.getInt(1);
            assertFalse(rs.wasNull(), "nulo: " + sql);
            return value;
        }
    }

    private static String textOf(Connection connection, String sql) throws SQLException {
        try (Statement st = connection.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            assertTrue(rs.next(), "sin resultado: " + sql);
            return rs.getString(1);
        }
    }
}
