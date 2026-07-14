package com.scaramutti.tms.quotations;

import com.scaramutti.tms.shared.entity.Quotation;
import com.scaramutti.tms.shared.util.Etag;

import java.time.OffsetDateTime;

/**
 * Helper tipado del ETag / optimistic locking de cotizaciones sobre {@link Quotation}.
 * Delega el formato del ETag y el check del {@code If-Match} en {@link Etag} (fuente de
 * verdad compartida del proyecto): antes el formato vivía DUPLICADO byte-a-byte acá y en
 * {@code WarehouseProductEtag}. Una sola fuente: si cambia el formato, cambia en {@link Etag}.
 *
 * <p>Clase utilitaria sin estado: constructor privado, todo {@code static}.
 */
public final class QuotationEtag {

    private QuotationEtag() {
    }

    /** ETag de la cotización a partir de la entity (su {@code updatedAt} es la "versión"). */
    public static String of(Quotation quotation) {
        return Etag.of(quotation.updatedAt);
    }

    /**
     * Mismo formato a partir del {@code updatedAt} suelto: lo usa el {@code QuotationResource}
     * para el header (solo tiene el {@code QuotationResponse}, no la entity).
     */
    public static String of(OffsetDateTime updatedAt) {
        return Etag.of(updatedAt);
    }

    /**
     * Optimistic locking: compara el header {@code If-Match} contra la versión actual
     * ({@code updatedAt}). Si falta o no coincide → 412 COM-004. Mismo check para el PUT
     * y el PATCH /status.
     */
    public static void verify(String ifMatch, Quotation quotation) {
        Etag.verify(ifMatch, quotation.updatedAt);
    }
}
