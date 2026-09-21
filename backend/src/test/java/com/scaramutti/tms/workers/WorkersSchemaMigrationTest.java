package com.scaramutti.tms.workers;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Cubre lo que deja la migracion del mantenimiento de trabajadores: el organigrama en
 * {@code public.roles}, el trabajador apuntando a su rol, la fecha de ingreso, las
 * columnas de auditoria y la tabla de bitacora.
 *
 * <p>Las aserciones son sobre lo que la migracion DEJO (filas, restricciones que
 * rechazan, invariantes) y nunca sobre el total de la base: la de desarrollo es
 * compartida y acumula datos reales, la de integracion continua nace vacia. Todo lo que
 * este archivo inserta corre en una transaccion que termina en {@code rollback}.
 */
@QuarkusTest
class WorkersSchemaMigrationTest {

    @Inject
    DataSource dataSource;

    // ---------- el organigrama ---------------------------------------------------

    /**
     * Las once filas del organigrama, una por caso a proposito: cuando falla, el nombre
     * del caso dice QUE rol quedo mal en vez de "la tabla no coincide".
     *
     * <p>Cubre tambien que la migracion pise la descripcion de los siete roles que ya
     * existian: desde esta unidad la descripcion es el nombre visible del cargo, asi que
     * un rol con el texto viejo se ve acá y no en el pie del menu de alguien.
     */
    @ParameterizedTest(name = "{0} es {1}, nivel {2}")
    @CsvSource({
        "admin,              Administrador del Sistema,  4, true,  NONE",
        "general_manager,    Gerente General,            3, true,  NONE",
        "operations_manager, Gerente de Operaciones,     3, true,  NONE",
        "finance_manager,    Jefe de Finanzas,           2, true,  NONE",
        "dispatcher,         Coordinador de Operaciones, 2, true,  NONE",
        "sales,              Ejecutivo de Ventas,        2, true,  NONE",
        "warehouse_keeper,   Encargado de Almacén,       1, true,  NONE",
        "driver,             Conductor,                  1, false, REQUIRED",
        "escort,             Escolta,                    1, false, REQUIRED",
        "assistant,          Ayudante,                   1, false, OPTIONAL",
        "operator,           Operador,                   1, false, NONE",
    })
    void eachRoleOfTheOrganigram_hasItsVisibleNameLevelLoginAndDriverProfile(
            String name, String description, int level, boolean canLogin, String driverProfile)
            throws SQLException {
        assertEquals(1, count(
            "SELECT count(*) FROM public.roles WHERE name = '" + name + "'"
                + " AND description = '" + description + "'"
                + " AND level = " + level
                + " AND can_login = " + canLogin
                + " AND driver_profile = '" + driverProfile + "'"
                + " AND is_active = true"));
    }

    @Test
    void noRole_isLeftWithoutLevelOrOutsideItsRange() throws SQLException {
        assertEquals(0, count(
            "SELECT count(*) FROM public.roles WHERE level IS NULL OR level NOT BETWEEN 1 AND 4"));
    }

