package com.scaramutti.tms.operations;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Cubre lo que V007__operaciones_schema.sql agrega: no hay entidades ni
 * repositorios todavia (O1 = solo DB), asi que se verifica el schema con SQL
 * directo. Las garantias probadas son las que el DDL toma a su cargo: el
 * dominio cerrado del ambito del viaje (columna + CHECK, no tabla), la
 * unicidad case-insensitive del codigo, el cruce de fechas que v1 dejaba
 * pasar, los dominios de los tipos de evento y de cambio, y la disyuncion de
 * la asignacion de refuerzo.
 *
 * V008 suma un valor al dominio del estado (DELETED), asi que el CHECK que lo
 * cierra se prueba en el mismo lugar.
 *
 * Hermetico: los FK de clients/cargo_types (tablas de v1 que ningun seeder
 * llena) se siembran dentro de la misma transaccion y todo se revierte al
 * final, asi corre igual en una DB virgen (CI) que en la dev-DB compartida.
 * Los fixtures llevan el prefijo {@code ZTEST_}/{@code ZTSRV-} para que un
 * residuo de un rollback que no llego a correr se distinga de la data real.
 */
@QuarkusTest
class OperationsSchemaMigrationTest {

    private static final String PREFIX = "ZTEST_";
    private static final String CODE_PREFIX = "ZTSRV-";

    /** Usuario que el DevDataSeeder garantiza en todo entorno (dev, CI, staging). */
    private static final String SEEDED_USERNAME = "admin";

    @Inject
    DataSource dataSource;

