package com.scaramutti.tms.warehouse;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Cubre lo que V002__almacen_schema.sql + R__almacen_stock_views.sql
 * agregan: no hay entidades/repositorios todavia (A1 = solo DB), asi que se
 * verifica el schema con SQL directo.
 *
 * Las aserciones son sobre lo que la MIGRACION dejo (filas sembradas, vistas que
 * resuelven, restricciones que rechazan), nunca sobre el estado global de la base: la
 * de desarrollo es compartida y acumula data real de uso, la de CI nace virgen.
 */
@QuarkusTest
class WarehouseSchemaMigrationTest {

    @Inject
    DataSource dataSource;

    /**
     * Las 7 categorias que siembra la migracion existen y estan activas. NO se afirma el
     * TOTAL de la tabla: las categorias se crean al vuelo desde la aplicacion, asi que una
     * base con uso real tiene mas (la de CI, virgen, tiene exactamente estas).
     */
    @Test
    void seededProductCategories_existAndAreActive() throws SQLException {
        assertEquals(7, count(
            "SELECT count(*) FROM almacen.product_categories WHERE is_active = true AND name IN "
                + "('Repuestos','Lubricantes','Neumáticos','Filtros','EPP','Herramientas','Consumibles')"));
    }

    @Test
    void unitsOfMeasureSeed_has7ActiveRows() throws SQLException {
        assertEquals(7, count("SELECT count(*) FROM almacen.units_of_measure WHERE is_active = true"));
    }

    @Test
    void newRoles_areSeeded() throws SQLException {
        assertEquals(2, count(
            "SELECT count(*) FROM public.roles WHERE name IN ('finance_manager','warehouse_keeper')"));
    }

    /**
     * La VIEW resuelve y TODAS sus columnas siguen ahi: se proyectan una por una, porque un
     * {@code count(*)} pelado no toca ninguna y un rename dentro de la vista pasaria de
     * largo hasta romper el kardex o los reportes. NO se afirma cuantas filas tiene: sobre
     * una base con movimientos reales no esta vacia.
     */
    @Test
    void productStockView_resolvesWithAllItsColumns() {
        assertDoesNotThrow(() -> count(
            "SELECT count(*) FROM (SELECT product_id, stock, low_stock FROM almacen.product_stock) v"));
    }

    /**
     * RN-WH11: low_stock = stock < min_stock (ESTRICTO), definido en la VIEW. Sin
     * movimientos el stock es 0, asi que un min_stock &gt; 0 marca lowStock; un
     * min_stock = 0 no (0 &lt; 0 es falso). Cubre la desigualdad estricta sin depender
     * de las entidades de movimiento (todavia no existen).
     */
    @Test
    void productStockView_lowStock_isStrictLessThan() throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                int categoryId = firstId(c, "SELECT id FROM almacen.product_categories LIMIT 1");
                int unitId = firstId(c, "SELECT id FROM almacen.units_of_measure LIMIT 1");
                int userId = firstId(c, "SELECT id FROM public.users LIMIT 1");

                insertProductWithMinStock(c, "WH-TEST Low", categoryId, unitId, userId, "5");
                insertProductWithMinStock(c, "WH-TEST NotLow", categoryId, unitId, userId, "0");

                assertEquals(true, lowStockOf(c, "WH-TEST Low"));   // stock 0 < 5
                assertEquals(false, lowStockOf(c, "WH-TEST NotLow")); // stock 0 < 0 => false (estricto)
            } finally {
                c.rollback();
            }
        }
    }

    private void insertProductWithMinStock(Connection c, String name, int categoryId, int unitId,
                                            int userId, String minStock) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO almacen.products (name, category_id, unit_of_measure_id, min_stock, created_by) "
                    + "VALUES (?, ?, ?, CAST(? AS NUMERIC), ?)")) {
            ps.setString(1, name);
            ps.setInt(2, categoryId);
            ps.setInt(3, unitId);
            ps.setString(4, minStock);
            ps.setInt(5, userId);
            ps.executeUpdate();
        }
    }

    private boolean lowStockOf(Connection c, String name) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT ps.low_stock FROM almacen.product_stock ps "
                    + "JOIN almacen.products p ON p.id = ps.product_id WHERE p.name = ?")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getBoolean(1);
            }
        }
    }

    /** Misma idea que {@link #productStockView_resolvesWithAllItsColumns()}, con las 7 columnas de esta. */
    @Test
    void stockMovementsView_resolvesWithAllItsColumns() {
        assertDoesNotThrow(() -> count(
            "SELECT count(*) FROM (SELECT movement_type, product_id, quantity, moved_at, "
                + "registered_by, source_id, movement_seq FROM almacen.stock_movements) v"));
    }

    @Test
    void productsIdentityUniqueIndex_rejectsDuplicateNameBrandPartNumber() throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                int categoryId = firstId(c, "SELECT id FROM almacen.product_categories LIMIT 1");
                int unitId = firstId(c, "SELECT id FROM almacen.units_of_measure LIMIT 1");
                int userId = firstId(c, "SELECT id FROM public.users LIMIT 1");

                insertProduct(c, "WH-TEST Filtro de aceite", categoryId, unitId, "Bosch", "P7123", userId);
                assertThrows(SQLException.class, () ->
                    insertProduct(c, "WH-TEST Filtro de aceite", categoryId, unitId, "Bosch", "P7123", userId));
            } finally {
                c.rollback();
            }
        }
    }

    private void insertProduct(Connection c, String name, int categoryId, int unitId,
                                String brand, String partNumber, int userId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO almacen.products (name, category_id, unit_of_measure_id, brand, part_number, created_by) "
                    + "VALUES (?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, name);
            ps.setInt(2, categoryId);
            ps.setInt(3, unitId);
            ps.setString(4, brand);
            ps.setString(5, partNumber);
            ps.setInt(6, userId);
            ps.executeUpdate();
        }
    }

    private int firstId(Connection c, String sql) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private int count(String sql) throws SQLException {
        try (Connection c = dataSource.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
