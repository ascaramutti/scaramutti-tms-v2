package com.scaramutti.tms.workers;

import com.scaramutti.tms.shared.repository.WorkerRepository;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
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
import java.util.Arrays;
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
 * Cubre la migracion que separa el apellido paterno del materno: la copia de {@code last_name}, la
 * forma de las tres columnas, el disparador que las mantiene de acuerdo y el bloqueo.
 *
 * <p>Casi todos los casos deshacen la migracion y vuelven a correr el archivo, dentro de una
 * transaccion que termina en {@code rollback}: asi se mide el archivo y no lo que dejo Flyway. Los
 * que miran lo que dejo Flyway lo dicen, y los del bloqueo miran solo esa sentencia del archivo.
 */
@QuarkusTest
class WorkerSurnamesMigrationTest {

    private static final String MIGRATION = "db/migration/V015__public_workers_surnames.sql";
    /** En {@code pg_trigger.tgtype}: por fila (1), antes (2), en el alta (4) y en el UPDATE (16). */
    private static final int ROW_BEFORE_INSERT_OR_UPDATE = 1 | 2 | 4 | 16;
    private static final char NO_BREAK_SPACE = 0xA0;

    @Inject
    DataSource dataSource;

    /** Lo que dejo Flyway. */
    @Test
    void theMigration_isAppliedExactlyOnce() throws SQLException {
        inRollback(connection -> assertEquals(1, intOf(connection,
            "SELECT count(*) FROM flyway_schema_history WHERE version = '015' AND success")));
    }

    /**
     * Lo que dejo Flyway: el disparador corre antes de escribir, en el alta y en todo UPDATE, sin
     * lista de columnas ni condicion, y encendido. Con una lista, el UPDATE que no la nombre no
     * recompone nada.
     */
    @Test
    void theTrigger_firesBeforeEveryInsertAndUpdate_withoutColumnsOrCondition() throws SQLException {
        inRollback(connection -> assertEquals(1, intOf(connection,
            "SELECT count(*) FROM pg_trigger WHERE tgrelid = 'public.workers'::regclass"
                + " AND tgname = 'workers_surnames_sync'"
                + " AND tgfoid = 'public.workers_surnames_sync()'::regprocedure"
                + " AND tgtype = " + ROW_BEFORE_INSERT_OR_UPDATE
                + " AND tgattr::text = '' AND tgqual IS NULL AND tgenabled = 'O'")));
    }

    @ParameterizedTest(name = "{0}: varchar({1}), nula {2}")
    @CsvSource({"paternal_last_name, 100, NO", "maternal_last_name, 100, YES", "last_name, 201, NO"})
    void theFile_leavesEachSurnameColumnWithItsLengthAndNullability(String column, int length, String nullable)
            throws SQLException {
        inFreshMigration(connection -> assertEquals("character varying|" + length + "|" + nullable,
            textOf(connection, "SELECT data_type || '|' || character_maximum_length || '|' || is_nullable"
                + " FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'workers'"
                + " AND column_name = '" + column + "'")));
    }

    // ---------- la copia ---------------------------------------------------------

    /**
     * El paterno es {@code last_name} entero, sin partir ni recortar, y el materno queda vacio: con
     * una palabra, varias, en blanco y con un espacio raro. La fila no cambia de fecha ni de firma, y
     * no queda rastro.
     */
    @ParameterizedTest(name = "last_name \"{0}\"")
    @ValueSource(strings = {"Quispe", "De la Cruz", " ", "Quispe" + NO_BREAK_SPACE + "Mamani"})
    void theCopy_takesTheWholeLastNameAsThePaternal_andTouchesNothingElse(String lastName) throws SQLException {
        inRollback(connection -> {
            undoTheMigration(connection);
            int signer = intOf(connection, "SELECT min(id) FROM public.users");
            int workerId = intOf(connection,
                "INSERT INTO public.workers (first_name, last_name, document_type_id, document_number, role_id,"
                    + " hire_date, updated_by, updated_at) VALUES ('ZTEST', ?, " + documentTypeId(connection)
                    + ", 'ZTESTSUR1', " + operatorRoleId(connection) + ", DATE '2024-01-01', " + signer
                    + ", TIMESTAMPTZ '2024-01-01 00:00:00+00') RETURNING id", lastName);
            String stampBefore = stampOf(connection, workerId);
            int auditRowsBefore = intOf(connection, "SELECT count(*) FROM public.worker_audit_logs");

            runMigration(connection);

            assertSurnames(connection, workerId, lastName, lastName, null);
            assertEquals(stampBefore, stampOf(connection, workerId));
            assertEquals(auditRowsBefore, intOf(connection, "SELECT count(*) FROM public.worker_audit_logs"));
        });
    }

