package com.scaramutti.tms.auth.service;

import at.favre.lib.crypto.bcrypt.BCrypt;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class PasswordService {

    /** Cost factor BCrypt. 10 es estandar; balance entre seguridad y velocidad. */
    private static final int BCRYPT_COST = 10;

    /**
     * Hash BCrypt precomputado. Se usa para igualar el tiempo de respuesta del
     * /login cuando el usuario no existe (timing attack mitigation).
     */
    private static final String DUMMY_HASH = BCrypt.withDefaults().hashToString(BCRYPT_COST, "dummy-for-timing-protection".toCharArray());

    /** Hashea una password en texto plano usando BCrypt $2a$ (cost 10). */
    public String hash(String plain) {
        return BCrypt.withDefaults().hashToString(BCRYPT_COST, plain.toCharArray());
    }

    /**
     * Verifica una password contra un hash BCrypt. Soporta variantes $2a, $2b y $2y;
     * la app legacy v1 grabo hashes $2b, los nuevos generados aca son $2a.
     */
    public boolean matches(String plain, String hash) {
        return BCrypt.verifyer().verify(plain.toCharArray(), hash).verified;
    }

    /**
     * Ejecuta un BCrypt dummy para consumir el mismo tiempo que una verificacion real.
     * Llamar cuando el usuario NO existe en login, para que un atacante no pueda
     * enumerar usernames midiendo tiempos de respuesta.
     */
    public void runDummyVerify() {
        BCrypt.verifyer().verify("dummy-input".toCharArray(), DUMMY_HASH);
    }
}
