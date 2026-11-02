package com.scaramutti.tms.auth;

import com.scaramutti.tms.shared.entity.UserAuditLog;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
 * Cubre la migracion de la autoria y el rastro de las cuentas: las columnas, el valor de las cuentas
 * que ya existen, la tabla con su {@code CHECK}, el bloqueo y lo que toma el archivo entero.
 *
 * <p>Como en la de la version de las sesiones, los casos del archivo deshacen la migracion y la
 * vuelven a correr dentro de una transaccion que termina en {@code rollback}: asi se mide el archivo
 * y no lo que dejo Flyway. Los del bloqueo miran solo esa sentencia del archivo, salvo el que lo
 * corre entero para ver todo lo que toma.
 */
@QuarkusTest
class UserAuditMigrationTest {

    private static final String MIGRATION = "db/migration/V017__public_users_audit.sql";

    @Inject
    DataSource dataSource;

    /** Lo que dejo Flyway. */
    @Test
    void theMigration_isAppliedExactlyOnce() throws SQLException {
        inRollback(connection -> assertEquals(1, intOf(connection,
            "SELECT count(*) FROM flyway_schema_history WHERE version = '017' AND success")));
    }

    /** Las dos firmas, nullables y hacia las cuentas; la marca, obligatoria y con valor por omision. */
    @Test
    void theFile_leavesTheAuthorshipColumnsOfTheAccounts() throws SQLException {
        inFreshMigration(connection -> {
            assertEquals(List.of("created_by|integer|YES|-", "updated_at|timestamp with time zone|NO|CURRENT_TIMESTAMP",
                "updated_by|integer|YES|-"), textsOf(connection,
                "SELECT column_name || '|' || data_type || '|' || is_nullable || '|' || coalesce(column_default, '-')"
                    + " FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'users'"
                    + " AND column_name IN ('created_by', 'updated_by', 'updated_at') ORDER BY column_name"));
            assertEquals(List.of("created_by", "updated_by"), foreignKeysToTheAccounts(connection, "users"));
        });
    }

    /** Las cuentas que ya existen quedan sin autores y con la marca de cambio de su alta. */
    @Test
    void theFile_leavesEveryExistingAccountWithoutAuthors_andChangedWhenItWasCreated() throws SQLException {
        inRollback(connection -> {
            undoTheMigration(connection);
            // Un alta de hace tiempo: con la marca puesta en el momento de migrar, no seria la de su alta.
            execute(connection, "UPDATE public.users SET created_at = TIMESTAMPTZ '2024-01-01 00:00:00+00'"
                + " WHERE id = (SELECT min(id) FROM public.users)");
            int accounts = intOf(connection, "SELECT count(*) FROM public.users");
            assertTrue(accounts > 0, "precondicion: hay cuentas");
            runMigration(connection);

            assertEquals(accounts, intOf(connection, "SELECT count(*) FROM public.users"
                + " WHERE created_by IS NULL AND updated_by IS NULL AND updated_at = created_at"));
        });
    }

    /**
     * El rastro: la cuenta y el autor obligatorios y hacia las cuentas, el tipo y la marca obligatorios,
     * los largos de la tabla de trabajadores y el indice por cuenta.
     */
    @Test
    void theFile_leavesTheTrailTable() throws SQLException {
        inFreshMigration(connection -> {
            assertEquals(List.of("change_type|character varying(30)|NO|-", "changed_by|integer|NO|-",
                "field_label|character varying(100)|YES|-", "field_name|character varying(50)|YES|-",
                "id|bigint|NO|nextval('user_audit_logs_id_seq'::regclass)",
                "logged_at|timestamp with time zone|NO|now()",
                "new_value|text|YES|-", "old_value|text|YES|-", "user_id|integer|NO|-"), textsOf(connection,
                "SELECT column_name || '|' || data_type"
                    + " || coalesce('(' || character_maximum_length || ')', '') || '|' || is_nullable || '|'"
                    + " || coalesce(column_default, '-') FROM information_schema.columns WHERE table_schema = 'public'"
                    + " AND table_name = 'user_audit_logs' ORDER BY column_name"));
            assertEquals(List.of("changed_by", "user_id"), foreignKeysToTheAccounts(connection, "user_audit_logs"));
            assertEquals(List.of("CREATE INDEX idx_user_audit_user ON public.user_audit_logs USING btree (user_id)"),
                textsOf(connection, "SELECT indexdef FROM pg_indexes WHERE schemaname = 'public'"
                    + " AND tablename = 'user_audit_logs' AND indexname <> 'user_audit_logs_pkey'"));
        });
    }