    // ---------- el disparador en el alta -------------------------------------------

    /** El alta de la imagen anterior y de los INSERT crudos, solo con {@code last_name}: tal cual. */
    @ParameterizedTest(name = "last_name \"{0}\"")
    @ValueSource(strings = {"Rojas Vega", " ", " Rojas  Vega "})
    void anInsertWithOnlyLastName_copiesItToThePaternal(String lastName) throws SQLException {
        inFreshMigration(connection -> {
            int workerId = insertWorker(connection, "ZTESTSUR2", lastName, null, null);

            assertSurnames(connection, workerId, lastName, lastName, null);
        });
    }

    /**
     * El alta de la aplicacion, que ya no escribe {@code last_name}: sin materno, sin espacio al
     * final, y sin recortar las partes, que llegan recortadas.
     */
    @ParameterizedTest(name = "paterno \"{0}\", materno \"{1}\"")
    @CsvSource({"Perez, Quispe, Perez Quispe", "Perez, , Perez", "' Perez', 'Quispe ', ' Perez Quispe '"})
    void anInsertWithOnlyTheParts_composesLastName(String paternal, String maternal, String lastName)
            throws SQLException {
        inFreshMigration(connection -> {
            int workerId = insertWorker(connection, "ZTESTSUR3", null, paternal, maternal);

            assertSurnames(connection, workerId, lastName, paternal, maternal);
        });
    }

    @Test
    void anInsertWithBothSides_keepsTheParts_andRecomposesLastName() throws SQLException {
        inFreshMigration(connection -> {
            int workerId = insertWorker(connection, "ZTESTSUR4", "Otro Apellido", "Perez", "Quispe");

            assertSurnames(connection, workerId, "Perez Quispe", "Perez", "Quispe");
        });
    }

    /** Un materno sin paterno no se pierde en la copia: las partes ganan, y sin paterno no hay alta. */
    @Test
    void anInsertWithTheMaternalButNoPaternal_failsOnThePaternal() throws SQLException {
        inFreshMigration(connection -> {
            PSQLException failed = assertThrows(PSQLException.class,
                () -> insertWorker(connection, "ZTESTSUR15", "Rojas Vega", null, "Quispe"));

            assertEquals("23502", failed.getSQLState());
            assertEquals("paternal_last_name", failed.getServerErrorMessage().getColumn());
        });
    }

    @Test
    void anInsertWithoutAnySurname_failsOnLastName() throws SQLException {
        inFreshMigration(connection -> {
            PSQLException failed = assertThrows(PSQLException.class,
                () -> insertWorker(connection, "ZTESTSUR5", null, null, null));

            assertEquals("23502", failed.getSQLState());
            assertEquals("last_name", failed.getServerErrorMessage().getColumn());
        });
    }

    // ---------- el disparador en el UPDATE -----------------------------------------

    /** Por SQL, sin nombrar {@code last_name}: el disparador no mira solo esa columna. */
    @Test
    void settingTheMaternal_recomposesLastName() throws SQLException {
        inFreshMigration(connection -> {
            int workerId = insertWorker(connection, "ZTESTSUR6", null, "Perez", null);

            update(connection, workerId, "maternal_last_name = 'Quispe'");

            assertSurnames(connection, workerId, "Perez Quispe", "Perez", "Quispe");
        });
    }

    @Test
    void emptyingTheMaternal_recomposesLastName() throws SQLException {
        inFreshMigration(connection -> {
            int workerId = insertWorker(connection, "ZTESTSUR7", null, "Perez", "Quispe");

            update(connection, workerId, "maternal_last_name = NULL");

            assertSurnames(connection, workerId, "Perez", "Perez", null);
        });
    }

    @Test
    void changingThePaternal_recomposesLastName() throws SQLException {
        inFreshMigration(connection -> {
            int workerId = insertWorker(connection, "ZTESTSUR8", null, "Perez", "Quispe");

            update(connection, workerId, "paternal_last_name = 'Rojas'");

            assertSurnames(connection, workerId, "Rojas Quispe", "Rojas", "Quispe");
        });
    }

