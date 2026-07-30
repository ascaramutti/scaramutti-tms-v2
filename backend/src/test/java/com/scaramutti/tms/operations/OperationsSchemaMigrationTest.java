package com.scaramutti.tms.operations;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

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
            Fixtures f = seedFixtures(c);
            insertService(c, f, CODE_PREFIX + "9001", "LOCAL");
            assertEquals("PENDING_ASSIGNMENT", queryString(c,
                "SELECT status FROM operaciones.services WHERE code = ?", CODE_PREFIX + "9001"));
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
            Fixtures f = seedFixtures(c);
            insertService(c, f, CODE_PREFIX + "9002", "PROVINCIA");
            assertThrows(SQLException.class,
                () -> insertService(c, f, CODE_PREFIX + "9003", "REGIONAL"));
        });
    }

    /** El codigo lo genera el backend en mayusculas; el indice impide colarlo en minusculas. */
    @Test
    void serviceCodeUniqueIndex_isCaseInsensitive() throws SQLException {
        inRolledBackTransaction(c -> {
            Fixtures f = seedFixtures(c);
            insertService(c, f, CODE_PREFIX + "9004", "LOCAL");
            assertThrows(SQLException.class,
                () -> insertService(c, f, CODE_PREFIX.toLowerCase() + "9004", "LOCAL"));
        });
    }

    /** v1 nunca valido el cruce (hay historicos con end &lt; start); v2 lo cierra en el schema. */
    @Test
    void servicesTimesCheck_rejectsEndBeforeStart() throws SQLException {
        inRolledBackTransaction(c -> {
            Fixtures f = seedFixtures(c);
            long serviceId = insertService(c, f, CODE_PREFIX + "9005", "LOCAL");
            assertThrows(SQLException.class, () -> execute(c,
                "UPDATE operaciones.services SET start_date_time = ?::timestamptz, "
                    + "end_date_time = ?::timestamptz WHERE id = ?",
                "2026-07-29T10:00:00Z", "2026-07-29T09:00:00Z", serviceId));
        });
    }

    @Test
    void serviceEventsCheck_rejectsUnknownEventType() throws SQLException {
        inRolledBackTransaction(c -> {
            Fixtures f = seedFixtures(c);
            long serviceId = insertService(c, f, CODE_PREFIX + "9006", "LOCAL");
            insertEvent(c, serviceId, "STATUS_CHANGE", f.userId());
            assertThrows(SQLException.class, () -> insertEvent(c, serviceId, "DELIVERED", f.userId()));
        });
    }

    /** ADMIN_UPDATE no lo escribe el backend nuevo: entra al dominio porque el cutover lo conserva. */
    @Test
    void auditChangeTypeCheck_acceptsHistoricAdminUpdateAndRejectsUnknown() throws SQLException {
        inRolledBackTransaction(c -> {
            Fixtures f = seedFixtures(c);
            long serviceId = insertService(c, f, CODE_PREFIX + "9007", "LOCAL");
            insertAuditLog(c, serviceId, "ADMIN_UPDATE", f.userId());
            assertThrows(SQLException.class, () -> insertAuditLog(c, serviceId, "DELETED", f.userId()));
        });
    }

    /** Un refuerzo sin ninguna unidad no es un refuerzo. */
    @Test
    void assignmentCheck_requiresAtLeastOneUnit() throws SQLException {
        inRolledBackTransaction(c -> {
            Fixtures f = seedFixtures(c);
            long serviceId = insertService(c, f, CODE_PREFIX + "9008", "LOCAL");
            assertThrows(SQLException.class, () -> execute(c,
                "INSERT INTO operaciones.service_assignments (service_id, reason, assigned_by) "
                    + "VALUES (?, ?, ?)",
                serviceId, "Refuerzo sin unidad", f.userId()));
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

    private long insertService(Connection c, Fixtures f, String code, String tripScope) throws SQLException {
        return queryLong(c,
            "INSERT INTO operaciones.services (code, client_id, origin, destination, tentative_date, "
                + "trip_scope, cargo_type_id, weight, price, currency_id, created_by, updated_by) "
                + "VALUES (?, ?, 'Lima', 'Arequipa', DATE '2026-07-29', ?, ?, 1000, 500, ?, ?, ?) "
                + "RETURNING id",
            code, f.clientId(), tripScope, f.cargoTypeId(), f.currencyId(), f.userId(), f.userId());
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