    /**
     * El dominio del tipo son los cinco cambios de cuentas de esta version, y el cambio propio lo
     * escribe con la constante de la entidad: si una de las dos se mueve sola, el INSERT revienta.
     */
    @Test
    void theCheck_allowsTheFiveTypesOfThisVersion_amongThemTheConstantOfTheOwnChange() throws SQLException {
        inFreshMigration(connection -> {
            Set<String> allowed = new TreeSet<>();
            Matcher quoted = Pattern.compile("'([A-Z_]+)'").matcher(textOf(connection,
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = 'chk_user_audit_change'"));
            while (quoted.find()) {
                allowed.add(quoted.group(1));
            }

            assertEquals(Set.of("CREATED", "DEACTIVATED", "REACTIVATED", "PASSWORD_RESET", "PASSWORD_CHANGED"),
                allowed);
            assertTrue(allowed.contains(UserAuditLog.PASSWORD_CHANGED));
        });
    }

    @Test
    void aTypeOutsideTheCheck_isRejected() throws SQLException {
        inFreshMigration(connection -> {
            PSQLException rejected = assertThrows(PSQLException.class, () -> execute(connection,
                "INSERT INTO public.user_audit_logs (user_id, change_type, changed_by)"
                    + " SELECT min(id), 'UNLOCKED', min(id) FROM public.users"));

            assertEquals("23514", rejected.getSQLState());
            assertEquals("chk_user_audit_change", rejected.getServerErrorMessage().getConstraint());
        });
    }

    // ---------- el bloqueo -----------------------------------------------------------

    @Test
    void theLockOfTheFile_takesOnlyUsers() throws IOException {
        assertEquals(List.of("public.users"), tablesLockedBy(firstStatementOf(MIGRATION)));
    }

    /**
     * El bloqueo cubre todo lo que el archivo toma de lo que ya existe: corrido entero, ninguna tabla
     * de la base queda tomada salvo las cuentas y la del rastro, que nadie ve hasta que confirma.
     */
    @Test
    void theWholeFile_takesNoOtherTableThanUsersAndItsOwn() throws SQLException {
        inFreshMigration(connection -> assertEquals(List.of("user_audit_logs", "users"), textsOf(connection,
            "SELECT DISTINCT c.relname FROM pg_locks l JOIN pg_class c ON c.oid = l.relation"
                + " WHERE l.pid = pg_backend_pid() AND c.relkind = 'r' AND c.relnamespace <> 'pg_catalog'::regnamespace"
                + " ORDER BY 1")));
    }

    /**
     * Contra una transaccion que ya leyo {@code users}: la migracion reintenta sin hacer cola, y una
     * lectura nueva de la tabla, como un ingreso, no la espera. Corre la primera sentencia del archivo
     * y termina en rollback.
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
                System.err.println("AVISO: quedo un hilo vivo del caso del bloqueo de la V017");
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

    /** Deshace la migracion y corre el archivo entero, en la transaccion del caso. */
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

    /** Deshace el archivo: la tabla del rastro y las tres columnas de autoria de las cuentas. */
    private void undoTheMigration(Connection connection) throws SQLException {
        execute(connection, "SET LOCAL lock_timeout = '5s'");
        execute(connection, "DROP TABLE public.user_audit_logs");
        execute(connection, "ALTER TABLE public.users DROP COLUMN created_by, DROP COLUMN updated_by,"
            + " DROP COLUMN updated_at");
    }

    private void runMigration(Connection connection) throws SQLException {
        try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(MIGRATION)) {
            assertNotNull(in, "no se encontro " + MIGRATION);
            execute(connection, new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new SQLException(e);
        }
    }

    /** Las columnas de la tabla con clave foranea hacia el id de las cuentas, por nombre. */
    private static List<String> foreignKeysToTheAccounts(Connection connection, String table) throws SQLException {
        return textsOf(connection, "SELECT a.attname FROM pg_constraint k"
            + " JOIN pg_attribute a ON a.attrelid = k.conrelid AND a.attnum = k.conkey[1]"
            + " WHERE k.contype = 'f' AND k.conrelid = 'public." + table + "'::regclass"
            + " AND k.confrelid = 'public.users'::regclass AND cardinality(k.conkey) = 1"
            + " AND k.confkey = ARRAY[(SELECT attnum FROM pg_attribute WHERE attrelid = 'public.users'::regclass"
            + " AND attname = 'id')] ORDER BY 1");
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

    private static List<String> textsOf(Connection connection, String sql) throws SQLException {
        List<String> values = new ArrayList<>();
        try (Statement st = connection.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                values.add(rs.getString(1));
            }
        }
        return values;
    }
}
