package com.scaramutti.tms.auth.model;

/**
 * Tipos de token emitidos por el sistema. El valor del claim `typ` en el JWT
 * sigue el formato `claimValue()` y permite distinguir access vs refresh.
 */
public enum TokenType {

    ACCESS("access"),
    REFRESH("refresh");

    private final String claimValue;

    TokenType(String claimValue) {
        this.claimValue = claimValue;
    }

    public String claimValue() {
        return claimValue;
    }
}
