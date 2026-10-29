package com.scaramutti.tms.support;

import io.restassured.response.Response;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.scaramutti.tms.support.TestAuth.adminToken;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Los apellidos y el nombre completo de una ficha, para afirmar que una escritura responde lo
 * que da el GET siguiente. Cada clave se exige presente: un materno nulo y uno ausente darian el
 * mismo {@code path}, y el contrato promete la clave.
 */
public final class WorkerDetailNames {

    private static final List<String> KEYS =
        List.of("paternalLastName", "maternalLastName", "fullName");

    private WorkerDetailNames() {
    }

    /** La respuesta trae esos tres valores, y el GET siguiente tambien. */
    public static void assertSameAsTheNextGet(Response written, int workerId,
            String paternalLastName, String maternalLastName, String fullName) {
        Map<String, Object> wanted = new LinkedHashMap<>();
        wanted.put("paternalLastName", paternalLastName);
        wanted.put("maternalLastName", maternalLastName);
        wanted.put("fullName", fullName);
        assertEquals(wanted, namesOf(written), "la respuesta de la escritura");

        Response read = given().header("Authorization", "Bearer " + adminToken())
            .when().get("/workers/" + workerId);
        assertEquals(200, read.statusCode());
        assertEquals(wanted, namesOf(read), "el GET siguiente");
    }

    private static Map<String, Object> namesOf(Response response) {
        Map<String, Object> body = response.jsonPath().getMap("$");
        Map<String, Object> names = new LinkedHashMap<>();
        for (String key : KEYS) {
            assertTrue(body.containsKey(key), "la ficha no trae la clave " + key);
            names.put(key, body.get(key));
        }
        assertFalse(body.containsKey("lastName"), "la ficha ya no trae el apellido de antes en un solo campo");
        return names;
    }
}
