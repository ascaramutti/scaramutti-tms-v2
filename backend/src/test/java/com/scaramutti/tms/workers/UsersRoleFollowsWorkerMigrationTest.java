package com.scaramutti.tms.workers;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
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
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cubre la migracion que hace que la cuenta tome el rol de su trabajador: la guarda que aborta
 * ante una divergencia y los dos disparadores.
 *
 * <p>Casi todos los casos borran los disparadores y vuelven a correr el archivo, dentro de una
 * transaccion que termina en {@code rollback} y mientras dura tiene tomadas las dos tablas: asi se
 * mide el archivo y no lo que dejo Flyway. Los que miran lo que dejo Flyway lo dicen.
 */
@QuarkusTest
class UsersRoleFollowsWorkerMigrationTest {

    private static final String MIGRATION = "db/migration/V012__public_users_role_follows_worker.sql";
    private static final Pattern OFFENDING_ACCOUNTS = Pattern.compile("\\(users\\.id\\): ([0-9, ]+)");
    private static final String BOTH_TRIGGERS_ENABLED =
        "SELECT count(*) FROM pg_trigger WHERE tgenabled = 'O' AND ("
            + "(tgname = 'users_role_from_worker' AND tgrelid = 'public.users'::regclass)"
            + " OR (tgname = 'workers_role_to_account' AND tgrelid = 'public.workers'::regclass))";

    @Inject
    DataSource dataSource;

    /** Lo que dejo Flyway. */
    @Test
    void theMigration_isAppliedExactlyOnce() throws SQLException {
        inRollback(connection -> assertEquals(1, intOf(connection,
            "SELECT count(*) FROM flyway_schema_history WHERE version = '012' AND success")));
    }

    /** Lo que dejo Flyway: los dos disparadores, cada uno en su tabla y encendido. */
    @Test
    void bothTriggers_areInPlace_andEnabled() throws SQLException {
        inRollback(connection -> assertEquals(2, intOf(connection, BOTH_TRIGGERS_ENABLED)));
    }

    /**
     * El archivo los deja encendidos en el modo de siempre. En ALWAYS correrian tambien con los
     * disparadores apagados por sesion, y los tests que siembran una divergencia no podrian.
     */
    @Test
    void theFile_leavesBothTriggersEnabled() throws SQLException {
        inFreshMigration(connection -> assertEquals(2, intOf(connection, BOTH_TRIGGERS_ENABLED)));
    }

    // ---------- la guarda -------------------------------------------------------

    /**
     * La lista nombra a las dos cuentas divergentes y a ninguna otra. Una tiene el rol por encima
     * del cargo y la otra por debajo, y la segunda esta de baja con su trabajador: reactivada,
     * volveria con un rol que nadie miro.
     */
    @Test
    void withDivergentAccounts_theMigrationAborts_namingExactlyThose() throws SQLException {
        inRollback(connection -> {
            dropTriggersAndAlignLeftovers(connection);
            int above = insertUser(connection, insertWorker(connection, "ZTESTSYNC1", "sales", true),
                "ztestusersync1", "admin", true);
            int below = insertUser(connection, insertWorker(connection, "ZTESTSYNC13", "admin", false),
                "ztestusersync13", "sales", false);
            insertUser(connection, insertWorker(connection, "ZTESTSYNC2", "sales", true),
                "ztestusersync2", "sales", true);

            PSQLException aborted = assertThrows(PSQLException.class, () -> runMigration(connection));

            String message = aborted.getServerErrorMessage().getMessage();
            assertTrue(message.startsWith("cuentas con un rol distinto del cargo de su trabajador"), message);
            assertEquals(List.of(String.valueOf(Math.min(above, below)), String.valueOf(Math.max(above, below))),
                offendingAccounts(message), message);
        });
    }

    // ---------- la cuenta toma el rol de su trabajador ---------------------------

    /** Es lo que necesita el alta de una cuenta cuando la aplicacion deje de escribir la columna. */
    @Test
    void anAccountCreatedWithoutRole_takesTheRoleOfItsWorker() throws SQLException {
        inFreshMigration(connection -> {
            int workerId = insertWorker(connection, "ZTESTSYNC3", "dispatcher");
            int userId = insertUserWithoutRole(connection, workerId, "ztestusersync3");

            assertEquals("dispatcher", roleOfUser(connection, userId));
        });
    }

    @Test
    void anAccountCreatedWithAnotherRole_takesTheRoleOfItsWorker() throws SQLException {
        inFreshMigration(connection -> {
            int userId = insertUser(connection, insertWorker(connection, "ZTESTSYNC4", "sales"),
                "ztestusersync4", "admin");

            assertEquals("sales", roleOfUser(connection, userId));
        });
    }

