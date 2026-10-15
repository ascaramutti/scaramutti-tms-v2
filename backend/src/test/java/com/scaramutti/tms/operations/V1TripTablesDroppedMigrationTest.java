package com.scaramutti.tms.operations;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.metamodel.MappingMetamodel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.postgresql.util.PSQLException;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.scaramutti.tms.support.LockWaiters.backendPid;
import static com.scaramutti.tms.support.MigrationLocks.awaitRetry;
import static com.scaramutti.tms.support.MigrationLocks.codeOf;
import static com.scaramutti.tms.support.MigrationLocks.firstStatementOf;
import static com.scaramutti.tms.support.MigrationLocks.lockWaitSamples;
import static com.scaramutti.tms.support.MigrationLocks.tablesLockedBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cubre la migracion que borra las cinco tablas de viajes de la v1.
 *
 * <p>El archivo no se puede volver a correr: lo que borra ya no esta. Los casos miran lo que dejo
 * Flyway, el mapeo de Hibernate y el texto del archivo. Tres corren el bloqueo: dos crean antes
 * las cinco tablas vacias dentro de la transaccion de la migracion, que termina en
 * {@code rollback}, y el tercero lo corre sin ellas a proposito.
 */
@QuarkusTest
class V1TripTablesDroppedMigrationTest {

    private static final String MIGRATION = "db/migration/V014__public_drop_v1_trip_tables.sql";
    private static final List<String> V1_TABLES = List.of("service_audit_logs", "service_assignments",
        "services", "service_statuses", "service_types");

    @Inject
    DataSource dataSource;

    @Inject
    EntityManagerFactory entityManagerFactory;

    @Test
    void theMigration_isAppliedExactlyOnce() throws SQLException {
        assertEquals(1, intOf(
            "SELECT count(*) FROM flyway_schema_history WHERE version = '014' AND success"));
    }

    @ParameterizedTest(name = "public.{0} ya no existe")
    @ValueSource(strings = {"services", "service_assignments", "service_audit_logs", "service_statuses",
        "service_types"})
    void eachTripTableOfV1_isGone(String table) throws SQLException {
        assertEquals(1, tableCount("clients"), "una tabla viva se ve: el cero mide algo");
        assertEquals(0, tableCount(table));
    }

    /** Por las tablas que mapea Hibernate (entidades y colecciones), con o sin {@code @Table}. */
    @Test
    void noMapping_namesATripTableOfV1() {
        Set<String> tables = new TreeSet<>();
        MappingMetamodel mapping =
            entityManagerFactory.unwrap(SessionFactoryImplementor.class).getMappingMetamodel();
        mapping.forEachEntityDescriptor(entity -> tables.addAll(List.of(entity.getTableNames())));
        mapping.forEachCollectionDescriptor(collection -> tables.add(collection.getTableName()));
        assertTrue(tables.contains("operaciones.services"), "control, las tablas salen calificadas: " + tables);
        List<String> v1 = tables.stream()
            .filter(table -> V1_TABLES.contains(table.replaceFirst("^public\\.", ""))).toList();
        assertEquals(List.of(), v1);
    }

    /** El borrado nombra las cinco y nada mas: sin CASCADE, una dependencia nueva lo frena. */
    @Test
    void theDropOfTheFile_namesTheFiveTables_withoutCascade() throws Exception {
        String code = codeOf(MIGRATION);
        String drop = code.substring(code.indexOf("DROP TABLE "));
        assertFalse(drop.contains("CASCADE"), drop);
        Set<String> dropped = Arrays.stream(drop.substring("DROP TABLE ".length(), drop.indexOf(';'))
            .split(",")).map(String::strip).collect(Collectors.toSet());
        assertEquals(V1_TABLES.stream().map(table -> "public." + table).collect(Collectors.toSet()), dropped);
    }

    /**
     * El bloque toma las cinco tablas y las siete vivas a las que apuntan sus claves foraneas: son
     * las que el borrado toma en exclusivo (medido con {@code pg_locks} en una copia). Una que
     * falte la tomaria el borrado en cola, frenando las lecturas de esa tabla.
     */
    @Test
    void theLockOfTheFile_takesTheFiveTablesAndTheLiveOnesTheyPointTo() throws Exception {
        assertEquals(Set.of("public.service_audit_logs", "public.service_assignments",
                "public.services", "public.service_statuses", "public.service_types", "public.users",
                "public.clients", "public.drivers", "public.tractors", "public.trailers",
                "public.cargo_types", "public.currencies"),
            Set.copyOf(tablesLockedBy(firstStatementOf(MIGRATION))));
    }

