package com.scaramutti.tms.workers.dto;

import java.text.Normalizer;

/**
 * La regla del nombre y el apellido de un trabajador, compartida por el alta y la edicion.
 *
 * <p>Se pasa a NFC en el constructor del record, antes de que corra Bean Validation: una tilde
 * que llega como letra mas marca combinante no es letra y la regla la rechazaria. El recorte
 * sigue en el mapper, despues de validar, como el del documento, la licencia y el motivo.
 */
public final class PersonNames {

    /**
     * Al menos una letra latina, mas espacios, apostrofo y guion. Letra latina es letra de script
     * latino: quedan afuera los numeros romanos, los rellenos invisibles y las letras de otros
     * alfabetos que se parecen a las nuestras. Antes de la primera letra solo van los otros tres,
     * asi la expresion no retrocede. Una marca que NFC no compone queda suelta y se rechaza.
     */
    public static final String PATTERN = "^[' -]*[\\p{IsLatin}&&\\p{L}][[\\p{IsLatin}&&\\p{L}]' -]*$";

    public static final String MESSAGE = "Solo letras, espacios, apóstrofo o guion.";

    private PersonNames() {
    }

    /**
     * Pasado este largo no se normaliza: corre antes de {@code @Size}, y reordenar miles de marcas
     * combinantes cuesta tiempo cuadratico. Componer junta a lo sumo cuatro caracteres en uno, asi
     * que nada que normalizado quepa en el tope de 100 llega con mas de 400; lo demas lo rechaza
     * {@code @Size} tal como llego.
     */
    static final int MAX_NORMALIZED_LENGTH = 400;

    public static String toNfc(String value) {
        if (value == null || value.length() > MAX_NORMALIZED_LENGTH) {
            return value;
        }
        return Normalizer.normalize(value, Normalizer.Form.NFC);
    }
}
