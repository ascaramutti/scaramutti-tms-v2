package com.scaramutti.tms.users.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** El nombre generado, sin la base de datos: lo tomado lo pone cada caso. */
class UsernameGeneratorTest {

    private static final int WORKER_ID = 31;
    private static final String LOGIN_PATTERN = "^[a-z0-9]{3,50}$";

    private final UsernameGenerator generator = new UsernameGenerator();

    /** Nombre, paterno, materno (vacio es nulo), lo tomado separado por punto y coma, y lo esperado. */
    @ParameterizedTest(name = "{0} / {1} / {2} con [{3}] -> {4}")
    @CsvSource(value = {
        "Juan|Pérez|Quispe||jperez",
        "Juan|Pérez|Quispe|jperez|jperez1",
        "Juan|Pérez|Quispe|jperez;jperez1|jperez2",
        "Juan|Pérez|Quispe|jperez1|jperez",
        "José|Núñez||jnunez|jnunez1",
        "José|Núñez|Quispe|jnunez|jnunez1",
        "María José|De la Cruz|Pérez||mdelacruz",
        "Luis|San Martín|Ñahui||lsanmartin",
        "Rosa|García-Pérez|Rojas||rgarciaperez",
        "Jürgen|Groß|||jgross",
        "Ana|O|||anao",
        "Ana|O||anao|anao1",
        "A|O|||usuario31",
        "A|O||usuario31|usuario311",
        "Иван|Петров|||usuario31",
        "Ana|Ox|||aox",
        "A|O|X||aox",
        "Иван|Pérez|||perez",
        "¡Ana|D'Angelo|||adangelo",
        "Juan|Pérez 2|||jperez2",
    }, delimiter = '|')
    void generatedNames_followTheNamingRules(
        String firstName, String paternal, String maternal, String taken, String expected) {
        assertEquals(expected, generate(firstName, paternal, maternal, takenOf(taken)));
    }

    /** Las letras que la descomposicion no separa, en minuscula y en mayuscula. */
    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
        "Øystein Solskjær, osolskjaer",
        "Łukasz Łęcki, llecki",
        "Đorđe Đoković, ddokovic",
        "Œdipe Rœux, oroeux",
        "Ana GROẞ, agross",
        "Æsa Mæhle, amaehle",
        "Þóra Ðiaz, tdiaz",
        "Ana Þórsson, athorsson",
        "Ana Ħajduk, ahajduk",
        "Ana Ŧomić, atomic",
        "Ana Yıldız, ayildiz",
        "Ana ĸalaallit, akalaallit",
        "Ana Ŋuyen, anuyen",
    })
    void theLettersWithoutDecomposition_mapToLatinLetters(String names, String expected) {
        String[] parts = names.split(" ");
        assertEquals(expected, generate(parts[0], parts[1], null, Set.of()));
    }

    /** La o barrada y la ae con tilde se descomponen en la letra y la marca: el mapeo va despues. */
    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({"Ǿdegaard, aodegaard", "ǿdegaard, aodegaard", "Ǽsir, aaesir", "ﬁgueroa, afigueroa", "Ｐérez, aperez"})
    void theMappingGoesAfterTheDecomposition(String paternal, String expected) {
        assertEquals(expected, generate("Ana", paternal, null, Set.of()));
    }

    // ---------- el largo ------------------------------------------------------------

    @Test
    void aLongPaternal_isCutToFifty_andMakesRoomForTheSuffix() {
        String paternal = "B".repeat(60);
        String fifty = "j" + "b".repeat(49);

        assertEquals(fifty, generate("Juan", paternal, null, Set.of()));
        assertEquals("j" + "b".repeat(48) + "1", generate("Juan", paternal, null, Set.of(fifty)));
        Set<String> nineTaken = new HashSet<>(Set.of(fifty));
        for (int suffix = 1; suffix <= 9; suffix++) {
            nineTaken.add("j" + "b".repeat(48) + suffix);
        }
        assertEquals("j" + "b".repeat(47) + "10", generate("Juan", paternal, null, nineTaken));
    }

    /**
     * Cada candidato de una base larga empieza con el prefijo de lo tomado, tambien los de nueve
     * cifras: con la base entera de prefijo, el segundo homonimo no veria el primero y lo repetiria.
     */
    @Test
    void everyCandidate_startsWithTheTakenPrefix() {
        String base = generator.baseOf("Juan", "B".repeat(60), null, WORKER_ID);
        String prefix = generator.takenPrefixOf(base);

        assertEquals(41, prefix.length());
        for (int suffix : new int[] {1, 9, 10, 99, 999_999_999}) {
            String digits = Integer.toString(suffix);
            String candidate = base.substring(0, UsernameGenerator.MAX_LENGTH - digits.length()) + digits;
            assertTrue(candidate.startsWith(prefix), candidate);
        }
        assertEquals("jperez", generator.takenPrefixOf("jperez"));
    }

    @Test
    void everyGeneratedName_fitsTheLoginPattern() {
        for (String[] worker : new String[][] {
            {"Juan", "Pérez", "Quispe"}, {"A", "O", null}, {"Ana", "O", null}, {"Juan", "B".repeat(100), null},
            {"Иван", "Петров", null}, {"Ǿ", "Ǽ", null}, {"x".repeat(100), "y".repeat(100), "z".repeat(100)},
        }) {
            String generated = generate(worker[0], worker[1], worker[2], Set.of());
            assertTrue(generated.matches(LOGIN_PATTERN), generated);
            assertTrue(generate(worker[0], worker[1], worker[2], Set.of(generated)).matches(LOGIN_PATTERN));
        }
    }

    private String generate(String firstName, String paternal, String maternal, Set<String> taken) {
        return generator.choose(generator.baseOf(firstName, paternal, maternal, WORKER_ID), taken);
    }

    private static Set<String> takenOf(String taken) {
        return taken == null ? Set.of() : Arrays.stream(taken.split(";")).collect(Collectors.toSet());
    }
}