    /**
     * El bloqueo del archivo contra una transaccion que ya leyo una tabla viva y despues lee otra:
     * la migracion reintenta sin hacer cola, la segunda lectura no la espera y nadie se abraza.
     */
    @ParameterizedTest(name = "lee {0} y despues {1}")
    @CsvSource({"clients, users", "users, clients", "currencies, drivers", "tractors, cargo_types",
        "trailers, currencies"})
    void theLockOfTheFile_retriesWithoutQueueing_againstAReaderOfTwoLiveTables(
            String first, String second) throws Exception {
        String lockBlock = firstStatementOf(MIGRATION);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection reader = dataSource.getConnection();
             Connection migration = dataSource.getConnection();
             Connection observer = dataSource.getConnection()) {
            reader.setAutoCommit(false);
            migration.setAutoCommit(false);
            try {
                execute(reader, "SET LOCAL lock_timeout = '3s'");
                execute(reader, "SELECT count(*) FROM public." + first);
                // Tope de sentencia y no de bloqueo: el bloque atrapa el fin de un lock_timeout y
                // lo reintenta. Asi, un bloqueo que vuelva a hacer cola hace fallar el caso.
                execute(migration, "SET LOCAL statement_timeout = '20s'");
                createTheFiveTablesEmpty(migration);
                int migrationPid = backendPid(migration);

                Future<?> locked = executor.submit(() -> {
                    execute(migration, lockBlock);
                    return null;
                });
                assertTrue(awaitRetry(observer, migrationPid, locked),
                    "la migracion tiene que estar reintentando antes de la segunda lectura");
                assertEquals(0, lockWaitSamples(observer, migrationPid, 300),
                    "la migracion espero en la cola en vez de reintentar");

                long startNanos = System.nanoTime();
                assertDoesNotThrow(() -> execute(reader, "SELECT count(*) FROM public." + second));
                long waitedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
                assertTrue(waitedMillis < 1000, "la segunda lectura espero " + waitedMillis + " ms");
                reader.rollback();
                assertDoesNotThrow(() -> locked.get(10, TimeUnit.SECONDS));
            } finally {
                // Primero el lector: suelto, la migracion termina sola y el hilo no espera su tope.
                try {
                    reader.rollback();
                } finally {
                    executor.shutdownNow();
                    if (!executor.awaitTermination(25, TimeUnit.SECONDS)) {
                        System.err.println("AVISO: quedo un hilo vivo del caso del bloqueo de la V014");
                    }
                    migration.rollback();
                }
            }
        }
    }

    /**
     * El tope: si una tabla sigue tomada, el bloque se rinde con su mensaje en vez de seguir de largo
     * y hacer cola en el borrado. Corre el bloque del archivo con tres intentos en vez de
     * seiscientos, contra una sesion que retiene {@code users}, la primera tabla viva del bloque:
     * otra sesion que retenga una tabla posterior no cambia cual nombra el mensaje.
     */
    @Test
    void theLockOfTheFile_givesUpWithItsMessage_whenATableStaysTaken() throws Exception {
        String lockBlock = firstStatementOf(MIGRATION);
        // 600 intentos con 50 ms entre uno y otro son los 30 s que dice el mensaje; y solo se
        // reintenta el bloqueo no disponible: cualquier otro error sale en el acto con su causa.
        assertTrue(lockBlock.contains("FOR attempt IN 1..600 LOOP"), "el tope del archivo cambio");
        assertTrue(lockBlock.contains("PERFORM pg_sleep(0.05);"), "la pausa del archivo cambio");
        assertTrue(lockBlock.contains("en 30 s"), "el mensaje ya no dice el tope");
        assertTrue(lockBlock.contains("IN ACCESS EXCLUSIVE MODE NOWAIT;"), "el bloqueo hace cola");
        assertTrue(lockBlock.contains("EXCEPTION WHEN lock_not_available THEN"),
            "el bloque reintenta otros errores");
        String shortBlock = lockBlock.replace("FOR attempt IN 1..600 LOOP", "FOR attempt IN 1..3 LOOP");
        try (Connection holder = dataSource.getConnection();
             Connection migration = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            migration.setAutoCommit(false);
            try {
                execute(holder, "SELECT count(*) FROM public.users");
                execute(migration, "SET LOCAL statement_timeout = '20s'");
                createTheFiveTablesEmpty(migration);

                PSQLException gaveUp = assertThrows(PSQLException.class,
                    () -> execute(migration, shortBlock));
                assertEquals("P0001", gaveUp.getSQLState());
                String message = gaveUp.getServerErrorMessage().getMessage();
                assertTrue(message.startsWith("no se pudieron tomar"), message);
                assertTrue(message.contains("public.users"), "el mensaje nombra la tabla tomada: " + message);
            } finally {
                rollbackBoth(holder, migration);
            }
        }
    }

    /**
     * Antes de cerrar: el pool, al devolver una conexion sin autocommit, confirma lo pendiente, y
     * la migracion creo las cinco tablas en su transaccion.
     */
    private static void rollbackBoth(Connection first, Connection second) throws SQLException {
        try {
            first.rollback();
        } finally {
            second.rollback();
        }
    }

    /** Solo se reintenta el bloqueo no disponible: sin las cinco tablas, el error sale con su causa. */
    @Test
    void theLockOfTheFile_failsAtOnceWithTheCause_onAnyOtherError() throws Exception {
        String shortBlock = firstStatementOf(MIGRATION)
            .replace("FOR attempt IN 1..600 LOOP", "FOR attempt IN 1..3 LOOP");
        try (Connection migration = dataSource.getConnection()) {
            migration.setAutoCommit(false);
            try {
                execute(migration, "SET LOCAL statement_timeout = '20s'");
                PSQLException failed = assertThrows(PSQLException.class, () -> execute(migration, shortBlock));
                assertEquals("42P01", failed.getSQLState(), failed.getMessage());
            } finally {
                migration.rollback();
            }
        }
    }

    /** Las cinco tablas, vacias, solo en la transaccion de la migracion: el bloque las nombra. */
    private static void createTheFiveTablesEmpty(Connection migration) throws SQLException {
        for (String table : V1_TABLES) {
            execute(migration, "CREATE TABLE public." + table + " (id integer)");
        }
    }

    /** Por el catalogo y no por information_schema, que oculta las tablas sin privilegios. */
    private int tableCount(String table) throws SQLException {
        return intOf("SELECT count(to_regclass('public." + table + "'))");
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
