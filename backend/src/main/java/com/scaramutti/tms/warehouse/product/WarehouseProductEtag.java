package com.scaramutti.tms.warehouse.product;

import com.scaramutti.tms.shared.entity.Product;
import com.scaramutti.tms.shared.util.Etag;

import java.time.OffsetDateTime;

/**
 * Helper tipado del ETag / optimistic locking de productos de almacén sobre
 * {@link Product}. Delega el formato del ETag y el check del {@code If-Match} en
 * {@link Etag} (fuente de verdad compartida del proyecto): antes el formato vivía
 * DUPLICADO byte-a-byte acá y en {@code QuotationEtag}. Una sola fuente: si cambia el
 * formato, cambia en {@link Etag}.
 *
 * <p>Clase utilitaria sin estado: constructor privado, todo {@code static}.
 */
public final class WarehouseProductEtag {

    private WarehouseProductEtag() {
    }

    /** ETag del producto a partir de la entity (su {@code updatedAt} es la "versión"). */
    public static String of(Product product) {
        return Etag.of(product.updatedAt);
    }

    /**
     * Mismo formato a partir del {@code updatedAt} suelto: lo usa el
     * {@code WarehouseProductResource} para el header (solo tiene el response, no la entity).
     */
    public static String of(OffsetDateTime updatedAt) {
        return Etag.of(updatedAt);
    }

    /**
     * Optimistic locking: compara el header {@code If-Match} contra la versión actual del
     * recurso ({@code updatedAt}). Si falta o no coincide → 412 COM-004.
     */
    public static void verify(String ifMatch, Product product) {
        Etag.verify(ifMatch, product.updatedAt);
    }
}
