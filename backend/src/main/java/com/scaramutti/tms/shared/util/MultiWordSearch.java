package com.scaramutti.tms.shared.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Búsqueda multi-palabra (RN-WH14): {@code q} se tokeniza por espacios y cada
 * palabra debe matchear en ALGÚN campo — un AND de ORs, no un OR de la cadena
 * completa. Así "repuestos diesel" matchea un registro con "Repuestos" en un
 * campo y "diesel" en otro, sin exigir que ambas estén en el mismo campo.
 *
 * Extraído de {@code SupplierRepository} al aparecer el 2° caller
 * ({@code ProductRepository}). El repo llamante arma su
 * SELECT/ORDER/LIMIT; este helper solo produce las condiciones tokenizadas del
 * WHERE y rellena los named params (mismo criterio side-effect que el
 * {@code buildWhere} de cada repo).
 */
public final class MultiWordSearch {

    private MultiWordSearch() {
        // utility class
    }

    /**
     * Tokeniza {@code q} por espacios, descartando tokens vacíos (espacios
     * múltiples o al borde no generan condiciones vacías que rompan el AND).
     * Público y estático para poder testearlo sin levantar Postgres.
     */
    public static String[] tokenize(String q) {
        String[] rawTokens = q.trim().split("\\s+");
        List<String> tokens = new ArrayList<>();
        for (String token : rawTokens) {
            if (!token.isEmpty()) {
                tokens.add(token);
            }
        }
        return tokens.toArray(new String[0]);
    }

    /**
     * Devuelve una condición SQL por cada token de {@code q}, todas pensadas
     * para unirse por AND: cada token produce
     * {@code (col1 ILIKE :prefixN ESCAPE '\' OR col2 ILIKE :prefixN ...)} y
     * agrega su named param {@code prefixN = %<escapado>%} al mapa. Los
     * metacaracteres de LIKE ({@code \ % _}) se escapan
     * ({@link StringUtils#escapeLikeWildcards}) para que el input se trate como
     * literal.
     *
     * @param q           cadena de búsqueda ya trimmed/normalizada por el ResourceMapper
     * @param columns     columnas (o expresiones SQL) donde cada palabra puede matchear
     * @param paramPrefix prefijo del named param (evita colisión con otros params del WHERE)
     * @param params      mapa de named params del repo (side-effect: se rellena acá)
     */
    public static List<String> conditions(
            String q, List<String> columns, String paramPrefix, Map<String, Object> params) {
        List<String> conditions = new ArrayList<>();
        String[] tokens = tokenize(q);
        for (int i = 0; i < tokens.length; i++) {
            String paramName = paramPrefix + i;
            List<String> ors = new ArrayList<>();
            for (String column : columns) {
                ors.add(column + " ILIKE :" + paramName + " ESCAPE '\\'");
            }
            conditions.add("(" + String.join(" OR ", ors) + ")");
            params.put(paramName, "%" + StringUtils.escapeLikeWildcards(tokens[i]) + "%");
        }
        return conditions;
    }
}
