package com.scaramutti.tms.users.service;

import jakarta.enterprise.context.ApplicationScoped;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * El nombre de usuario de una cuenta nueva: la inicial del nombre y el apellido paterno entero, y si
 * esta tomado, el primer sufijo libre. No lee la base: lo tomado lo trae quien llama, leido con
 * {@link #takenPrefixOf}.
 */
@ApplicationScoped
public class UsernameGenerator {

    static final int MAX_LENGTH = 50;
    static final int MIN_LENGTH = 3;
    static final String FALLBACK_PREFIX = "usuario";

    /** Agotar nueve cifras pide mil millones de homonimos: la lectura de lo tomado no mira mas alla. */
    static final int MAX_SUFFIX_DIGITS = 9;

    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern OUTSIDE_THE_ALPHABET = Pattern.compile("[^a-z0-9]+");

    // Letras latinas que la descomposicion no separa en base y marca.
    private static final Map<Character, String> UNDECOMPOSED_LETTERS = Map.ofEntries(
        Map.entry('ß', "ss"), Map.entry('æ', "ae"), Map.entry('œ', "oe"), Map.entry('ø', "o"),
        Map.entry('ł', "l"), Map.entry('đ', "d"), Map.entry('þ', "th"), Map.entry('ð', "d"),
        Map.entry('ħ', "h"), Map.entry('ŧ', "t"), Map.entry('ı', "i"), Map.entry('ĸ', "k"),
        Map.entry('ŋ', "n"));

    /** Sin sufijo ni recorte. Si es corta, el nombre completo; si sigue, {@code usuario} y el id del trabajador. */
    public String baseOf(String firstName, String paternalLastName, String maternalLastName, int workerId) {
        String first = normalized(firstName);
        String paternal = normalized(paternalLastName);
        String base = (first.isEmpty() ? "" : first.substring(0, 1)) + paternal;
        if (base.length() >= MIN_LENGTH) {
            return base;
        }
        String fullName = first + paternal + normalized(maternalLastName);
        return fullName.length() >= MIN_LENGTH ? fullName : FALLBACK_PREFIX + workerId;
    }

    /**
     * Lo que comparten todos los candidatos de esa base. Con la base entera, una larga no veria los
     * candidatos con sufijo, que la recortan, y repetiria uno ya tomado.
     */
    public String takenPrefixOf(String base) {
        return truncated(base, MAX_LENGTH - MAX_SUFFIX_DIGITS);
    }

    /** El primer candidato libre: la base, y despues la base con 1, 2... recortada para que entre. */
    public String choose(String base, Set<String> taken) {
        String candidate = truncated(base, MAX_LENGTH);
        for (int suffix = 1; taken.contains(candidate); suffix++) {
            String digits = Integer.toString(suffix);
            candidate = truncated(base, MAX_LENGTH - digits.length()) + digits;
        }
        return candidate;
    }

    /** El mapeo va despues de descomponer: antes, una letra con marca, como la o barrada con tilde, se pierde. */
    static String normalized(String part) {
        if (part == null) {
            return "";
        }
        String withoutMarks = MARKS.matcher(Normalizer.normalize(part, Normalizer.Form.NFKD)).replaceAll("");
        String lowercase = withoutMarks.toLowerCase(Locale.ROOT);
        StringBuilder mapped = new StringBuilder(lowercase.length());
        for (char letter : lowercase.toCharArray()) {
            String replacement = UNDECOMPOSED_LETTERS.get(letter);
            if (replacement == null) {
                mapped.append(letter);
            } else {
                mapped.append(replacement);
            }
        }
        return OUTSIDE_THE_ALPHABET.matcher(mapped).replaceAll("");
    }

    private static String truncated(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