    @Test
    void writingAnotherRoleOnTheAccount_leavesTheRoleOfItsWorker() throws SQLException {
        inFreshMigration(connection -> {
            int userId = insertUser(connection, insertWorker(connection, "ZTESTSYNC5", "sales"),
                "ztestusersync5", "sales");

            execute(connection, "UPDATE public.users SET role_id = " + roleId(connection, "admin")
                + " WHERE id = " + userId);

            assertEquals("sales", roleOfUser(connection, userId));
        });
    }

    @Test
    void movingTheAccountToAnotherWorker_takesTheRoleOfTheNewWorker() throws SQLException {
        inFreshMigration(connection -> {
            int userId = insertUser(connection, insertWorker(connection, "ZTESTSYNC6", "sales"),
                "ztestusersync6", "sales");
            int otherWorker = insertWorker(connection, "ZTESTSYNC7", "finance_manager");

            execute(connection, "UPDATE public.users SET worker_id = " + otherWorker + " WHERE id = " + userId);

            assertEquals("finance_manager", roleOfUser(connection, userId));
        });
    }

    /**
     * Si el trabajador no existe no hay rol que copiar: el disparador deja el valor como vino, y
     * con el rol escrito el alta falla por la clave foranea del trabajador, que dice lo que falta,
     * y no por un rol nulo ni por la clave del rol. Sin rol, falla el NOT NULL.
     */
    @Test
    void anAccountWhoseWorkerDoesNotExist_failsOnTheForeignKey() throws SQLException {
        inFreshMigration(connection -> {
            int missingWorker = intOf(connection, "SELECT coalesce(max(id), 0) + 1000 FROM public.workers");

            PSQLException rejected = assertThrows(PSQLException.class, () -> execute(connection,
                "INSERT INTO public.users (worker_id, username, password_hash, role_id) VALUES ("
                    + missingWorker + ", 'ztestusersync8', 'ztest', " + roleId(connection, "sales") + ")"));

            assertEquals("23503", rejected.getSQLState(), rejected.getMessage());
            assertEquals("users_worker_id_fkey", rejected.getServerErrorMessage().getConstraint());
        });
    }

    // ---------- el cargo del trabajador llega a su cuenta -------------------------

    /**
     * Tambien con la cuenta y el trabajador de baja: al reactivarla se escribe solo is_active, que
     * no dispara nada, asi que la cuenta tiene que volver con el cargo de hoy.
     */
    @Test
    void changingTheRoleOfTheWorker_changesTheRoleOfItsAccount() throws SQLException {
        inFreshMigration(connection -> {
            int workerId = insertWorker(connection, "ZTESTSYNC9", "sales", false);
            int userId = insertUser(connection, workerId, "ztestusersync9", "sales", false);

            setWorkerRole(connection, workerId, "operations_manager");

            assertEquals("operations_manager", roleOfUser(connection, userId));
        });
    }

    /**
     * Ni le cambia el rol ni reescribe su fila. El rol solo no alcanza: el disparador de la cuenta
     * le volveria a poner el de su trabajador, pero cada cambio de cargo tomaria todas las cuentas.
     */
    @Test
    void changingTheRoleOfTheWorker_leavesTheOtherAccountsAlone() throws SQLException {
        inFreshMigration(connection -> {
            int workerId = insertWorker(connection, "ZTESTSYNC10", "sales");
            insertUser(connection, workerId, "ztestusersync10", "sales");
            int otherUser = insertUser(connection, insertWorker(connection, "ZTESTSYNC11", "dispatcher"),
                "ztestusersync11", "dispatcher");
            String before = rowPositionOfUser(connection, otherUser);

            setWorkerRole(connection, workerId, "operations_manager");

            assertEquals("dispatcher", roleOfUser(connection, otherUser));
            assertEquals(before, rowPositionOfUser(connection, otherUser));
        });
    }

    /**
     * Editar el trabajador sin cambiarle el cargo no reescribe la fila de la cuenta: si la
     * reescribiera, cada edicion esperaria a quien tenga tomada esa fila, por ejemplo un cambio de
     * contrasena.
     */
    @Test
    void editingTheWorkerWithoutChangingItsRole_doesNotRewriteItsAccount() throws SQLException {
        inFreshMigration(connection -> {
            int workerId = insertWorker(connection, "ZTESTSYNC12", "sales");
            int userId = insertUser(connection, workerId, "ztestusersync12", "sales");
            String before = rowPositionOfUser(connection, userId);

            execute(connection, "UPDATE public.workers SET first_name = 'ZTEST editado', role_id = role_id"
                + " WHERE id = " + workerId);

            assertEquals(before, rowPositionOfUser(connection, userId));
        });
    }

    // ---------- con otra transaccion en curso -------------------------------------

