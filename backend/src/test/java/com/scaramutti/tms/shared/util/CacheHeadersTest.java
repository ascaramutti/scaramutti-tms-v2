package com.scaramutti.tms.shared.util;

import org.jboss.resteasy.reactive.RestResponse;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit puro de {@link CacheHeaders}, sin Quarkus: es una API compartida, así que su contrato se
 * fija acá y no a través de un endpoint. El caso es la forma más cargada, la del alta: quien llama
 * pone el estado, el Location y el ETag, y el helper no pisa ninguno. Se compara la lista entera de
 * cada nombre: una cabecera repetida, con otro valor (el {@code no-transform} de un
 * {@code CacheControl} nuevo), perdida o de más, falla.
 */
class CacheHeadersTest {

    @Test
    void notStored_addsEachCacheHeaderOnce_keepingWhatTheCallerSet() {
        RestResponse<String> response = CacheHeaders.notStored(
            RestResponse.ResponseBuilder.create(RestResponse.Status.CREATED, "cuerpo")
                .location(URI.create("services/7"))
                .header("ETag", "\"v\""));

        assertEquals(201, response.getStatus());
        assertEquals("cuerpo", response.getEntity());
        assertEquals(
            Map.of(
                "Cache-Control", List.of("no-store"),
                "Vary", List.of("Authorization"),
                "ETag", List.of("\"v\""),
                "Location", List.of("services/7")),
            Map.copyOf(response.getStringHeaders()));
    }
}
