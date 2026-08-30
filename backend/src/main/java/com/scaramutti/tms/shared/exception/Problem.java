package com.scaramutti.tms.shared.exception;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * RFC 7807 Problem Details.
 * Estructura unificada para todos los errores del API.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Problem(
    String type,
    String title,
    Integer status,
    String detail,
    String instance,
    String code,
    String traceId,
    List<FieldError> errors,

    /**
     * Miembros de EXTENSION del error (RFC 7807 §3.2): datos que solo tienen sentido para un
     * codigo concreto y que no merecen un campo fijo en la estructura comun. Hoy los usa el
     * conflicto de recursos de operaciones, que agrega si el conflicto se puede forzar y cual
     * es.
     *
     * <p>Se ignora como propiedad y se serializa APLANADO en la raiz del cuerpo (ver
     * {@link #extensionMembers()}), que es como el RFC los define: un cliente que no los conoce
     * ve el mismo Problem de siempre, y el que si los conoce los lee al lado de {@code code}
     * sin abrir un nivel mas.
     */
    @JsonIgnore Map<String, Object> extensions
) {

    /** Prefijo URN para identificadores de tipo de error (RFC 3986). */
    private static final String TYPE_PREFIX = "urn:tms:error:";

    public record FieldError(String field, String message, String code) {}

    /**
     * Nunca deja el mapa en null: {@code @JsonAnyGetter} sobre un mapa nulo revienta el
     * serializador, y ese camino lo recorren TODOS los errores de la aplicacion, no solo los que
     * traen extensiones. La copia ademas lo vuelve inmutable, que es lo que se espera de un
     * cuerpo de error ya armado.
     */
    public Problem {
        extensions = extensions == null ? Map.of() : Map.copyOf(extensions);
        requireNoReservedKeys(extensions);
    }

    /** Los campos fijos del cuerpo, que una extension NO puede pisar. */
    private static final java.util.Set<String> RESERVED_MEMBERS =
        java.util.Set.of("type", "title", "status", "detail", "instance", "code", "traceId", "errors");

    /**
     * Una extension que se llame como un campo fijo produce un JSON con la clave DUPLICADA:
     * Jackson escribe las dos y quien lea se queda con una cualquiera, que puede ser la del
     * atacante. Hoy no es alcanzable —las dos unicas claves en uso son constantes del servidor—,
     * pero esto es API compartida por todos los modulos y el proximo que la use no va a leer este
     * archivo. Se cierra el MECANISMO, no el caso.
     */
    private static void requireNoReservedKeys(Map<String, Object> extensions) {
        for (String key : extensions.keySet()) {
            if (RESERVED_MEMBERS.contains(key)) {
                throw new IllegalArgumentException(
                    "un miembro de extension no puede llamarse como un campo fijo del cuerpo: " + key);
            }
        }
    }

    /**
     * Vuelca los miembros de extension en la raiz del JSON. Un mapa vacio no emite nada, asi que
     * el cuerpo de un error sin extensiones queda byte por byte como antes de que existieran.
     */
    @JsonAnyGetter
    Map<String, Object> extensionMembers() {
        return extensions;
    }

    public static Problem of(int status, String title, String detail, String code, String instance) {
        return new Problem(typeFromCode(code), title, status, detail, instance, code, newTraceId(), null, null);
    }

    public static Problem withErrors(int status, String title, String detail, String code, String instance, List<FieldError> errors) {
        return new Problem(typeFromCode(code), title, status, detail, instance, code, newTraceId(), errors, null);
    }

    /**
     * Problem con miembros de extension. Se mantiene aparte de {@link #of} para que agregarlos
     * sea una decision explicita de quien lanza el error: el catalogo de codigos declara cuales
     * los llevan, y un tercero que reuse ese codigo sin ellos serviria un cuerpo incompleto.
     */
    public static Problem withExtensions(int status, String title, String detail, String code,
            String instance, Map<String, Object> extensions) {
        return new Problem(typeFromCode(code), title, status, detail, instance, code, newTraceId(), null, extensions);
    }

    private static String typeFromCode(String code) {
        return TYPE_PREFIX + code.toLowerCase().replace('_', '-');
    }

    private static String newTraceId() {
        return java.util.UUID.randomUUID().toString();
    }
}
