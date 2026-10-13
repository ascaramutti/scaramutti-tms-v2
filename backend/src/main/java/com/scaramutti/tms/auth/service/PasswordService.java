package com.scaramutti.tms.auth.service;

import at.favre.lib.crypto.bcrypt.BCrypt;
import jakarta.enterprise.context.ApplicationScoped;

import java.nio.charset.StandardCharsets;

@ApplicationScoped
public class PasswordService {

    /** Cost factor BCrypt. 10 es estandar; balance entre seguridad y velocidad. */
    private static final int BCRYPT_COST = 10;

    /**
     * Lo mas que procesa BCrypt, en bytes UTF-8: la libreria lanza una excepcion desde 73, al
     * hashear y al verificar. Una letra con tilde ocupa 2 bytes y un emoji, 3 o mas.
     */
    public static final int MAX_PASSWORD_BYTES = 72;

    /**
     * Hash BCrypt precomputado. Se usa para igualar el tiempo de respuesta del
     * /login cuando el usuario no existe, y el de matches cuando la clave no cabe.
     */
    private static final String DUMMY_HASH = BCrypt.withDefaults().hashToString(BCRYPT_COST, "dummy-for-timing-protection".toCharArray());

    /**
     * Si la clave puede ir a BCrypt tal cual: hasta 72 bytes UTF-8, sin caracteres nulos ni
     * surrogates sueltos. Esos dos no hacen fallar a la libreria, pero vuelven iguales claves
     * distintas: una de 71 bytes verifica con ella misma mas un nulo, y un surrogate suelto se
     * codifica como "?".
     */
    public static boolean fitsBcrypt(String plain) {
        return plain.codePoints().noneMatch(PasswordService::isNulOrLoneSurrogate)
            && plain.getBytes(StandardCharsets.UTF_8).length <= MAX_PASSWORD_BYTES;
    }

    // codePoints() junta los pares bien formados: lo que cae en el rango de los surrogates esta suelto.
    private static boolean isNulOrLoneSurrogate(int codePoint) {
        return codePoint == 0 || (codePoint >= Character.MIN_SURROGATE && codePoint <= Character.MAX_SURROGATE);
    }

    /**
     * Hashea una password en texto plano usando BCrypt $2a$ (cost 10). Una clave que no cabe
     * (ver {@link #fitsBcrypt}) es un error de quien llama, porque el pedido la rechaza antes:
     * lanza {@link IllegalArgumentException} con el motivo.
     */
    public String hash(String plain) {
        if (!fitsBcrypt(plain)) {
            throw new IllegalArgumentException("La contrasena no cabe en BCrypt: hasta " + MAX_PASSWORD_BYTES
                + " bytes UTF-8, sin caracteres nulos ni surrogates sueltos");
        }
        return BCrypt.withDefaults().hashToString(BCRYPT_COST, plain.toCharArray());
    }

    /**
     * Verifica una password contra un hash BCrypt. Soporta variantes $2a, $2b y $2y;
     * la app legacy v1 grabo hashes $2b, los nuevos generados aca son $2a.
     *
     * <p>Una clave que no cabe (ver {@link #fitsBcrypt}) no verifica: devuelve false despues de
     * la verificacion ficticia, para no lanzar y tardar lo mismo que una real. El pedido ya la
     * rechaza antes; esto cubre a quien llegue sin pasar por el.
     */
    public boolean matches(String plain, String hash) {
        if (!fitsBcrypt(plain)) {
            runDummyVerify();
            return false;
        }
        return BCrypt.verifyer().verify(plain.toCharArray(), hash).verified;
    }

    /**
     * Ejecuta un BCrypt dummy para consumir el mismo tiempo que una verificacion real. Corre
     * cuando el usuario NO existe en login y, dentro de matches, cuando la clave no cabe: asi un
     * atacante no puede enumerar usernames midiendo tiempos de respuesta.
     */
    public void runDummyVerify() {
        BCrypt.verifyer().verify("dummy-input".toCharArray(), DUMMY_HASH);
    }
}
