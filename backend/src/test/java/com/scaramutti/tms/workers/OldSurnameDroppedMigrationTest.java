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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.scaramutti.tms.support.LockWaiters.backendPid;
import static com.scaramutti.tms.support.MigrationLocks.awaitRetry;
import static com.scaramutti.tms.support.MigrationLocks.codeOf;
import static com.scaramutti.tms.support.MigrationLocks.firstStatementOf;
import static com.scaramutti.tms.support.MigrationLocks.lockWaitSamples;
import static com.scaramutti.tms.support.MigrationLocks.tablesLockedBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * El archivo no se puede volver a correr tal cual: lo que borra ya no esta. Los casos miran lo que
 * dejo Flyway, salvo los del bloqueo, que miran esa sentencia del archivo, y los que vuelven a poner lo
 * que el archivo borra y lo corren entero. Los que escriben terminan en {@code rollback}.
 */
@QuarkusTest
class OldSurnameDroppedMigrationTest {

    private static final String MIGRATION = "db/migration/V019__public_drop_workers_last_name.sql";
    private static final String SURNAMES_MIGRATION = "db/migration/V015__public_workers_surnames.sql";

    @Inject
    DataSource dataSource;

    @Test
    void theMigration_isAppliedExactlyOnce() throws SQLException {
        inRollback(connection -> assertEquals(1, intOf(connection,
            "SELECT count(*) FROM flyway_schema_history WHERE version = '019' AND success")));
    }

    @Test
    void theOldColumn_isGone() throws SQLException {
        inRollback(connection -> {
            assertEquals(1, intOf(connection, "SELECT count(*) FROM information_schema.tables"
                    + " WHERE table_schema = 'public' AND table_name = 'workers'"),
                "la tabla tiene que existir para que el cero mida algo");
            assertEquals(0, intOf(connection, "SELECT count(*) FROM information_schema.columns"
                + " WHERE table_schema = 'public' AND table_name = 'workers' AND column_name = 'last_name'"));
        });
    }

    @Test
    void theTriggerAndItsFunction_areGone() throws SQLException {
        inRollback(connection -> {
            assertEquals(0, intOf(connection, "SELECT count(*) FROM pg_trigger WHERE tgname = 'workers_surnames_sync'"));
            assertEquals(0, intOf(connection, "SELECT count(*) FROM pg_proc"
                + " WHERE pronamespace = 'public'::regnamespace AND proname = 'workers_surnames_sync'"));
        });
    }

    /** Una que quedara pasaria la migracion y fallaria en el primer llamado. */
    @Test
    void noFunction_namesTheOldColumn() throws SQLException {
        inRollback(connection -> assertEquals(List.of(), textsOf(connection,
            "SELECT p.oid::regprocedure::text FROM pg_proc p"
                + " WHERE p.pronamespace NOT IN ('pg_catalog'::regnamespace, 'information_schema'::regnamespace)"
                + " AND p.prosrc ~ '\\mlast_name\\M'")));
    }

    @ParameterizedTest(name = "{0}: varchar({1}), nula {2}")
    @CsvSource({"paternal_last_name, 100, NO", "maternal_last_name, 100, YES"})
    void theSurnameParts_keepTheirLengthAndNullability(String column, int length, String nullable)
            throws SQLException {
        inRollback(connection -> assertEquals(List.of(length + "|" + nullable), textsOf(connection,
            "SELECT character_maximum_length || '|' || is_nullable FROM information_schema.columns"
                + " WHERE table_schema = 'public' AND table_name = 'workers' AND column_name = '" + column + "'")));
    }

    @Test
    void theCheckOfTheMaternal_stays() throws SQLException {
        inRollback(connection -> assertEquals(List.of("true|CHECK ((btrim((maternal_last_name)::text) <> ''::text))"),
            textsOf(connection, "SELECT convalidated || '|' || pg_get_constraintdef(oid) FROM pg_constraint"
                + " WHERE conrelid = 'public.workers'::regclass AND conname = 'chk_workers_maternal_last_name_not_blank'")));
    }

    @Test
    void theParts_areWrittenAsTheyCome_onInsertAndOnUpdate() throws SQLException {
        inRollback(connection -> {
            int workerId = intOf(connection, "INSERT INTO public.workers"
                + " (first_name, paternal_last_name, maternal_last_name, document_type_id, document_number, role_id,"
                + " hire_date) VALUES ('ZTEST', 'Pérez', 'Huamán', (SELECT min(id) FROM public.document_types),"
                + " 'ZTESTSUR19', (SELECT id FROM public.roles WHERE name = 'operator'), DATE '2024-01-01')"
                + " RETURNING id");
            assertParts(connection, workerId, "Pérez", "Huamán");

            execute(connection, "UPDATE public.workers SET maternal_last_name = NULL WHERE id = " + workerId);
            assertParts(connection, workerId, "Pérez", null);

            execute(connection, "UPDATE public.workers SET maternal_last_name = 'Vega' WHERE id = " + workerId);
            assertParts(connection, workerId, "Pérez", "Vega");
        });
    }