    @Test
    void servicesTable_isQueryable() throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            assertEquals(0, queryInt(c,
                "SELECT count(*) FROM operaciones.services WHERE code LIKE ?", CODE_PREFIX + "%"));
        }
    }

    @Test
    void service_defaultsToPendingAssignment() throws SQLException {
        inRolledBackTransaction(c -> {
            Fixtures fixtures = seedFixtures(c);
            insertService(c, fixtures, CODE_PREFIX + "9001", "LOCAL");
            assertEquals("PENDING_ASSIGNMENT", queryString(c,
                "SELECT status FROM operaciones.services WHERE code = ?", CODE_PREFIX + "9001"));
        });
    }

    /**
     * DELETED (el registro que nunca debio existir, distinto de la cancelacion de
     * un viaje real) entra al dominio del estado. Que la transicion solo salga de
     * los dos estados pendientes lo valida el backend: la DB solo abre el valor.
     */
    @Test
    void statusCheck_acceptsDeleted() throws SQLException {
        inRolledBackTransaction(c -> {
            Fixtures fixtures = seedFixtures(c);
            long serviceId = insertService(c, fixtures, CODE_PREFIX + "9009", "LOCAL");
            execute(c, "UPDATE operaciones.services SET status = ? WHERE id = ?", "DELETED", serviceId);
            assertEquals("DELETED", queryString(c,
                "SELECT status FROM operaciones.services WHERE id = ?", serviceId));
        });
    }

    /** El valor invalido entra en el VARCHAR(30): lo tiene que rechazar el CHECK, no el tipo. */
    @Test
    void statusCheck_rejectsValueOutsideTheClosedDomain() throws SQLException {
        inRolledBackTransaction(c -> {
            Fixtures fixtures = seedFixtures(c);
            long serviceId = insertService(c, fixtures, CODE_PREFIX + "9010", "LOCAL");
            assertConstraintViolation(CHECK_VIOLATION, () -> execute(c,
                "UPDATE operaciones.services SET status = ? WHERE id = ?", "ARCHIVED", serviceId));
        });
    }

    /**
     * El ambito del viaje es un dominio CERRADO (LOCAL | PROVINCIA), sin tabla de
     * catalogo. El valor invalido tiene que ENTRAR en el VARCHAR(10): uno mas
     * largo lo rechazaria el tipo antes de llegar al CHECK, y el test seguiria
     * verde aunque alguien borrara la restriccion.
     */
    @Test
    void tripScopeCheck_rejectsValueOutsideTheClosedDomain() throws SQLException {
        inRolledBackTransaction(c -> {
            Fixtures fixtures = seedFixtures(c);
            insertService(c, fixtures, CODE_PREFIX + "9002", "PROVINCIA");
            assertConstraintViolation(CHECK_VIOLATION,
                () -> insertService(c, fixtures, CODE_PREFIX + "9003", "REGIONAL"));
        });
    }

    /** El codigo lo genera el backend en mayusculas; el indice impide colarlo en minusculas. */
    @Test
    void serviceCodeUniqueIndex_isCaseInsensitive() throws SQLException {
        inRolledBackTransaction(c -> {
            Fixtures fixtures = seedFixtures(c);
            insertService(c, fixtures, CODE_PREFIX + "9004", "LOCAL");
            assertConstraintViolation(UNIQUE_VIOLATION,
                () -> insertService(c, fixtures, CODE_PREFIX.toLowerCase() + "9004", "LOCAL"));
        });
    }

    /** v1 nunca valido el cruce (hay historicos con end &lt; start); v2 lo cierra en el schema. */
    @Test
    void servicesTimesCheck_rejectsEndBeforeStart() throws SQLException {
        inRolledBackTransaction(c -> {
            Fixtures fixtures = seedFixtures(c);
            long serviceId = insertService(c, fixtures, CODE_PREFIX + "9005", "LOCAL");
            assertConstraintViolation(CHECK_VIOLATION, () -> execute(c,
                "UPDATE operaciones.services SET start_date_time = ?::timestamptz, "
                    + "end_date_time = ?::timestamptz WHERE id = ?",
                "2026-07-29T10:00:00Z", "2026-07-29T09:00:00Z", serviceId));
        });
    }

    @Test
    void serviceEventsCheck_rejectsUnknownEventType() throws SQLException {
        inRolledBackTransaction(c -> {
            Fixtures fixtures = seedFixtures(c);
            long serviceId = insertService(c, fixtures, CODE_PREFIX + "9006", "LOCAL");
            insertEvent(c, serviceId, "STATUS_CHANGE", fixtures.userId());
            assertConstraintViolation(CHECK_VIOLATION,
                () -> insertEvent(c, serviceId, "DELIVERED", fixtures.userId()));
        });
    }

    /** ADMIN_UPDATE no lo escribe el backend nuevo: entra al dominio porque el cutover lo conserva. */
    @Test
    void auditChangeTypeCheck_acceptsHistoricAdminUpdateAndRejectsUnknown() throws SQLException {
        inRolledBackTransaction(c -> {
            Fixtures fixtures = seedFixtures(c);
            long serviceId = insertService(c, fixtures, CODE_PREFIX + "9007", "LOCAL");
            insertAuditLog(c, serviceId, "ADMIN_UPDATE", fixtures.userId());
            assertConstraintViolation(CHECK_VIOLATION,
                () -> insertAuditLog(c, serviceId, "DELETED", fixtures.userId()));
        });
    }

    /**
     * Un viaje siempre tiene precio: el cero lo rechaza la BASE, no solo la validacion del
     * backend. Es lo que agrega V009 — el CHECK original lo aceptaba.
     */
    @Test
    void priceCheck_rejectsZero() throws SQLException {
        inRolledBackTransaction(c -> {
            Fixtures fixtures = seedFixtures(c);
            insertService(c, fixtures, CODE_PREFIX + "9011", "LOCAL", "0.01");
            assertConstraintViolation(CHECK_VIOLATION,
                () -> insertService(c, fixtures, CODE_PREFIX + "9012", "LOCAL", "0"));
        });
    }

    /**
     * El precio negativo ya lo rechazaba el CHECK original; se fija para que endurecerlo no lo
     * haya aflojado. Va en su PROPIA transaccion: un INSERT rechazado la aborta, y cualquier
     * sentencia posterior fallaria por eso y no por el CHECK, dando un verde falso.
     */
    @Test
    void priceCheck_rejectsNegative() throws SQLException {
        inRolledBackTransaction(c -> {
            Fixtures fixtures = seedFixtures(c);
            assertConstraintViolation(CHECK_VIOLATION,
                () -> insertService(c, fixtures, CODE_PREFIX + "9013", "LOCAL", "-1"));
        });
    }

    /** Un refuerzo sin ninguna unidad no es un refuerzo. */
    @Test
    void assignmentCheck_requiresAtLeastOneUnit() throws SQLException {
        inRolledBackTransaction(c -> {
            Fixtures fixtures = seedFixtures(c);
            long serviceId = insertService(c, fixtures, CODE_PREFIX + "9008", "LOCAL");
            assertConstraintViolation(CHECK_VIOLATION, () -> execute(c,
                "INSERT INTO operaciones.service_assignments (service_id, reason, assigned_by) "
                    + "VALUES (?, ?, ?)",
                serviceId, "Refuerzo sin unidad", fixtures.userId()));
        });
    }

    // ---------- Fixtures ------------------------------------------------------

    /** Ids de las filas de public que el servicio referencia por FK. */
    private record Fixtures(int clientId, int cargoTypeId, int currencyId, int userId) { }

    private Fixtures seedFixtures(Connection c) throws SQLException {
        int clientId = queryInt(c,
            "INSERT INTO public.clients (name, ruc, is_active) VALUES (?, ?, true) RETURNING id",
            PREFIX + "Cliente operaciones", "99000000901");
        int cargoTypeId = queryInt(c,
            "INSERT INTO public.cargo_types (name, standard_weight, is_active) VALUES (?, 1, true) RETURNING id",
            PREFIX + "Carga operaciones");
        int currencyId = queryInt(c, "SELECT id FROM public.currencies WHERE code = ?", "PEN");
        int userId = queryInt(c, "SELECT id FROM public.users WHERE username = ?", SEEDED_USERNAME);
        return new Fixtures(clientId, cargoTypeId, currencyId, userId);
    }

    private long insertService(Connection c, Fixtures fixtures, String code, String tripScope) throws SQLException {
        return insertService(c, fixtures, code, tripScope, "500");
    }

    private long insertService(Connection c, Fixtures fixtures, String code, String tripScope, String price)
            throws SQLException {
        return queryLong(c,
            "INSERT INTO operaciones.services (code, client_id, origin, destination, tentative_date, "
                + "trip_scope, cargo_type_id, weight, price, currency_id, created_by, updated_by) "
                + "VALUES (?, ?, 'Lima', 'Arequipa', DATE '2026-07-29', ?, ?, 1000, CAST(? AS NUMERIC), ?, ?, ?) "
                + "RETURNING id",
            code, fixtures.clientId(), tripScope, fixtures.cargoTypeId(), price, fixtures.currencyId(), fixtures.userId(), fixtures.userId());
    }

    private void insertEvent(Connection c, long serviceId, String eventType, int userId) throws SQLException {
        execute(c, "INSERT INTO operaciones.service_events (service_id, event_type, note, created_by) "
            + "VALUES (?, ?, ?, ?)", serviceId, eventType, "Nota de prueba", userId);
    }

    private void insertAuditLog(Connection c, long serviceId, String changeType, int userId) throws SQLException {
        execute(c, "INSERT INTO operaciones.service_audit_logs (service_id, changed_by, change_type, description) "
            + "VALUES (?, ?, ?, ?)", serviceId, userId, changeType, "Cambio de prueba");
    }

    // ---------- Plomeria JDBC -------------------------------------------------

    /** Cuerpo de test que corre contra una conexion y puede lanzar SQLException. */
    @FunctionalInterface
    private interface ConnectionBody {
        void run(Connection connection) throws SQLException;
    }

    /**
     * Corre el cuerpo en una transaccion que SIEMPRE se revierte: los fixtures de
     * public y las filas de operaciones desaparecen aunque el test falle.
     */
    private void inRolledBackTransaction(ConnectionBody body) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                body.run(c);
            } finally {
                c.rollback();
            }
        }
    }

    /** Violacion de CHECK. */
    private static final String CHECK_VIOLATION = "23514";

    /** Violacion de restriccion de unicidad (indice UNIQUE). */
    private static final String UNIQUE_VIOLATION = "23505";

    /**
     * Asevera que a la sentencia la rechaza la restriccion esperada, mirando el CODIGO de error
     * y no solo el tipo de excepcion. Sin esto una asercion puede pasar sin probar nada: un
     * error de tipo, un fallo de FK o una transaccion ya abortada por una sentencia anterior
     * lanzan SQLException igual, diga lo que diga la restriccion que el test dice cubrir.
     */
    private void assertConstraintViolation(String expectedSqlState, Executable statement) {
        SQLException failure = assertThrows(SQLException.class, statement);
        assertEquals(expectedSqlState, failure.getSQLState(),
            "se esperaba la restriccion " + expectedSqlState + ", llego: " + failure.getMessage());
    }

    private void execute(Connection c, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = prepare(c, sql, params)) {
            ps.executeUpdate();
        }
    }

    private int queryInt(Connection c, String sql, Object... params) throws SQLException {
        return (int) queryLong(c, sql, params);
    }

    private long queryLong(Connection c, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = prepare(c, sql, params); ResultSet rs = ps.executeQuery()) {
            requireRow(rs, sql);
            return rs.getLong(1);
        }
    }

    private String queryString(Connection c, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = prepare(c, sql, params); ResultSet rs = ps.executeQuery()) {
            requireRow(rs, sql);
            return rs.getString(1);
        }
    }

    private PreparedStatement prepare(Connection c, String sql, Object... params) throws SQLException {
        PreparedStatement ps = c.prepareStatement(sql);
        for (int i = 0; i < params.length; i++) {
            ps.setObject(i + 1, params[i]);
        }
        return ps;
    }

    /** Sin fila el fixture falta: mejor decir cual que dejar un error criptico mas abajo. */
    private void requireRow(ResultSet rs, String sql) throws SQLException {
        if (!rs.next()) {
            throw new IllegalStateException("Sin resultado para: " + sql);
        }
    }
}