    /** Tambien con un {@code last_name} nuevo: el paterno nulo es un cambio de las partes, y gana. */
    @Test
    void emptyingThePaternal_failsOnThePaternal_evenWithANewLastName() throws SQLException {
        inFreshMigration(connection -> {
            int workerId = insertWorker(connection, "ZTESTSUR16", null, "Perez", "Quispe");

            PSQLException failed = assertThrows(PSQLException.class,
                () -> update(connection, workerId, "paternal_last_name = NULL, last_name = 'Rojas Vega'"));

            assertEquals("23502", failed.getSQLState());
            assertEquals("paternal_last_name", failed.getServerErrorMessage().getColumn());
        });
    }

    @Test
    void changingBothSides_keepsTheParts_andRecomposesLastName() throws SQLException {
        inFreshMigration(connection -> {
            int workerId = insertWorker(connection, "ZTESTSUR9", null, "Perez", "Quispe");

            update(connection, workerId, "last_name = 'Otro Apellido', paternal_last_name = 'Rojas'");

            assertSurnames(connection, workerId, "Rojas Quispe", "Rojas", "Quispe");
        });
    }

    /** La edicion de la imagen anterior, que de los apellidos escribe solo {@code last_name}. */
    @Test
    void changingOnlyLastName_copiesIt_andEmptiesTheMaternal() throws SQLException {
        inFreshMigration(connection -> {
            int workerId = insertWorker(connection, "ZTESTSUR10", null, "Perez", "Quispe");

            update(connection, workerId, "last_name = 'Rojas Vega'");

            assertSurnames(connection, workerId, "Rojas Vega", "Rojas Vega", null);
        });
    }

    /**
     * Como reescribe la fila la aplicacion en cada edicion, baja y reactivacion: todas las columnas
     * que mapea, con el mismo {@code last_name}. Las partes quedan, materno incluido.
     */
    @Test
    void rewritingTheSameLastName_keepsTheParts() throws SQLException {
        inFreshMigration(connection -> {
            int workerId = insertWorker(connection, "ZTESTSUR11", null, "Perez", "Quispe");

            update(connection, workerId, "first_name = first_name, last_name = 'Perez Quispe', is_active = false");

            assertSurnames(connection, workerId, "Perez Quispe", "Perez", "Quispe");
        });
    }

    // ---------- el materno vacio y el largo ----------------------------------------

    @ParameterizedTest(name = "materno \"{0}\"")
    @ValueSource(strings = {"", " ", "   "})
    void aMaternalThatIsEmptyOrOnlySpaces_isRejected(String maternal) throws SQLException {
        inFreshMigration(connection -> {
            PSQLException failed = assertThrows(PSQLException.class,
                () -> insertWorker(connection, "ZTESTSUR12", null, "Perez", maternal));

            assertEquals("23514", failed.getSQLState());
            assertEquals("chk_workers_maternal_last_name_not_blank", failed.getServerErrorMessage().getConstraint());
        });
    }

    @Test
    void twoPartsOf100_fitInLastName() throws SQLException {
        inFreshMigration(connection -> {
            int workerId = insertWorker(connection, "ZTESTSUR13", null, "a".repeat(100), "b".repeat(100));

            assertEquals(201, intOf(connection, "SELECT length(last_name) FROM public.workers WHERE id = " + workerId));
        });
    }

