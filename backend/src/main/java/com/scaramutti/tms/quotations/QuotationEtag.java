package com.scaramutti.tms.quotations;

import com.scaramutti.tms.shared.entity.Quotation;
import com.scaramutti.tms.shared.exception.CommonError;

import java.time.OffsetDateTime;

/**
 * Helper unico del ETag / optimistic locking de cotizaciones. Centraliza el formato
 * del ETag (el {@code updatedAt} entre comillas) y el check del {@code If-Match}, que
 * antes vivian DUPLICADOS byte-a-byte en {@link com.scaramutti.tms.quotations.service.UpdateQuotationService}
 * y {@link com.scaramutti.tms.quotations.service.ChangeQuotationStatusService}.
 *
 * <p>El formato es el mismo que sirve el {@code QuotationResource} en el header
 * {@code ETag} de los GET/POST/PUT/PATCH: {@code "\"" + updatedAt + "\""}. Una sola
 * fuente de verdad — si cambia el formato, cambia aca y en ambos lados a la vez.
 *
 * <p>Clase utilitaria sin estado: constructor privado, todo {@code static}.
 */
public final class QuotationEtag {

    private QuotationEtag() {
    }

    /**
     * Arma el ETag de la cotizacion: su {@code updatedAt} (la "version") entre comillas
     * dobles, formato weak/opaco que tambien sirve el resource en el header.
     */
    public static String of(Quotation quotation) {
        return of(quotation.updatedAt);
    }

    /**
     * Mismo formato de ETag a partir del {@code updatedAt} suelto — lo usa el
     * {@code QuotationResource} para el header (solo tiene el {@code QuotationResponse},
     * no la entity) y lo reusa {@link #of(Quotation)}. Asi el header del resource y el
     * check del {@code If-Match} comparten EXACTAMENTE el mismo formato.
     */
    public static String of(OffsetDateTime updatedAt) {
        return "\"" + updatedAt.toString() + "\"";
    }

    /**
     * Optimistic locking: compara el header {@code If-Match} contra la version actual
     * del recurso ({@code updatedAt}). Si falta o no coincide → 412 COM-004 (otro usuario
     * edito primero, hay que recargar). Mismo check para el PUT y el PATCH /status.
     */
    public static void verify(String ifMatch, Quotation quotation) {
        if (ifMatch == null || !of(quotation).equals(ifMatch)) {
            throw CommonError.PRECONDITION_FAILED.toException();
        }
    }
}
