package com.scaramutti.tms.quotations.util;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Monto en letras al estilo de los comprobantes peruanos (es-PE). Port de
 * {@code frontend/.../montoEnLetras.ts}: el front lo usa en pantalla, el backend en el PDF
 * (que genera el backend). Ej: {@code amountToWords(new BigDecimal("4770"), "USD")} ->
 * "Cuatro mil setecientos setenta con 00/100 dólares americanos".
 *
 * <p>Deuda conocida: duplica la logica del TS. Mantener en sync; si se vuelve a divergir,
 * considerar un unico origen. Escala larga es-PE: 10^9 = "mil millones", 10^12 = "un billón".
 */
public final class AmountToWords {

    private AmountToWords() {}

    private static final String[] UNIDADES =
        {"cero", "uno", "dos", "tres", "cuatro", "cinco", "seis", "siete", "ocho", "nueve"};
    private static final String[] DIEZ_QUINCE = {"diez", "once", "doce", "trece", "catorce", "quince"};
    private static final String[] DIECISEIS_DIECINUEVE = {"dieciséis", "diecisiete", "dieciocho", "diecinueve"};
    private static final String[] VEINTIDOS_VEINTINUEVE = {
        "veintidós", "veintitrés", "veinticuatro", "veinticinco",
        "veintiséis", "veintisiete", "veintiocho", "veintinueve"
    };
    private static final String[] DECENAS =
        {"", "", "veinte", "treinta", "cuarenta", "cincuenta", "sesenta", "setenta", "ochenta", "noventa"};
    private static final String[] CENTENAS = {
        "", "ciento", "doscientos", "trescientos", "cuatrocientos",
        "quinientos", "seiscientos", "setecientos", "ochocientos", "novecientos"
    };

    /** 0-99 a letras. {@code apocopar}: "uno" -> "un" (antes de "mil"/"millones"). */
    private static String dosDigitos(int n, boolean apocopar) {
        if (n == 0) return "";
        if (n < 10) return n == 1 ? (apocopar ? "un" : "uno") : UNIDADES[n];
        if (n < 16) return DIEZ_QUINCE[n - 10];
        if (n < 20) return DIECISEIS_DIECINUEVE[n - 16];
        if (n == 20) return "veinte";
        if (n == 21) return apocopar ? "veintiún" : "veintiuno";
        if (n < 30) return VEINTIDOS_VEINTINUEVE[n - 22];
        String decena = DECENAS[n / 10];
        int unidad = n % 10;
        if (unidad == 0) return decena;
        return decena + " y " + (unidad == 1 ? (apocopar ? "un" : "uno") : UNIDADES[unidad]);
    }

    /** 0-999 a letras. */
    private static String tresDigitos(int n, boolean apocopar) {
        if (n == 0) return "";
        if (n == 100) return "cien";
        String centena = CENTENAS[n / 100];
        return join(centena, dosDigitos(n % 100, apocopar));
    }

    /** 0-999.999 (miles + cientos) a letras. */
    private static String periodoALetras(int n, boolean apocopar) {
        int miles = n / 1000;
        int cientos = n % 1000;
        StringBuilder sb = new StringBuilder();
        if (miles > 0) {
            sb.append(miles == 1 ? "mil" : tresDigitos(miles, true) + " mil");
        }
        if (cientos > 0) {
            appendPart(sb, tresDigitos(cientos, apocopar));
        }
        return sb.toString();
    }

    /** Parte entera por periodos (billon / millon / unidades), cada uno 0-999.999. */
    private static String enteroALetras(long n) {
        if (n == 0) return "cero";
        long billones = n / 1_000_000_000_000L;
        long restoBillon = n % 1_000_000_000_000L;
        long millones = restoBillon / 1_000_000L;
        int resto = (int) (restoBillon % 1_000_000L);
        StringBuilder sb = new StringBuilder();
        if (billones > 0) {
            appendPart(sb, billones == 1 ? "un billón" : periodoALetras((int) billones, true) + " billones");
        }
        if (millones > 0) {
            appendPart(sb, millones == 1 ? "un millón" : periodoALetras((int) millones, true) + " millones");
        }
        if (resto > 0) {
            appendPart(sb, periodoALetras(resto, false));
        }
        return sb.toString();
    }

    /** Nombre de la moneda en plural (es-PE). */
    private static String nombreMoneda(String currencyCode) {
        return switch (currencyCode == null ? "" : currencyCode) {
            case "USD" -> "dólares americanos";
            case "PEN" -> "soles";
            default -> currencyCode == null ? "" : currencyCode;
        };
    }

    /**
     * Monto en letras con los centavos como fracción /100. Negativos/null degradan a 0.
     * Soporta enteros hasta ~9·10^15 (holgado sobre los topes del schema).
     */
    public static String amountToWords(BigDecimal amount, String currencyCode) {
        BigDecimal safe = (amount != null && amount.signum() >= 0) ? amount : BigDecimal.ZERO;
        BigDecimal rounded = safe.setScale(2, RoundingMode.HALF_UP);
        long entero = rounded.longValue();
        int centavos = rounded.subtract(BigDecimal.valueOf(entero)).movePointRight(2).intValueExact();
        String letras = enteroALetras(entero);
        String fraccion = String.format("%02d", centavos);
        String frase = letras + " con " + fraccion + "/100 " + nombreMoneda(currencyCode);
        return Character.toUpperCase(frase.charAt(0)) + frase.substring(1);
    }

    private static String join(String a, String b) {
        if (a.isEmpty()) return b;
        if (b.isEmpty()) return a;
        return a + " " + b;
    }

    private static void appendPart(StringBuilder sb, String part) {
        if (part.isEmpty()) return;
        if (sb.length() > 0) sb.append(" ");
        sb.append(part);
    }
}