    /**
     * El {@code last_name} que compone el disparador da, con la regla de la imagen anterior (los
     * nombres y {@code last_name}), el nombre que da la regla de las partes, con materno y sin el.
     */
    @ParameterizedTest(name = "{0} {1} {2}")
    @CsvSource({"María José, De la Cruz, Núñez, María José De la Cruz Núñez", "Fabiola, Quispe, , Fabiola Quispe"})
    void theComposedLastName_givesTheFullNameOfTheParts(String firstName, String paternal, String maternal,
            String fullName) throws SQLException {
        inFreshMigration(connection -> {
            int workerId = insertWorker(connection, "ZTESTSUR14", firstName, null, paternal, maternal);

            assertEquals(fullName, textOf(connection, "SELECT trim(first_name || ' ' || last_name)"
                + " FROM public.workers WHERE id = " + workerId));
            assertEquals(fullName, textOf(connection, "SELECT " + WorkerRepository.fullNameExpression("w")
                + " FROM public.workers w WHERE w.id = " + workerId));
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
                System.err.println("AVISO: quedo un hilo vivo del caso del bloqueo de la V015");
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
     * Deshace la migracion dentro de la transaccion del caso, sobre las filas que haya: sin el
     * disparador, su funcion ni las dos columnas (el {@code CHECK} se va con el materno), y con
     * {@code last_name} de 100, cortando el que pase. Despues corre el archivo entero.
     */
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
        execute(connection, "DROP TRIGGER workers_surnames_sync ON public.workers");
        execute(connection, "DROP FUNCTION public.workers_surnames_sync()");
        execute(connection, "ALTER TABLE public.workers DROP COLUMN paternal_last_name,"
            + " DROP COLUMN maternal_last_name, ALTER COLUMN last_name TYPE VARCHAR(100) USING left(last_name, 100)");
    }

    private void runMigration(Connection connection) throws SQLException {
        try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(MIGRATION)) {
            assertNotNull(in, "no se encontro " + MIGRATION);
            execute(connection, new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new SQLException(e);
        }
    }

    private int insertWorker(Connection connection, String documentNumber, String lastName, String paternal,
            String maternal) throws SQLException {
        return insertWorker(connection, documentNumber, "ZTEST", lastName, paternal, maternal);
    }

    /** Nombra solo las columnas del apellido que no son nulas: las demas llegan vacias al disparador. */
    private int insertWorker(Connection connection, String documentNumber, String firstName, String lastName,
            String paternal, String maternal) throws SQLException {
        StringBuilder columns = new StringBuilder("first_name, document_type_id, document_number, role_id, hire_date");
        StringBuilder values = new StringBuilder("?, " + documentTypeId(connection) + ", '" + documentNumber + "', "
            + operatorRoleId(connection) + ", DATE '2024-01-01'");
        List<String> parameters = new ArrayList<>(List.of(firstName));
        String[][] surnames = {{"last_name", lastName}, {"paternal_last_name", paternal},
            {"maternal_last_name", maternal}};
        for (String[] surname : surnames) {
            if (surname[1] != null) {
                columns.append(", ").append(surname[0]);
                values.append(", ?");
                parameters.add(surname[1]);
            }
        }
        return intOf(connection, "INSERT INTO public.workers (" + columns + ") VALUES (" + values
            + ") RETURNING id", parameters.toArray(String[]::new));
    }

    private void update(Connection connection, int workerId, String assignments) throws SQLException {
        execute(connection, "UPDATE public.workers SET " + assignments + " WHERE id = " + workerId);
    }

    private void assertSurnames(Connection connection, int workerId, String lastName, String paternal,
            String maternal) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT last_name, paternal_last_name,"
                + " maternal_last_name FROM public.workers WHERE id = ?")) {
            ps.setInt(1, workerId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "no esta el trabajador " + workerId);
                assertEquals(Arrays.asList(lastName, paternal, maternal),
                    Arrays.asList(rs.getString(1), rs.getString(2), rs.getString(3)),
                    "last_name, paterno y materno");
            }
        }
    }

    private String stampOf(Connection connection, int workerId) throws SQLException {
        return textOf(connection, "SELECT updated_at::text || '|' || coalesce(updated_by::text, 'nulo')"
            + " FROM public.workers WHERE id = " + workerId);
    }

    /** El de menor id: al test le sirve cualquiera, y el seeder garantiza al menos el DNI. */
    private int documentTypeId(Connection connection) throws SQLException {
        return intOf(connection, "SELECT min(id) FROM public.document_types");
    }

    private int operatorRoleId(Connection connection) throws SQLException {
        return intOf(connection, "SELECT id FROM public.roles WHERE name = 'operator'");
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

    private static int intOf(Connection connection, String sql, String... parameters) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                ps.setString(i + 1, parameters[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "sin resultado: " + sql);
                int value = rs.getInt(1);
                assertFalse(rs.wasNull(), "nulo: " + sql);
                return value;
            }
        }
    }

    private static String textOf(Connection connection, String sql) throws SQLException {
        try (Statement st = connection.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            assertTrue(rs.next(), "sin resultado: " + sql);
            return rs.getString(1);
        }
    }
}
