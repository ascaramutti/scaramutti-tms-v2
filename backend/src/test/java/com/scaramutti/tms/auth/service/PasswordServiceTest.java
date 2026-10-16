package com.scaramutti.tms.auth.service;

import at.favre.lib.crypto.bcrypt.BCrypt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Arrays;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests puros (sin Quarkus runtime) para PasswordService.
 * BCrypt es deterministico en validacion pero NO en hashing (salt aleatorio por hash).
 */
class PasswordServiceTest {

    private PasswordService passwordService;

    @BeforeEach
    void setUp() {
        passwordService = new PasswordService();
    }

    @Test
    void hash_returnsNonNullAndDifferentFromPlain() {
        String hash = passwordService.hash("MiPasswordSegura123");

        assertNotNull(hash);
        assertNotEquals("MiPasswordSegura123", hash);
        assertTrue(hash.startsWith("$2"), "BCrypt hashes empiezan con $2");
    }

    @Test
    void hash_producesDifferentHashesForSamePassword() {
        // BCrypt usa salt aleatorio - mismo input deberia dar hashes distintos
        String hash1 = passwordService.hash("MiPasswordSegura123");
        String hash2 = passwordService.hash("MiPasswordSegura123");

        assertNotEquals(hash1, hash2);
    }

    @Test
    void matches_withCorrectPassword_returnsTrue() {
        String hash = passwordService.hash("PasswordCorrecta456");

        assertTrue(passwordService.matches("PasswordCorrecta456", hash));
    }

    @Test
    void matches_withWrongPassword_returnsFalse() {
        String hash = passwordService.hash("PasswordCorrecta456");

        assertFalse(passwordService.matches("PasswordIncorrecta456", hash));
    }

    @Test
    void matches_withEmptyPassword_returnsFalse() {
        String hash = passwordService.hash("PasswordCorrecta456");

        assertFalse(passwordService.matches("", hash));
    }

    @Test
    void matches_isCaseSensitive() {
        String hash = passwordService.hash("Password123");

        assertFalse(passwordService.matches("password123", hash));
        assertFalse(passwordService.matches("PASSWORD123", hash));
    }

    @Test
    void runDummyVerify_executesWithoutThrowing() {
        // El metodo es para timing protection: debe completar sin lanzar
        passwordService.runDummyVerify();
    }

    @Test
    void runDummyVerify_takesNonTrivialTime() {
        // Sanity check: el dummy debe tomar tiempo similar a un BCrypt real (>5ms).
        long start = System.nanoTime();
        passwordService.runDummyVerify();
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertTrue(elapsedMs > 5L, "Dummy verify deberia tomar >5ms, tomó: " + elapsedMs + "ms");
    }

    // ---------- lo que cabe en BCrypt ----------------------------------------

    /** El caracter de ese punto de codigo, repetido. Sin escapes en el fuente: se ven todos los bytes. */
    private static String repeat(int codePoint, int times) {
        return new String(Character.toChars(codePoint)).repeat(times);
    }

    private static final int ENYE = 0xF1;        // 2 bytes en UTF-8, 1 unidad UTF-16
    private static final int KANJI = 0x4E2D;     // 3 bytes, 1 unidad
    private static final int EMOJI = 0x1F600;    // 4 bytes, 2 unidades (un par de surrogates)

