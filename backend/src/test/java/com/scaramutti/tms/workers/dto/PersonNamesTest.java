package com.scaramutti.tms.workers.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.text.Normalizer;
import java.time.Duration;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersonNamesTest {

    @Test
    void toNfc_composesACombiningAccent() {
        assertEquals("Jos\u00e9", PersonNames.toNfc("Jose\u0301"));
        assertNull(PersonNames.toNfc(null));
    }

    /** Cien letras de cuatro caracteres cada una (la composicion mas larga) se siguen normalizando. */
    @Test
    void toNfc_atTheLimit_stillNormalizes() {
        String fourInOne = Normalizer.normalize("\u1f82", Normalizer.Form.NFD);
        assertEquals(4, fourInOne.length());

        String atTheLimit = fourInOne.repeat(100);
        assertEquals(PersonNames.MAX_NORMALIZED_LENGTH, atTheLimit.length());
        assertEquals("\u1f82".repeat(100), PersonNames.toNfc(atTheLimit));
    }

    /** Miles de marcas alternadas costarian segundos de reordenamiento: pasado el tope no se tocan. */
    @Test
    void toNfc_overTheLimit_returnsTheValueAsItCame() {
        String marks = "a" + "\u0301\u0316".repeat(40_000);
        assertSame(marks, PersonNames.toNfc(marks));

        String justOver = Normalizer.normalize("\u1f82", Normalizer.Form.NFD).repeat(100) + "a";
        assertSame(justOver, PersonNames.toNfc(justOver));
    }

    /** Los casos los comparte el formulario, que los mide contra el patron que publica el contrato. */
    @Test
    void theSharedCases_matchThePattern() throws Exception {
        JsonNode cases;
        try (var in = getClass().getResourceAsStream("/workers/person-names.json")) {
            cases = new ObjectMapper().readTree(in);
        }
        for (JsonNode accepted : cases.get("accepted")) {
            assertTrue(matchesTheRule(accepted.asText()), "deberia entrar: " + accepted.asText());
        }
        for (JsonNode rejected : cases.get("rejected")) {
            assertFalse(matchesTheRule(rejected.asText()), "deberia quedar afuera: " + rejected.asText());
        }
    }

    private static boolean matchesTheRule(String value) {
        return PersonNames.toNfc(value).matches(PersonNames.PATTERN);
    }

    /**
     * La regla corre sobre el valor entero, antes que el largo. Con clases que se solapan, un
     * texto largo que falla al final retrocede en tiempo cuadratico; la forma elegida es lineal.
     */
    @Test
    void thePattern_rejectsALongNonMatchingValue_inLinearTime() {
        String longFailure = "a".repeat(200_000) + "1";
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> assertFalse(longFailure.matches(PersonNames.PATTERN)));
    }

    /** El tope tiene que valer en la frontera: los dos cuerpos pasan por el, no por su propio NFC. */
    @Test
    void bothBodies_overTheLimit_keepTheNamesAsTheyCame() {
        String marks = "a" + "\u0301\u0316".repeat(40_000);
        LocalDate hired = LocalDate.of(2024, 3, 1);

        WorkerRequest created = new WorkerRequest(marks, marks, 1, "X1", null, "operator", hired, null);
        assertSame(marks, created.firstName());
        assertSame(marks, created.lastName());

        WorkerUpdateRequest updated = new WorkerUpdateRequest(marks, marks, 1, "X1", null, "operator", hired, null, null);
        assertSame(marks, updated.firstName());
        assertSame(marks, updated.lastName());
    }
}