    @Test
    void theFile_dropsTheThree_andLeavesTheRowsAsTheyWere() throws SQLException {
        inRollback(connection -> {
            execute(connection, "INSERT INTO public.workers"
                + " (first_name, paternal_last_name, maternal_last_name, document_type_id, document_number, role_id,"
                + " hire_date) VALUES ('ZTEST', 'De la Cruz', 'Huamán', (SELECT min(id) FROM public.document_types),"
                + " 'ZTESTSUR20', (SELECT id FROM public.roles WHERE name = 'operator'), DATE '2024-01-01')");
            String rows = "SELECT count(*) FILTER (WHERE maternal_last_name IS NOT NULL) || '|'"
                + " || md5(string_agg(to_jsonb(w)::text, ',' ORDER BY id)) FROM public.workers w";
            String before = textsOf(connection, rows).get(0);
            putBackWhatTheFileDrops(connection, "");

            runMigration(connection);

            assertEquals(0, intOf(connection, "SELECT count(*) FROM information_schema.columns"
                + " WHERE table_schema = 'public' AND table_name = 'workers' AND column_name = 'last_name'"));
            assertEquals(0, intOf(connection, "SELECT count(*) FROM pg_trigger WHERE tgname = 'workers_surnames_sync'"));
            assertEquals(0, intOf(connection, "SELECT count(*) FROM pg_proc"
                + " WHERE pronamespace = 'public'::regnamespace AND proname = 'workers_surnames_sync'"));
            assertEquals(before, textsOf(connection, rows).get(0));
        });
    }

    /** Sin {@code IF EXISTS}: si falta uno de los tres, el archivo aborta en vez de seguir de largo. */
    @ParameterizedTest(name = "sin {0}: {1}")
    @CsvSource({"trigger, 42704", "function, 42883", "column, 42703"})
    void aMissingObject_stopsTheFile(String missing, String sqlState) throws SQLException {
        inRollback(connection -> {
            putBackWhatTheFileDrops(connection, missing);

            PSQLException stopped = assertThrows(PSQLException.class, () -> runMigration(connection));
            assertEquals(sqlState, stopped.getSQLState(), stopped.getServerErrorMessage().getMessage());
        });
    }

    /** Sin {@code CASCADE}: una vista sobre la columna frena el borrado en vez de irse con ella. */
    @Test
    void aViewOverTheOldColumn_stopsTheFile() throws SQLException {
        inRollback(connection -> {
            putBackWhatTheFileDrops(connection, "");
            execute(connection, "CREATE VIEW public.ztest_old_surnames AS SELECT last_name FROM public.workers");

            PSQLException stopped = assertThrows(PSQLException.class, () -> runMigration(connection));
            assertEquals("2BP01", stopped.getSQLState());
            assertTrue(stopped.getServerErrorMessage().getMessage().contains("last_name"),
                stopped.getServerErrorMessage().getMessage());
        });
    }

    // ---------- el bloqueo -----------------------------------------------------------

    @Test
    void theLockOfTheFile_takesOnlyWorkers() throws IOException {
        assertEquals(List.of("public.workers"), tablesLockedBy(firstStatementOf(MIGRATION)));
    }