    /**
     * El limite son 72 bytes UTF-8, no caracteres ni unidades UTF-16: cada fila de 73 o mas tiene
     * menos de 73 caracteres y unidades, salvo la ASCII.
     */
    static Stream<Arguments> byteBoundary() {
        return Stream.of(
            Arguments.of("ASCII x72", "a".repeat(72), true),
            Arguments.of("ASCII x73", "a".repeat(73), false),
            Arguments.of("enie x36 (72 bytes)", repeat(ENYE, 36), true),
            Arguments.of("enie x37 (74 bytes, 37 caracteres)", repeat(ENYE, 37), false),
            Arguments.of("71 ASCII y una enie (73 bytes, 72 caracteres)", "a".repeat(71) + repeat(ENYE, 1), false),
            Arguments.of("kanji x24 (72 bytes)", repeat(KANJI, 24), true),
            Arguments.of("kanji x25 (75 bytes, 25 caracteres)", repeat(KANJI, 25), false),
            Arguments.of("emoji x18 (72 bytes)", repeat(EMOJI, 18), true),
            Arguments.of("emoji x19 (76 bytes, 38 unidades)", repeat(EMOJI, 19), false));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("byteBoundary")
    void fitsBcrypt_countsUtf8Bytes_upTo72(String name, String password, boolean fits) {
        assertEquals(fits, PasswordService.fitsBcrypt(password));
    }

    static Stream<Arguments> nulAndSurrogates() {
        String high = String.valueOf((char) 0xD800);
        String low = String.valueOf((char) 0xDC00);
        return Stream.of(
            Arguments.of("un nulo en el medio", "Clave" + (char) 0 + "123", false),
            Arguments.of("un nulo al final", "Clave123" + (char) 0, false),
            Arguments.of("un surrogate alto suelto", "Clave" + high + "123", false),
            Arguments.of("un surrogate alto al final", "Clave123" + high, false),
            Arguments.of("un surrogate bajo suelto", "Clave" + low + "123", false),
            Arguments.of("un par al reves", "Clave" + low + high + "123", false),
            Arguments.of("un par bien formado", "Clave" + high + low + "123", true),
            Arguments.of("otro caracter de control, que no es nulo", "Clave" + (char) 9 + "123", true),
            Arguments.of("el ultimo surrogate bajo, suelto", "Clave" + (char) 0xDFFF + "123", false),
            Arguments.of("el caracter justo antes de los surrogates", "Clave" + (char) 0xD7FF + "123", true),
            Arguments.of("el caracter justo despues", "Clave" + (char) 0xE000 + "123", true),
            // Sus 16 bits bajos caen en el rango de los surrogates: no hay que confundirlo con uno.
            Arguments.of("un punto de codigo alto cuyo resto parece surrogate", "Clave" + repeat(0x1D800, 1), true));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("nulAndSurrogates")
    void fitsBcrypt_rejectsNul_andLoneSurrogates(String name, String password, boolean fits) {
        assertEquals(fits, PasswordService.fitsBcrypt(password));
    }

    @Test
    void hash_withAPasswordThatDoesNotFit_throwsWithTheReason() {
        IllegalArgumentException tooLong = assertThrows(IllegalArgumentException.class,
            () -> passwordService.hash("a".repeat(73)));
        IllegalArgumentException withNul = assertThrows(IllegalArgumentException.class,
            () -> passwordService.hash("Clave123" + (char) 0));

        assertTrue(tooLong.getMessage().contains("72 bytes UTF-8"), tooLong.getMessage());
        assertEquals(tooLong.getMessage(), withNul.getMessage());
    }

    @Test
    void hash_andMatches_workWith72BytesOfEmoji() {
        String password = repeat(EMOJI, 18);

        assertTrue(passwordService.matches(password, passwordService.hash(password)));
    }

    @Test
    void matches_withAPasswordOver72Bytes_returnsFalse_withoutThrowing() {
        String hash = passwordService.hash("a".repeat(72));

        assertFalse(passwordService.matches("a".repeat(80), hash));
        assertFalse(passwordService.matches(repeat(ENYE, 40), hash));
    }

    /**
     * Sin la regla, la libreria da por buena una clave de 71 bytes con un nulo al final, y la de un
     * surrogate suelto como si tuviera un signo de pregunta.
     */
    @Test
    void matches_withANulOrALoneSurrogate_returnsFalse_evenWhereTheLibrarySaysTrue() {
        String hash = passwordService.hash("a".repeat(71));
        String withQuestionMark = passwordService.hash("Clave12?");

        assertTrue(BCrypt.verifyer().verify(("a".repeat(71) + (char) 0).toCharArray(), hash).verified);
        assertTrue(BCrypt.verifyer().verify(("Clave12" + (char) 0xD800).toCharArray(), withQuestionMark).verified);

        assertFalse(passwordService.matches("a".repeat(71) + (char) 0, hash));
        assertFalse(passwordService.matches("Clave12" + (char) 0xD800, withQuestionMark));
    }

    /**
     * La clave que no cabe tarda lo que una verificacion real: asi, en un camino sin la regla del
     * pedido, el tiempo no depende de que la cuenta exista. El umbral es un decimo porque con la
     * maquina cargada la relacion baja; sin la verificacion es casi cero.
     */
    @Test
    void matches_withAPasswordThatDoesNotFit_takesAsLongAsARealVerify() {
        String hash = passwordService.hash("Clave123");
        String tooLong = "a".repeat(80);
        passwordService.matches("Clave124", hash);
        passwordService.matches(tooLong, hash);

        long real = medianNanos(() -> passwordService.matches("Clave124", hash));
        long rejected = medianNanos(() -> passwordService.matches(tooLong, hash));

        assertTrue(rejected * 10 > real,
            "la clave larga tardo " + rejected / 1_000_000 + " ms y una real " + real / 1_000_000 + " ms");
    }

    private static long medianNanos(Runnable action) {
        long[] samples = new long[5];
        for (int i = 0; i < samples.length; i++) {
            long start = System.nanoTime();
            action.run();
            samples[i] = System.nanoTime() - start;
        }
        Arrays.sort(samples);
        return samples[samples.length / 2];
    }
}
