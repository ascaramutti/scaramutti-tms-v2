package com.scaramutti.tms.workers;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Cubre la migracion que firma con el usuario admin a los trabajadores sin autor.
 *
 * <p>Flyway la aplica al arrancar, antes de que el seeder siembre al admin: en una base
 * vacia (integracion continua) corre sin admin y no hace nada. Por eso cada caso vuelve a
 * correr el mismo archivo sobre filas propias, dentro de una transaccion que termina en
 * {@code rollback}, y nunca afirma sobre el total de la base.
 */
@QuarkusTest
class WorkersLegacyCreatedByMigrationTest {

    private static final String MIGRATION = "db/migration/V011__public_workers_legacy_created_by.sql";

    @Inject
    DataSource dataSource;

    @Test
    void theMigration_isAppliedExactlyOnce() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            assertEquals(1, intOf(connection,
                "SELECT count(*) FROM flyway_schema_history WHERE version = '011' AND success"));
        }
    }

    /**
     * El admin de la prueba no es ni el primer usuario ni el ultimo, y el otro autor es el
     * ultimo: firmar con el menor id, con el mayor o con el primero de rol admin daria otro.
     */
    @Test
    void withAdmin_theWorkersWithoutAuthorGoToAdmin_andTheOthersKeepTheirs() throws SQLException {
        inRollback(connection -> {
            execute(connection, "UPDATE public.users SET username = 'ztestoldadmin' WHERE username = 'admin'");
            int adminId = createUser(connection, "admin", "ZTESTLEG0");
            int otherAuthorId = createUser(connection, "ztestlegacyauthor", "ZTESTLEG1");
            int withoutAuthor = insertWorker(connection, "ZTESTLEG2", null, null);
            int withAuthor = insertWorker(connection, "ZTESTLEG3", otherAuthorId, null);
            int editedLater = insertWorker(connection, "ZTESTLEG7", null, otherAuthorId);
            // En el pasado: dentro de la transaccion, now() es la misma hora del INSERT.
            execute(connection, "UPDATE public.workers SET updated_at = TIMESTAMPTZ '2024-01-01 00:00:00+00'"
                + " WHERE id = " + withoutAuthor);
            String updatedAtBefore = updatedAt(connection, withoutAuthor);

            runMigration(connection);

            assertEquals(adminId, createdBy(connection, withoutAuthor));
            assertEquals(otherAuthorId, createdBy(connection, withAuthor));
            assertNull(updatedBy(connection, withoutAuthor));
            assertNull(updatedBy(connection, withAuthor));
            assertEquals(updatedAtBefore, updatedAt(connection, withoutAuthor));
            // Una fila anterior que alguien edito: gana autor y conserva la firma del editor.
            assertEquals(adminId, createdBy(connection, editedLater));
            assertEquals(otherAuthorId, updatedBy(connection, editedLater));
        });
    }

    /** Sin admin no hay quien firme: no falla y la fila sigue sin autor. */
    @Test
    void withoutAdmin_itDoesNotFail_andTheWorkersStayWithoutAuthor() throws SQLException {
        inRollback(connection -> {
            execute(connection, "UPDATE public.users SET username = 'ztestnotadmin' WHERE username = 'admin'");
            int withoutAuthor = insertWorker(connection, "ZTESTLEG4", null, null);

            assertDoesNotThrow(() -> runMigration(connection));

            assertNull(createdBy(connection, withoutAuthor));
        });
    }

    /**
     * No deja nada detras: un trabajador que nace despues sin decir su autor (como el primero
     * de una base vacia) sigue sin autor. Se inserta sin la columna para que un valor por
     * omision o un trigger se vean.
     */
    @Test
    void aWorkerCreatedAfterTheMigration_isNotTouched() throws SQLException {
        inRollback(connection -> {
            runMigration(connection);
            int bornLater = intOf(connection,
                "INSERT INTO public.workers (first_name, last_name, document_type_id, document_number, role_id,"
                    + " hire_date) VALUES ('ZTEST', 'Legacy', " + documentTypeId(connection) + ", 'ZTESTLEG6', "
                    + roleId(connection, "operator") + ", DATE '2024-01-01') RETURNING id");

            assertNull(createdBy(connection, bornLater));
        });
    }

    // ---------- fixtures ----------------------------------------------------------

    private void runMigration(Connection connection) throws SQLException {
        try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(MIGRATION)) {
            assertNotNull(in, "no se encontro " + MIGRATION);
            execute(connection, new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new SQLException(e);
        }
    }

    private int createUser(Connection connection, String username, String documentNumber) throws SQLException {
        int workerId = insertWorker(connection, documentNumber, null, null);
        return intOf(connection,
            "INSERT INTO public.users (worker_id, username, password_hash, role_id) VALUES ("
                + workerId + ", '" + username + "', 'ztest', " + roleId(connection, "admin") + ") RETURNING id");
    }

    private int insertWorker(Connection connection, String documentNumber, Integer createdBy, Integer updatedBy)
            throws SQLException {
        return intOf(connection,
            "INSERT INTO public.workers (first_name, last_name, document_type_id, document_number, role_id,"
                + " hire_date, created_by, updated_by) VALUES ('ZTEST', 'Legacy', " + documentTypeId(connection)
                + ", '" + documentNumber + "', " + roleId(connection, "operator") + ", DATE '2024-01-01', "
                + (createdBy == null ? "NULL" : createdBy) + ", " + (updatedBy == null ? "NULL" : updatedBy)
                + ") RETURNING id");
    }

    private Integer createdBy(Connection connection, int workerId) throws SQLException {
        return nullableInt(connection, "SELECT created_by FROM public.workers WHERE id = " + workerId);
    }

    private Integer updatedBy(Connection connection, int workerId) throws SQLException {
        return nullableInt(connection, "SELECT updated_by FROM public.workers WHERE id = " + workerId);
    }

    private String updatedAt(Connection connection, int workerId) throws SQLException {
        return textOf(connection, "SELECT updated_at::text FROM public.workers WHERE id = " + workerId);
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
        Integer value = nullableInt(connection, sql);
        assertNotNull(value, "sin resultado: " + sql);
        return value;
    }

    private Integer nullableInt(Connection connection, String sql) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) {
                return null;
            }
            int value = rs.getInt(1);
            return rs.wasNull() ? null : value;
        }
    }

    private String textOf(Connection connection, String sql) throws SQLException {
        try (Statement st = connection.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }
}
