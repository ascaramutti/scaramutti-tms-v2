package com.scaramutti.tms.shared.exception;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Como se serializa el cuerpo de error, incluidos los miembros de extension de RFC 7807.
 *
 * <p>Existe porque el aplanado de las extensiones NO es una propiedad del codigo que se lea a
 * simple vista: depende de que {@code @JsonAnyGetter} y {@code @JsonIgnore} convivan sobre el
 * mismo componente del record. Si algun dia esa combinacion deja de funcionar, el sintoma seria
 * un cuerpo con un objeto {@code extensions} anidado (o directamente sin los datos), y ningun
 * test de endpoint lo diria con claridad: dirian "falta el campo" sin explicar por que.
 *
 * <p>Y sobre todo: este camino lo recorren TODOS los errores de la aplicacion, no solo los que
 * traen extensiones. El caso del mapa vacio es el que garantiza que agregar el mecanismo no
 * cambio ni un byte de los cuerpos que ya estaban en produccion.
 */
class ProblemSerializationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void problemWithoutExtensions_serializesExactlyAsBefore() throws Exception {
        Problem problem = Problem.of(404, "Resource not found", "No existe", "OPS-005", "/api/v1/services/9");

        JsonNode body = objectMapper.readTree(objectMapper.writeValueAsString(problem));

        assertEquals("urn:tms:error:ops-005", body.get("type").asText());
        assertEquals("OPS-005", body.get("code").asText());
        assertEquals(404, body.get("status").asInt());
        // ni la clave del componente ignorado ni ninguna otra de mas: el cuerpo sin extensiones
        // tiene exactamente los seis campos no nulos que ya servia antes
        assertFalse(body.has("extensions"), "el componente no debe viajar como propiedad");
        assertFalse(body.has("extensionMembers"), "el accesor del aplanado tampoco es una propiedad");
        assertFalse(body.has("errors"), "un campo nulo no se emite (NON_NULL)");
        // los siete no nulos de siempre: type, title, status, detail, instance, code, traceId
        assertEquals(7, body.size(), "campos servidos: " + body);
    }

    @Test
    void problemWithExtensions_flattensThemIntoTheRoot() throws Exception {
        Map<String, Object> extensions = new LinkedHashMap<>();
        extensions.put("forcible", true);
        extensions.put("conflicts", List.of(Map.of("resource", "DRIVER")));

        Problem problem = Problem.withExtensions(409, "Resource conflict", "Ocupado",
            "OPS-002", "/api/v1/services/9/assignment", extensions);

        JsonNode body = objectMapper.readTree(objectMapper.writeValueAsString(problem));

        // aplanados en la RAIZ, al lado de `code`, no dentro de un objeto propio
        assertTrue(body.get("forcible").asBoolean());
        assertEquals("DRIVER", body.get("conflicts").get(0).get("resource").asText());
        assertFalse(body.has("extensions"), "las extensiones no viajan anidadas");
        assertEquals("OPS-002", body.get("code").asText());
    }

    /**
     * El mapa nulo tiene que quedar vacio y no explotar: {@code @JsonAnyGetter} sobre null
     * revienta el serializador, y las dos factories que no llevan extensiones pasan null.
     */
    @Test
    void problemWithNullExtensions_becomesAnEmptyMap() {
        assertEquals(Map.of(),
            Problem.of(400, "t", "d", "COM-001", "/x").extensions());
        assertEquals(Map.of(),
            Problem.withErrors(400, "t", "d", "COM-001", "/x", List.of()).extensions());
    }

    /** El cuerpo ya armado no se puede mutar por la referencia con la que se construyo. */
    @Test
    void extensions_areCopiedSoTheCallerCannotMutateTheBody() {
        Map<String, Object> mutable = new LinkedHashMap<>();
        mutable.put("forcible", true);

        Problem problem = Problem.withExtensions(409, "t", "d", "OPS-002", "/x", mutable);
        mutable.put("colado", "despues");

        assertEquals(Map.of("forcible", true), problem.extensions());
    }
}
