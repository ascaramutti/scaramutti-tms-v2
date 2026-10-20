package com.scaramutti.tms.support;

import io.restassured.response.Response;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Cabeceras contadas. {@code .header(nombre, valor)} de RestAssured mira solo el último valor de
 * un nombre repetido, así que una cabecera mandada dos veces pasa en verde: acá se compara la
 * lista entera de valores, tal cual, de un nombre que se busca sin distinguir mayúsculas.
 */
public final class ResponseHeaders {

    private ResponseHeaders() {
    }

    /** La cabecera sale una sola vez, con ese valor. */
    public static void assertSentOnce(Response response, String name, String value) {
        assertEquals(List.of(value), response.getHeaders().getValues(name), name);
    }

    /** Ninguna de estas cabeceras sale. */
    public static void assertNotSent(Response response, String... names) {
        for (String name : names) {
            assertEquals(List.of(), response.getHeaders().getValues(name), name);
        }
    }
}