    /**
     * Un alta mientras el cargo del trabajador cambia en otra transaccion espera detras de ese
     * cambio y toma el cargo nuevo: sin esperar, o leyendo antes de esperar, se quedaria con el
     * viejo y nada lo vuelve a mirar. Mira el disparador que dejo Flyway, sobre filas confirmadas.
     */
    @Test
    void anAccountCreatedWhileItsWorkerChangesRole_waits_andTakesTheNewRole() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection changing = dataSource.getConnection(); Connection creating = dataSource.getConnection();
             Connection observer = dataSource.getConnection()) {
            deleteConcurrencyRows(observer);
            try {
                int workerId = insertWorker(observer, "ZTESTSYNC20", "sales", true);
                int changingPid = intOf(changing, "SELECT pg_backend_pid()");
                int creatingPid = intOf(creating, "SELECT pg_backend_pid()");
                changing.setAutoCommit(false);
                setWorkerRole(changing, workerId, "admin");
                creating.setAutoCommit(false);
                execute(creating, "SET LOCAL lock_timeout = '10s'");

                Future<Integer> created = executor.submit(() ->
                    insertUserWithoutRole(creating, workerId, "ztestusersync20"));

                assertTrue(waitsBehind(observer, creatingPid, changingPid, created),
                    "el alta no espero detras del cambio de cargo");
                changing.commit();
                int userId = created.get(10, TimeUnit.SECONDS);
                creating.commit();
                assertEquals("admin", roleOfUser(observer, userId));
            } finally {
                if (!changing.getAutoCommit()) {
                    changing.rollback();
                }
                executor.shutdownNow();
                executor.awaitTermination(10, TimeUnit.SECONDS);
                if (!creating.getAutoCommit()) {
                    creating.rollback();
                    creating.setAutoCommit(true);
                }
                deleteConcurrencyRows(observer);
            }
        }
    }

    /**
     * Cambiar la contrasena no espera a una edicion del trabajador en curso: el disparador de la
     * cuenta corre solo si se le escribe el rol o el trabajador. Si corriera en toda escritura, su
     * FOR SHARE dejaria cada cambio de contrasena detras de la edicion. Mira el disparador que dejo
     * Flyway, sobre filas confirmadas.
     */
    @Test
    void changingThePassword_doesNotWaitForAnEditOfTheWorker() throws SQLException {
        try (Connection editing = dataSource.getConnection(); Connection writing = dataSource.getConnection()) {
            deleteConcurrencyRows(writing);
            try {
                int workerId = insertWorker(writing, "ZTESTSYNC21", "sales", true);
                int userId = insertUserWithoutRole(writing, workerId, "ztestusersync21");
                editing.setAutoCommit(false);
                execute(editing, "SELECT id FROM public.workers WHERE id = " + workerId + " FOR NO KEY UPDATE");
                writing.setAutoCommit(false);
                execute(writing, "SET LOCAL lock_timeout = '200ms'");

                assertDoesNotThrow(() -> execute(writing,
                    "UPDATE public.users SET password_hash = 'ztest-otra' WHERE id = " + userId));
            } finally {
                if (!editing.getAutoCommit()) {
                    editing.rollback();
                }
                if (!writing.getAutoCommit()) {
                    writing.rollback();
                    writing.setAutoCommit(true);
                }
                deleteConcurrencyRows(writing);
            }
        }
    }

    // ---------- fixtures ----------------------------------------------------------

    /** Corre el bloque sobre los disparadores que deja el archivo, no sobre los de la base. */
    private void inFreshMigration(SqlBlock block) throws SQLException {
        inRollback(connection -> {
            dropTriggersAndAlignLeftovers(connection);
            runMigration(connection);
            block.run(connection);
        });
    }

    /**
     * Saca los disparadores y sus funciones con un tope de 5 s: sin el, si otra conexion tiene
     * tomada una de las tablas el test espera para siempre. Una corrida cortada de otra clase puede
     * dejar una divergencia sembrada en una cuenta de prueba: aca se iguala, para que la guarda no
     * la cuente.
     */
    private void dropTriggersAndAlignLeftovers(Connection connection) throws SQLException {
        execute(connection, "SET LOCAL lock_timeout = '5s'");
        execute(connection, "DROP TRIGGER IF EXISTS users_role_from_worker ON public.users");
        execute(connection, "DROP TRIGGER IF EXISTS workers_role_to_account ON public.workers");
        execute(connection, "DROP FUNCTION IF EXISTS public.users_role_from_worker()");
        execute(connection, "DROP FUNCTION IF EXISTS public.workers_role_to_account()");
        execute(connection, "UPDATE public.users u SET role_id = w.role_id FROM public.workers w"
            + " WHERE w.id = u.worker_id AND u.role_id <> w.role_id AND u.username LIKE 'ztestuser%'");
    }

    private void runMigration(Connection connection) throws SQLException {
        try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(MIGRATION)) {
            assertNotNull(in, "no se encontro " + MIGRATION);
            execute(connection, new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new SQLException(e);
        }
    }

    private static List<String> offendingAccounts(String message) {
        Matcher matcher = OFFENDING_ACCOUNTS.matcher(message);
        assertTrue(matcher.find(), message);
        return Arrays.stream(matcher.group(1).split(",")).map(String::trim).toList();
    }

    private int insertWorker(Connection connection, String documentNumber, String roleName) throws SQLException {
        return insertWorker(connection, documentNumber, roleName, true);
    }

    private int insertWorker(Connection connection, String documentNumber, String roleName, boolean isActive)
            throws SQLException {
        return intOf(connection,
            "INSERT INTO public.workers (first_name, last_name, document_type_id, document_number, role_id,"
                + " hire_date, is_active) VALUES ('ZTEST', 'Sync', " + documentTypeId(connection) + ", '"
                + documentNumber + "', " + roleId(connection, roleName) + ", DATE '2024-01-01', " + isActive
                + ") RETURNING id");
    }

    private int insertUser(Connection connection, int workerId, String username, String roleName)
            throws SQLException {
        return insertUser(connection, workerId, username, roleName, true);
    }

    private int insertUser(Connection connection, int workerId, String username, String roleName, boolean isActive)
            throws SQLException {
        return intOf(connection,
            "INSERT INTO public.users (worker_id, username, password_hash, role_id, is_active) VALUES ("
                + workerId + ", '" + username + "', 'ztest', " + roleId(connection, roleName) + ", " + isActive
                + ") RETURNING id");
    }

    private int insertUserWithoutRole(Connection connection, int workerId, String username) throws SQLException {
        return intOf(connection, "INSERT INTO public.users (worker_id, username, password_hash) VALUES ("
            + workerId + ", '" + username + "', 'ztest') RETURNING id");
    }

    /** Las filas de los casos con dos transacciones, que se confirman: antes por si una corrida se corto. */
    private void deleteConcurrencyRows(Connection connection) throws SQLException {
        execute(connection, "DELETE FROM public.users WHERE username IN ('ztestusersync20', 'ztestusersync21')");
        execute(connection, "DELETE FROM public.workers WHERE document_number IN ('ZTESTSYNC20', 'ZTESTSYNC21')");
    }

    /**
     * Si la sesion de {@code waiterPid} espera a la de {@code holderPid}. Se pregunta a la base y no
     * al reloj: un alta que falla al instante o que termina sin esperar corta la espera enseguida.
     */
    private boolean waitsBehind(Connection observer, int waiterPid, int holderPid, Future<?> waiter)
            throws SQLException, InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!waiter.isDone() && System.nanoTime() < deadline) {
            if (intOf(observer, "SELECT count(*) FROM unnest(pg_blocking_pids(" + waiterPid + ")) AS pid"
                    + " WHERE pid = " + holderPid) == 1) {
                return true;
            }
            Thread.sleep(20);
        }
        return false;
    }

    private void setWorkerRole(Connection connection, int workerId, String roleName) throws SQLException {
        execute(connection, "UPDATE public.workers SET role_id = " + roleId(connection, roleName)
            + " WHERE id = " + workerId);
    }

    /** La posicion fisica de la fila: un UPDATE la mueve aunque escriba los mismos valores. */
    private String rowPositionOfUser(Connection connection, int userId) throws SQLException {
        return textOf(connection, "SELECT ctid::text FROM public.users WHERE id = " + userId);
    }

    private String roleOfUser(Connection connection, int userId) throws SQLException {
        return textOf(connection,
            "SELECT r.name FROM public.users u JOIN public.roles r ON r.id = u.role_id WHERE u.id = " + userId);
    }

    /** El de menor id: al test le sirve cualquiera, y el seeder garantiza al menos el DNI. */
    private int documentTypeId(Connection connection) throws SQLException {
        return intOf(connection, "SELECT min(id) FROM public.document_types");
    }

    private int roleId(Connection connection, String name) throws SQLException {
        return intOf(connection, "SELECT id FROM public.roles WHERE name = '" + name + "'");
    }

    private void execute(Connection connection, String sql) throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.execute(sql);
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

    private int intOf(Connection connection, String sql) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next(), "sin resultado: " + sql);
            return rs.getInt(1);
        }
    }

    private String textOf(Connection connection, String sql) throws SQLException {
        try (Statement st = connection.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            assertTrue(rs.next(), "sin resultado: " + sql);
            return rs.getString(1);
        }
    }
}
