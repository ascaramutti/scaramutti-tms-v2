package com.scaramutti.tms.shared.exception;

/**
 * Excepcion base para errores conocidos de negocio.
 * El handler la convierte en Problem (RFC 7807) con el status apropiado.
 */
public class ApiException extends RuntimeException {

    private final int status;
    private final String code;
    private final String title;

    public ApiException(int status, String code, String title, String detail) {
        super(detail);
        this.status = status;
        this.code = code;
        this.title = title;
    }

    public int status()    { return status; }
    public String code()   { return code; }
    public String title()  { return title; }
}
