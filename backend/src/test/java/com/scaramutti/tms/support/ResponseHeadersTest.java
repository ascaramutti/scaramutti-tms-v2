package com.scaramutti.tms.support;

import io.restassured.builder.ResponseBuilder;
import io.restassured.http.Header;
import io.restassured.http.Headers;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;
import org.opentest4j.AssertionFailedError;

import static com.scaramutti.tms.support.ResponseHeaders.assertNotSent;
import static com.scaramutti.tms.support.ResponseHeaders.assertSentOnce;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Lo que los tests de cabeceras dan por hecho de RestAssured: que la lista de un nombre trae cada
 * línea, también las escritas con otra caja. Si una versión nueva lo cambiara, esos tests quedarían
 * en verde sin ver una cabecera doble.
 */
class ResponseHeadersTest {

    @Test
    void assertSentOnce_withTheSameHeaderTwiceInAnotherCase_fails() {
        Response response = responseWith(new Header("Vary", "Authorization"), new Header("vary", "Authorization"));

        assertThrows(AssertionFailedError.class, () -> assertSentOnce(response, "Vary", "Authorization"));
    }

    @Test
    void assertSentOnce_withOneLineOfThatValue_passes() {
        assertSentOnce(responseWith(new Header("vary", "Authorization")), "Vary", "Authorization");
    }

    @Test
    void assertSentOnce_withAnotherValue_fails() {
        Response response = responseWith(new Header("Cache-Control", "no-store, no-transform"));

        assertThrows(AssertionFailedError.class, () -> assertSentOnce(response, "Cache-Control", "no-store"));
    }

    @Test
    void assertNotSent_withTheHeaderInAnotherCase_fails() {
        Response response = responseWith(new Header("etag", "\"v\""));

        assertThrows(AssertionFailedError.class, () -> assertNotSent(response, "Location", "ETag"));
    }

    private static Response responseWith(Header... headers) {
        return new ResponseBuilder().setStatusCode(200).setHeaders(new Headers(headers)).setBody("").build();
    }
}
