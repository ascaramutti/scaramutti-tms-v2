package com.scaramutti.tms.shared.util;

import org.jboss.resteasy.reactive.RestResponse;

/**
 * Cuerpos que no deben sobrevivir a la sesión: {@code no-store}, y {@code Vary} por si un día se
 * relaja el primero. Toma el builder para que quien llama sume el ETag o el Location. Literales:
 * un {@code CacheControl} nuevo trae {@code no-transform}. Va en el éxito y no en un filtro, que
 * marcaría también los errores.
 */
public final class CacheHeaders {

    private CacheHeaders() {
    }

    public static <T> RestResponse<T> notStored(RestResponse.ResponseBuilder<T> builder) {
        return builder
            .header("Cache-Control", "no-store")
            .header("Vary", "Authorization")
            .build();
    }
}
