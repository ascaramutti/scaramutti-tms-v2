package com.scaramutti.tms.shared;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * Endpoint de smoke-test para validar que el backend arranca y atiende
 * peticiones. Será removido cuando se implemente el primer módulo real.
 */
@Path("/hello")
public class HelloResource {

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> hello() {
        return Map.of(
            "message", "Scaramutti TMS Backend is running",
            "timestamp", OffsetDateTime.now().toString()
        );
    }
}
