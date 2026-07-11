package com.scaramutti.tms.warehouse.product;

import com.scaramutti.tms.shared.entity.Product;
import com.scaramutti.tms.shared.exception.CommonError;

import java.time.OffsetDateTime;

/**
 * Helper unico del ETag / optimistic locking de productos de almacen. Calco de
 * {@link com.scaramutti.tms.quotations.QuotationEtag} tipado sobre {@link Product}.
 * Centraliza el formato del ETag (el {@code updatedAt} entre comillas) y el check
 * del {@code If-Match} para que el header del GET/PUT y el {@code verify} del PUT
 * compartan EXACTAMENTE el mismo formato.
 *
 * <p>Solo 2 usos (resource + service): no se generaliza a {@code shared/} — 2
 * usos no es trigger de extracción (ver deuda quotations equivalente).
 *
 * <p>Clase utilitaria sin estado: constructor privado, todo {@code static}.
 */
public final class WarehouseProductEtag {

    private WarehouseProductEtag() {
    }

    /**
     * Arma el ETag del producto: su {@code updatedAt} (la "version") entre comillas
     * dobles, formato weak/opaco que tambien sirve el resource en el header.
     */
    public static String of(Product product) {
        return of(product.updatedAt);
    }

    /**
     * Mismo formato de ETag a partir del {@code updatedAt} suelto — lo usa el
     * {@code WarehouseProductResource} para el header (solo tiene el
     * {@code WarehouseProductResponse}, no la entity) y lo reusa {@link #of(Product)}.
     * Asi el header del resource y el check del {@code If-Match} comparten
     * EXACTAMENTE el mismo formato.
     */
    public static String of(OffsetDateTime updatedAt) {
        return "\"" + updatedAt.toString() + "\"";
    }

    /**
     * Optimistic locking: compara el header {@code If-Match} contra la version
     * actual del recurso ({@code updatedAt}). Si falta o no coincide → 412 COM-004
     * (otro usuario edito primero, hay que recargar).
     */
    public static void verify(String ifMatch, Product product) {
        if (ifMatch == null || !of(product).equals(ifMatch)) {
            throw CommonError.PRECONDITION_FAILED.toException();
        }
    }
}
