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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Cubre lo que V002__almacen_schema.sql + R__almacen_stock_views.sql
 * agregan: no hay entidades/repositorios todavia (A1 = solo DB), asi que se
 * verifica el schema con SQL directo.
 */
@QuarkusTest
class WarehouseSchemaMigrationTest {

    @Inject
    DataSource dataSource;

    @Test
    void productCategoriesSeed_has7ActiveRows() throws SQLException {
        assertEquals(7, count("SELECT count(*) FROM almacen.product_categories WHERE is_active = true"));
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

    @Test
    void productStockView_isQueryable() throws SQLException {
        assertEquals(0, count("SELECT count(*) FROM almacen.product_stock"));
    }

    @Test
    void stockMovementsView_isQueryable() throws SQLException {
        assertEquals(0, count("SELECT count(*) FROM almacen.stock_movements"));
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
