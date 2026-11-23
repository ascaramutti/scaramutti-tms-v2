package com.scaramutti.tms.users.service;

import jakarta.enterprise.context.ApplicationScoped;

import java.security.SecureRandom;

/**
 * Las contrasenas temporales: 12 caracteres al azar de un alfabeto sin los que se confunden al
 * dictarlos (0, O, o, 1, l, I, i).
 */
@ApplicationScoped
public class TemporaryPasswordGenerator {

    static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789";
    static final int LENGTH = 12;

    // El proveedor no bloqueante del sistema: getInstanceStrong() puede trabar el pedido.
    private final SecureRandom random = new SecureRandom();

    public String generate() {
        StringBuilder password = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) {
            password.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return password.toString();
    }
}
