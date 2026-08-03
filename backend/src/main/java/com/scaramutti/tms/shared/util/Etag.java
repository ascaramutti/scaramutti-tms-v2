package com.scaramutti.tms.shared.util;

import com.scaramutti.tms.shared.exception.CommonError;

import java.time.OffsetDateTime;

/**
 * Formato único del ETag / optimistic locking del proyecto: el {@code updatedAt} (la
 * "versión" del recurso) entre comillas dobles. NO es opaco ni weak: es el valor
 * de esa fecha, asi que el cliente puede reconstruirlo, pero debe reenviar el header
 * TAL CUAL (las comillas son parte del valor). Fuente de verdad
 * compartida por todos los recursos versionados: si cambia el formato, cambia acá y en
 * todos los lados a la vez.
 *
 * <p>Extraído al aparecer la 3ra copia byte-a-byte del formato (cotizaciones, productos
 * de almacén y entradas de almacén), aplicando la lección del refactor {@code DateUtils}
 * (D-16: extraer a {@code shared/} cuando la 3ra ocurrencia dispara la regla, no dejar
 * copias autodeclaradas "mismo criterio que X" que puedan divergir). Es especialmente
 * sensible porque el formato sostiene el optimistic locking (histórico: bug D-12, el ETag
 * inestable por nanos vs micros).
 *
 * <p>Los helpers tipados por entity ({@code QuotationEtag}, {@code WarehouseProductEtag})
 * delegan acá; el resto de los resources lo usan directo con el {@code updatedAt} del
 * response.
 *
 * <p>Clase utilitaria sin estado: constructor privado, todo {@code static}.
 */
public final class Etag {

    private Etag() {
    }

    /** ETag a partir del {@code updatedAt} (la "versión"): entre comillas dobles. */
    public static String of(OffsetDateTime version) {
        return "\"" + version.toString() + "\"";
    }

    /**
     * Optimistic locking: compara el header {@code If-Match} contra la versión actual del
     * recurso ({@code updatedAt}). Si falta o no coincide → 412 COM-004 (otro usuario editó
     * primero, hay que recargar).
     */
    public static void verify(String ifMatch, OffsetDateTime current) {
        if (ifMatch == null || !of(current).equals(ifMatch)) {
            throw CommonError.PRECONDITION_FAILED.toException();
        }
    }
}