    /**
     * El bloqueo del archivo contra una transaccion que ya leyo {@code workers}: la migracion
     * reintenta sin hacer cola, y una lectura nueva de la tabla, como la carga de una cuenta, no la
     * espera. Un exclusivo en la cola la frenaria hasta el final de la migracion.
     *
     * <p>Corre la primera sentencia del archivo, que tiene que ser el bloqueo, y termina en
     * {@code rollback}.
     */
    @Test
    void theLockOfTheFile_retriesWithoutQueueing_whileAReaderHoldsWorkers() throws Exception {
        String lockBlock = firstStatementOf(MIGRATION);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection reader = dataSource.getConnection();
             Connection migration = dataSource.getConnection();
             Connection observer = dataSource.getConnection();
             Connection newReader = dataSource.getConnection()) {
            reader.setAutoCommit(false);
            migration.setAutoCommit(false);
            newReader.setAutoCommit(false);
            execute(reader, "SET LOCAL lock_timeout = '3s'");
            execute(reader, "SELECT count(*) FROM public.workers");
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
            assertDoesNotThrow(() -> execute(newReader, "SELECT count(*) FROM public.workers"));
            long waitedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
            assertTrue(waitedMillis < 1000, "la lectura nueva espero " + waitedMillis + " ms");
            newReader.rollback();
            reader.rollback();
            assertDoesNotThrow(() -> locked.get(10, TimeUnit.SECONDS));
            migration.rollback();
        } finally {
            executor.shutdownNow();
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                System.err.println("AVISO: quedo un hilo vivo del caso del bloqueo de la V019");
            }
        }
    }

    /**
     * Solo se reintenta el bloqueo no disponible: cualquier otro error sale en el acto con su causa.
     * Corre el bloque del archivo con tres intentos contra una tabla que no existe.
     */
    @Test
    void theLockOfTheFile_retriesOnlyTheLockNotAvailable() throws Exception {
        String missingTable = shortLockBlock()
            .replace("LOCK TABLE public.workers IN", "LOCK TABLE public.ztest_missing IN");
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

    /**
     * El tope: si {@code workers} sigue tomada, el bloque se rinde con su mensaje en vez de seguir de
     * largo y hacer cola en la sentencia siguiente. Corre el bloque del archivo con tres intentos en
     * vez de seiscientos.
     */
    @Test
    void theLockOfTheFile_givesUpWithItsMessage_whenWorkersStaysTaken() throws Exception {
        String lockBlock = firstStatementOf(MIGRATION);
        // 600 intentos con 50 ms entre uno y otro son los 30 s que dice el mensaje.
        assertTrue(lockBlock.contains("FOR attempt IN 1..600 LOOP"), "el tope del archivo cambio");
        assertTrue(lockBlock.contains("PERFORM pg_sleep(0.05);"), "la pausa del archivo cambio");
        assertTrue(lockBlock.contains("en 30 s"), "el mensaje ya no dice el tope");
        String shortBlock = shortLockBlock();
        try (Connection holder = dataSource.getConnection();
             Connection migration = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            migration.setAutoCommit(false);
            execute(holder, "SELECT count(*) FROM public.workers");
            execute(migration, "SET LOCAL statement_timeout = '20s'");

            PSQLException gaveUp = assertThrows(PSQLException.class, () -> execute(migration, shortBlock));
            assertEquals("P0001", gaveUp.getSQLState());
            assertEquals("no se pudo tomar public.workers en 30 s", gaveUp.getServerErrorMessage().getMessage());
            migration.rollback();
            holder.rollback();
        }
    }

    // ---------- fixtures ----------------------------------------------------------

    /**
     * Vuelve a poner, dentro de la transaccion del caso, lo que el archivo borra como lo dejo la
     * migracion que lo creo: la columna con la composicion de las partes y la funcion de su archivo. Asi
     * un dano a las filas que pase por la columna o por el disparador se ve. Falta el que se nombre:
     * {@code trigger}, {@code function} (el disparador llama entonces a otra) o {@code column}.
     */
    private static void putBackWhatTheFileDrops(Connection connection, String missing) throws SQLException {
        execute(connection, "SET LOCAL lock_timeout = '5s'");
        if (!missing.equals("column")) {
            execute(connection, "ALTER TABLE public.workers ADD COLUMN last_name VARCHAR(201)");
            execute(connection, "UPDATE public.workers SET last_name = concat_ws(' ', paternal_last_name, maternal_last_name)");
            execute(connection, "ALTER TABLE public.workers ALTER COLUMN last_name SET NOT NULL");
        }
        String function = missing.equals("function") ? "ztest_surnames_sync" : "workers_surnames_sync";
        execute(connection, syncFunctionOfTheSurnamesMigration()
            .replace("public.workers_surnames_sync()", "public." + function + "()"));
        if (!missing.equals("trigger")) {
            execute(connection, "CREATE TRIGGER workers_surnames_sync BEFORE INSERT OR UPDATE ON public.workers"
                + " FOR EACH ROW EXECUTE FUNCTION public." + function + "()");
        }
    }

    private static String syncFunctionOfTheSurnamesMigration() throws SQLException {
        try {
            String code = codeOf(SURNAMES_MIGRATION);
            int start = code.indexOf("CREATE FUNCTION public.workers_surnames_sync()");
            int end = code.indexOf("END $$;", start);
            assertTrue(start >= 0 && end > start, "no se encontro la funcion en " + SURNAMES_MIGRATION);
            return code.substring(start, end + "END $$".length());
        } catch (IOException e) {
            throw new SQLException(e);
        }
    }

    private static String shortLockBlock() throws IOException {
        return firstStatementOf(MIGRATION).replace("FOR attempt IN 1..600 LOOP", "FOR attempt IN 1..3 LOOP");
    }

    private void runMigration(Connection connection) throws SQLException {
        try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(MIGRATION)) {
            assertNotNull(in, "no se encontro " + MIGRATION);
            execute(connection, new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new SQLException(e);
        }
    }

    private void assertParts(Connection connection, int workerId, String paternal, String maternal)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT paternal_last_name, maternal_last_name FROM public.workers WHERE id = ?")) {
            ps.setInt(1, workerId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "no esta el trabajador " + workerId);
                assertEquals(List.of(paternal, String.valueOf(maternal)),
                    List.of(rs.getString(1), String.valueOf(rs.getString(2))), "paterno y materno");
            }
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
            return rs.getInt(1);
        }
    }

    private static List<String> textsOf(Connection connection, String sql) throws SQLException {
        try (Statement st = connection.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            List<String> texts = new ArrayList<>();
            while (rs.next()) {
                texts.add(rs.getString(1));
            }
            return texts;
        }
    }
}
