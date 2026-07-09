package com.scaramutti.tms.shared.repository;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/**
 * Unit test puro (sin Quarkus) del tokenizador de {@link SupplierRepository}
 * que resuelve RN-WH14 (busqueda multi-palabra). Rapido de correr; el AND-de-ORs
 * contra Postgres real lo cubren los integration tests de
 * WarehouseSuppliersResourceTest.
 */
class SupplierRepositoryTokenizeTest {

    @Test
    void tokenize_singleWord_returnsSingleElementArray() {
        assertArrayEquals(new String[]{"Repuestos"}, SupplierRepository.tokenize("Repuestos"));
    }

    @Test
    void tokenize_multipleWords_returnsOneTokenPerWord() {
        assertArrayEquals(new String[]{"Repuestos", "Diesel"}, SupplierRepository.tokenize("Repuestos Diesel"));
    }

    @Test
    void tokenize_multipleInternalSpaces_collapsesToNonEmptyTokens() {
        assertArrayEquals(new String[]{"Repuestos", "Diesel"}, SupplierRepository.tokenize("Repuestos   Diesel"));
    }

    @Test
    void tokenize_leadingAndTrailingSpaces_trimsBeforeSplitting() {
        assertArrayEquals(new String[]{"Repuestos", "Diesel"}, SupplierRepository.tokenize("  Repuestos Diesel  "));
    }

    @Test
    void tokenize_preservesOriginalTokenOrder() {
        assertArrayEquals(new String[]{"Norte", "Repuestos", "Diesel"}, SupplierRepository.tokenize("Norte Repuestos Diesel"));
    }
}
