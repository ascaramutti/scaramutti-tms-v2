package com.scaramutti.tms.auth.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
}