    /**
     * El nivel no tiene valor por omision a proposito: un rol nuevo nace con el suyo. Si
     * alguien le pone un {@code DEFAULT} para "arreglar" un INSERT, un rol sorpresa entra
     * al organigrama con un nivel inventado y nadie se entera.
     */
    @Test
    void aRoleWithoutLevel_isRejected() throws SQLException {
        inRollback(connection -> assertThrows(SQLException.class, () -> execute(connection,
            "INSERT INTO public.roles (name, description, can_login, driver_profile)"
                + " VALUES ('ztestrole1', 'ZTEST sin nivel', true, 'NONE')")));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 5})
    void aLevelOutsideOneToFour_isRejected(int level) throws SQLException {
        inRollback(connection -> assertThrows(SQLException.class, () -> execute(connection,
            "INSERT INTO public.roles (name, description, level, can_login, driver_profile)"
                + " VALUES ('ztestrole2', 'ZTEST nivel raro', " + level + ", true, 'NONE')")));
    }

    @Test
    void aDriverProfileOutsideTheThree_isRejected() throws SQLException {
        inRollback(connection -> assertThrows(SQLException.class, () -> execute(connection,
            "INSERT INTO public.roles (name, description, level, can_login, driver_profile)"
                + " VALUES ('ztestrole3', 'ZTEST modalidad rara', 1, false, 'MAYBE')")));
    }

    // ---------- el trabajador apunta a su rol ------------------------------------

    /** La guarda de la migracion no dejo a nadie sin rol, sea cual sea el texto que tenia. */
    @Test
    void everyWorker_pointsToARole() throws SQLException {
        assertEquals(0, count("SELECT count(*) FROM public.workers WHERE role_id IS NULL"));
    }

    /**
     * El rol del trabajador es el de su usuario. Es la invariante que sostiene que cambiar
     * el cargo cambie los permisos: si se rompe, alguien tiene un cargo que dice una cosa y
     * un acceso que dice otra.
     */
    @Test
    void everyWorkerWithUser_carriesTheRoleOfItsUser() throws SQLException {
        assertEquals(0, count(
            "SELECT count(*) FROM public.workers w JOIN public.users u ON u.worker_id = w.id"
                + " WHERE w.role_id <> u.role_id"));
    }

    /**
     * La columna del cargo viejo deja de ser obligatoria. Es lo unico que hace posible
     * volver a la imagen anterior de la aplicacion dentro de la ventana de la release: esa
     * imagen todavia la escribe.
     */
    @Test
    void theOldPositionColumn_isNoLongerMandatory() throws SQLException {
        assertEquals("YES", textOf(
            "SELECT is_nullable FROM information_schema.columns"
                + " WHERE table_schema = 'public' AND table_name = 'workers' AND column_name = 'position'"));
    }

    // ---------- fecha de ingreso y auditoria -------------------------------------

    @Test
    void everyWorker_hasAHireDate() throws SQLException {
        assertEquals(0, count("SELECT count(*) FROM public.workers WHERE hire_date IS NULL"));
    }

    /**
     * Sin valor por omision: una fecha de ingreso puesta por la base es indistinguible de
     * una real, y esta columna existe justamente porque la fecha de alta de la fila no
     * sirve como fecha de ingreso de la persona.
     */
    @Test
    void aWorkerWithoutHireDate_isRejected() throws SQLException {
        inRollback(connection -> assertThrows(SQLException.class, () ->
            insertWorker(connection, "ZTESTMIG1", false)));
    }

    @Test
    void everyWorker_hasAnUpdatedAtNotBeforeItsCreatedAt() throws SQLException {
        assertEquals(0, count(
            "SELECT count(*) FROM public.workers WHERE updated_at IS NULL OR updated_at < created_at"));
    }

    /**
     * La marca de modificacion tiene valor por omision, al reves que la fecha de ingreso:
     * los fixtures y los sembradores insertan por SQL nativo sin nombrarla.
     */
    @Test
    void aNativeInsertWithoutUpdatedAt_getsOne() throws SQLException {
        inRollback(connection -> {
            int workerId = assertDoesNotThrow(() -> insertWorker(connection, "ZTESTMIG2", true));
            assertNotNull(assertDoesNotThrow(() -> textOf(connection,
                "SELECT updated_at::text FROM public.workers WHERE id = " + workerId)));
        });
    }

    /** Quien creo y quien modifico pueden faltar: las filas viejas no lo saben. */
    @Test
    void theAuditColumns_acceptNoAuthor() throws SQLException {
        inRollback(connection -> assertDoesNotThrow(() -> insertWorker(connection, "ZTESTMIG3", true)));
    }

    @Test
    void anAuthorThatDoesNotExist_isRejected() throws SQLException {
        inRollback(connection -> {
            int workerId = assertDoesNotThrow(() -> insertWorker(connection, "ZTESTMIG4", true));
            assertThrows(SQLException.class, () -> execute(connection,
                "UPDATE public.workers SET created_by = 999999999 WHERE id = " + workerId));
        });
    }

    // ---------- la bitacora ------------------------------------------------------

    @Test
    void theAuditLogTable_resolvesWithAllItsColumns() throws SQLException {
        assertDoesNotThrow(() -> count(
            "SELECT count(*) FROM (SELECT worker_id, change_type, field_name, field_label,"
                + " old_value, new_value, reason, changed_by, logged_at"
                + " FROM public.worker_audit_logs) entries"));
    }

    @Test
    void anAuditChangeTypeOutsideTheFour_isRejected() throws SQLException {
        inRollback(connection -> {
            int workerId = assertDoesNotThrow(() -> insertWorker(connection, "ZTESTMIG5", true));
            assertThrows(SQLException.class, () -> execute(connection,
                "INSERT INTO public.worker_audit_logs (worker_id, change_type, changed_by)"
                    + " VALUES (" + workerId + ", 'RENAMED', " + anyUserId(connection) + ")"));
        });
    }

    @Test
    void theIndexesOfTheUnit_exist() throws SQLException {
        assertEquals(2, count(
            "SELECT count(*) FROM pg_indexes WHERE schemaname = 'public'"
                + " AND indexname IN ('idx_workers_role', 'idx_worker_audit_worker')"));
    }

    // ---------- utilidades -------------------------------------------------------

    /**
     * Inserta un trabajador de prueba por SQL nativo. {@code withHireDate} en falso omite
     * la fecha de ingreso, que es lo que el caso de la columna obligatoria necesita.
     */
    private int insertWorker(Connection connection, String documentNumber, boolean withHireDate)
            throws SQLException {
        String columns = "first_name, last_name, document_type_id, document_number, role_id"
            + (withHireDate ? ", hire_date" : "");
        String values = "'ZTEST', 'Migracion', " + anyDocumentTypeId(connection) + ", '"
            + documentNumber + "', " + roleId(connection, "operator")
            + (withHireDate ? ", DATE '2024-01-01'" : "");
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO public.workers (" + columns + ") VALUES (" + values + ") RETURNING id")) {
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private int roleId(Connection connection, String name) throws SQLException {
        return intOf(connection, "SELECT id FROM public.roles WHERE name = '" + name + "'");
    }

    private int anyDocumentTypeId(Connection connection) throws SQLException {
        return intOf(connection, "SELECT min(id) FROM public.document_types");
    }

    private int anyUserId(Connection connection) throws SQLException {
        return intOf(connection, "SELECT min(id) FROM public.users");
    }

    private void execute(Connection connection, String sql) throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.executeUpdate(sql);
        }
    }

    /**
     * Corre el cuerpo en una transaccion propia y la deshace siempre: la base de prueba es
     * compartida y ninguno de estos casos debe dejar una fila atras, ni siquiera cuando
     * falla.
     */
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

    private int count(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            return intOf(connection, sql);
        }
    }

    private int intOf(Connection connection, String sql) throws SQLException {
        try (Statement st = connection.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private String textOf(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            return textOf(connection, sql);
        }
    }

    private String textOf(Connection connection, String sql) throws SQLException {
        try (Statement st = connection.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }
}
