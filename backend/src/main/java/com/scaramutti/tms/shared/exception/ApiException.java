package com.scaramutti.tms.shared.exception;

import java.util.Map;

/**
 * Excepcion base para errores conocidos de negocio.
 * El handler la convierte en Problem (RFC 7807) con el status apropiado.
 */
public class ApiException extends RuntimeException {

    private final int status;
    private final String code;
    private final String title;
    private final transient Map<String, Object> extensions;

    public ApiException(int status, String code, String title, String detail) {
        this(status, code, title, detail, Map.of());
    }

    /**
     * Variante con miembros de extension del Problem (RFC 7807 §3.2), para los codigos que
     * llevan datos propios en el cuerpo del error.
     */
    public ApiException(int status, String code, String title, String detail,
            Map<String, Object> extensions) {
        super(detail);
        this.status = status;
        this.code = code;
        this.title = title;
        this.extensions = extensions == null ? Map.of() : Map.copyOf(extensions);
    }

    public int status()    { return status; }
    public String code()   { return code; }
    public String title()  { return title; }

    /** Nunca null: sin extensiones devuelve el mapa vacio. */
    public Map<String, Object> extensions() { return extensions; }
}
