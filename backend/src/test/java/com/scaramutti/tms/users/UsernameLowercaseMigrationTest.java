package com.scaramutti.tms.users;

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

import static com.scaramutti.tms.support.MigrationLocks.firstStatementOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Los casos que corren el archivo lo hacen tras deshacer la migracion, dentro de una transaccion que
 * termina en {@code rollback}: asi se mide el archivo y no lo que dejo Flyway.
 */
@QuarkusTest
class UsernameLowercaseMigrationTest {

    private static final String MIGRATION = "db/migration/V018__public_users_username_lowercase.sql";
    private static final String SESSION_VERSION_MIGRATION = "db/migration/V016__public_users_session_version.sql";

    @Inject
    DataSource dataSource;

    @Test
    void theMigration_isAppliedExactlyOnce() throws SQLException {
        inRollback(connection -> assertEquals(1, intOf(connection,
            "SELECT count(*) FROM flyway_schema_history WHERE version = '018' AND success")));
    }

    /** Validada y no {@code NOT VALID}: asi la base comprobo tambien las filas que ya estaban. */
    @Test
    void theFile_leavesAValidatedCheck() throws SQLException {
        inFreshMigration(connection -> assertEquals(
            List.of("true|CHECK (((username)::text = lower((username)::text)))"), textsOf(connection,
                "SELECT convalidated || '|' || pg_get_constraintdef(oid) FROM pg_constraint"
                    + " WHERE conrelid = 'public.users'::regclass AND conname = 'chk_users_username_lowercase'")));
    }

    @Test
    void anUppercaseUsername_isRejectedByTheCheck_onInsert() throws SQLException {
        inFreshMigration(connection -> {
            // El trabajador es el de una cuenta que ya existe: el CHECK corre antes que los unicos.
            PSQLException rejected = assertThrows(PSQLException.class, () -> execute(connection,
                "INSERT INTO public.users (username, password_hash, worker_id)"
                    + " SELECT 'ztestuserJPerez', 'ztest-no-login', worker_id FROM public.users"
                    + " WHERE id = (SELECT min(id) FROM public.users)"));

            assertEquals("23514", rejected.getSQLState());
            assertEquals("chk_users_username_lowercase", rejected.getServerErrorMessage().getConstraint());
        });
    }

    @Test
    void anUppercaseUsername_isRejectedByTheCheck_onUpdate() throws SQLException {
        inFreshMigration(connection -> {
            // El motor registra la fila rechazada entera: sin el hash real, no lo deja en su log.
            execute(connection, "UPDATE public.users SET password_hash = 'ztest-no-login'"
                + " WHERE id = (SELECT min(id) FROM public.users)");
            PSQLException rejected = assertThrows(PSQLException.class, () -> execute(connection,
                "UPDATE public.users SET username = 'X' || left(username, 49)"
                    + " WHERE id = (SELECT min(id) FROM public.users)"));

            assertEquals("23514", rejected.getSQLState());
            assertEquals("chk_users_username_lowercase", rejected.getServerErrorMessage().getConstraint());
        });
    }

    /** Una de las dos lleva una mayuscula fuera de ASCII: la guarda tiene que ver lo mismo que el CHECK. */
    @Test
    void theGuard_abortsWithTheIdsOnly_whenAnAccountHasUppercase() throws SQLException {
        inRollback(connection -> {
            undoTheMigration(connection);
            List<String> ids = textsOf(connection, "SELECT id::text FROM public.users ORDER BY id LIMIT 2");
            assertEquals(2, ids.size(), "precondicion: hay dos cuentas");
            // La segunda primero: la lista sale por id y no en el orden de los cambios.
            execute(connection, "UPDATE public.users SET username = 'X' || left(username, 49) WHERE id = " + ids.get(1));
            execute(connection, "UPDATE public.users SET username = 'Ñ' || left(username, 49) WHERE id = " + ids.get(0));

            PSQLException aborted = assertThrows(PSQLException.class, () -> runMigration(connection));

            assertEquals("P0001", aborted.getSQLState());
            assertEquals("cuentas con mayusculas en el nombre de usuario (users.id): " + ids.get(0) + ", " + ids.get(1),
                aborted.getServerErrorMessage().getMessage());
            assertNull(aborted.getServerErrorMessage().getDetail(), "sin DETAIL");
            assertNull(aborted.getServerErrorMessage().getHint(), "sin HINT");
        });
    }

    // ---------- el bloqueo -----------------------------------------------------------

    /** El mismo bloque que la de la version de las sesiones, cuyos casos miden que no hace cola. */
    @Test
    void theLockOfTheFile_isTheOneOfTheSessionVersion() throws IOException {
        assertEquals(firstStatementOf(SESSION_VERSION_MIGRATION), firstStatementOf(MIGRATION));
    }

    @Test
    void theWholeFile_takesNoOtherTableThanUsers() throws SQLException {
        inFreshMigration(connection -> assertEquals(List.of("users"), textsOf(connection,
            "SELECT DISTINCT c.relname FROM pg_locks l JOIN pg_class c ON c.oid = l.relation"
                + " WHERE l.pid = pg_backend_pid() AND c.relkind = 'r' AND c.relnamespace <> 'pg_catalog'::regnamespace"
                + " ORDER BY 1")));
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

    private void undoTheMigration(Connection connection) throws SQLException {
        execute(connection, "SET LOCAL lock_timeout = '5s'");
        execute(connection, "ALTER TABLE public.users DROP CONSTRAINT chk_users_username_lowercase");
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
