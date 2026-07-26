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
 * Cubre lo que V005__almacen_ci_index_hardening.sql agrega: unicidad
 * case-insensitive de products.code (SKU) e indice trgm sobre suppliers.ruc.
 * SQL directo (mismo patron que WarehouseSchemaMigrationTest).
 */
@QuarkusTest
class WarehouseCiHardeningTest {

    @Inject
    DataSource dataSource;

    @Test
    void productsCodeUniqueIndex_isCaseInsensitive() throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                int categoryId = firstId(c, "SELECT id FROM almacen.product_categories LIMIT 1");
                int unitId = firstId(c, "SELECT id FROM almacen.units_of_measure LIMIT 1");
                int userId = firstId(c, "SELECT id FROM public.users LIMIT 1");

                insertProduct(c, "WH-TEST SKU upper", "ZZZ-TEST-1", categoryId, unitId, userId);
                // Mismo code en otro casing -> el indice funcional lower(code) lo rechaza.
                assertThrows(SQLException.class, () ->
                    insertProduct(c, "WH-TEST SKU lower", "zzz-test-1", categoryId, unitId, userId));
            } finally {
                c.rollback();
            }
        }
    }

    @Test
    void productsCodeUniqueIndex_allowsMultipleNullCodes() throws SQLException {
        // El indice es parcial (WHERE code IS NOT NULL): SKU es opcional, varios
        // productos sin code deben coexistir.
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                int categoryId = firstId(c, "SELECT id FROM almacen.product_categories LIMIT 1");
                int unitId = firstId(c, "SELECT id FROM almacen.units_of_measure LIMIT 1");
                int userId = firstId(c, "SELECT id FROM public.users LIMIT 1");

                insertProduct(c, "WH-TEST NoSku A", null, categoryId, unitId, userId);
                insertProduct(c, "WH-TEST NoSku B", null, categoryId, unitId, userId);
            } finally {
                c.rollback();
            }
        }
    }

    @Test
    void suppliersRucTrgmIndex_exists() throws SQLException {
        assertEquals(1, count(
            "SELECT count(*) FROM pg_indexes "
                + "WHERE schemaname = 'almacen' AND tablename = 'suppliers' "
                + "AND indexname = 'idx_suppliers_ruc_trgm'"));
    }

    @Test
    void productsCodeCiUniqueIndex_exists() throws SQLException {
        assertEquals(1, count(
            "SELECT count(*) FROM pg_indexes "
                + "WHERE schemaname = 'almacen' AND tablename = 'products' "
                + "AND indexname = 'uq_products_code_ci'"));
    }

    private void insertProduct(Connection c, String name, String code, int categoryId,
                                int unitId, int userId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO almacen.products (name, code, category_id, unit_of_measure_id, created_by) "
                    + "VALUES (?, ?, ?, ?, ?)")) {
            ps.setString(1, name);
            ps.setString(2, code);
            ps.setInt(3, categoryId);
            ps.setInt(4, unitId);
            ps.setInt(5, userId);
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
