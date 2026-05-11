package com.scaramutti.tms.auth.service;

import io.quarkus.elytron.security.common.BcryptUtil;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class PasswordService {

    /**
     * Hash BCrypt precomputado. Se usa para igualar el tiempo de respuesta del
     * /login cuando el usuario no existe (timing attack mitigation).
     */
    private static final String DUMMY_HASH = BcryptUtil.bcryptHash("dummy-for-timing-protection");

    /** Hashea una password en texto plano usando BCrypt (cost 10 por default). */
    public String hash(String plain) {
        return BcryptUtil.bcryptHash(plain);
    }

    /** Verifica una password en texto plano contra un hash BCrypt almacenado. */
    public boolean matches(String plain, String hash) {
        return BcryptUtil.matches(plain, hash);
    }

    /**
     * Ejecuta un BCrypt dummy para consumir el mismo tiempo que una verificacion real.
     * Llamar cuando el usuario NO existe en login, para que un atacante no pueda
     * enumerar usernames midiendo tiempos de respuesta.
     */
    public void runDummyVerify() {
        BcryptUtil.matches("dummy-input", DUMMY_HASH);
    }
}
