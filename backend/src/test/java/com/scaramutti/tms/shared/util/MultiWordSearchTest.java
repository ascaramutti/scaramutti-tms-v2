package com.scaramutti.tms.shared.util;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit test puro (sin Quarkus) del helper de búsqueda multi-palabra que resuelve
 * RN-WH14. Rápido de correr; el AND-de-ORs contra Postgres real lo cubren los
 * integration tests de WarehouseSuppliersResourceTest y WarehouseProductsResourceTest.
 */
class MultiWordSearchTest {

    // ---------- tokenize --------------------------------------------------------

    @Test
    void tokenize_singleWord_returnsSingleElementArray() {
        assertArrayEquals(new String[]{"Repuestos"}, MultiWordSearch.tokenize("Repuestos"));
    }

    @Test
    void tokenize_multipleWords_returnsOneTokenPerWord() {
        assertArrayEquals(new String[]{"Repuestos", "Diesel"}, MultiWordSearch.tokenize("Repuestos Diesel"));
    }

    @Test
    void tokenize_multipleInternalSpaces_collapsesToNonEmptyTokens() {
        assertArrayEquals(new String[]{"Repuestos", "Diesel"}, MultiWordSearch.tokenize("Repuestos   Diesel"));
    }

    @Test
    void tokenize_leadingAndTrailingSpaces_trimsBeforeSplitting() {
        assertArrayEquals(new String[]{"Repuestos", "Diesel"}, MultiWordSearch.tokenize("  Repuestos Diesel  "));
    }

    @Test
    void tokenize_preservesOriginalTokenOrder() {
        assertArrayEquals(new String[]{"Norte", "Repuestos", "Diesel"}, MultiWordSearch.tokenize("Norte Repuestos Diesel"));
    }

    // ---------- conditions ------------------------------------------------------

    @Test
    void conditions_oneTokenTwoColumns_buildsSingleAndOfOrs() {
        Map<String, Object> params = new HashMap<>();
        List<String> conditions = MultiWordSearch.conditions("Repuestos", List.of("name", "ruc"), "qTok", params);

        assertEquals(List.of("(name ILIKE :qTok0 ESCAPE '\\' OR ruc ILIKE :qTok0 ESCAPE '\\')"), conditions);
        assertEquals("%Repuestos%", params.get("qTok0"));
    }

    @Test
    void conditions_twoTokens_buildsOneConditionPerTokenAllForAndJoin() {
        Map<String, Object> params = new HashMap<>();
        List<String> conditions = MultiWordSearch.conditions(
            "Repuestos Diesel", List.of("name", "code", "brand"), "qTok", params);

        assertEquals(2, conditions.size());
        assertEquals("(name ILIKE :qTok0 ESCAPE '\\' OR code ILIKE :qTok0 ESCAPE '\\' OR brand ILIKE :qTok0 ESCAPE '\\')",
            conditions.get(0));
        assertEquals("%Repuestos%", params.get("qTok0"));
        assertEquals("%Diesel%", params.get("qTok1"));
    }

    @Test
    void conditions_escapesLikeWildcards_soInputIsTreatedAsLiteral() {
        Map<String, Object> params = new HashMap<>();
        MultiWordSearch.conditions("100%_off", List.of("name"), "qTok", params);

        assertEquals("%100\\%\\_off%", params.get("qTok0"));
    }

    @Test
    void conditions_customPrefix_avoidsParamNameCollisions() {
        Map<String, Object> params = new HashMap<>();
        List<String> conditions = MultiWordSearch.conditions("aceite", List.of("name"), "prod", params);

        assertTrue(conditions.get(0).contains(":prod0"));
        assertTrue(params.containsKey("prod0"));
    }
}
