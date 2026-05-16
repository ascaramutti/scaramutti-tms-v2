package com.scaramutti.tms.catalogs;

import com.scaramutti.tms.shared.exception.ApiError;

/**
 * Catalogo de errores del modulo Catalogs con codigos trazables (CAT-XXX).
 * Centraliza status/code/title/detail para evitar magic strings en services,
 * mappers y validators.
 *
 * Aplica a currencies, payment-terms y quotation-service-types.
 */
public enum CatalogsError implements ApiError {

    QUOTATION_SERVICE_TYPE_CODE_REQUIRED       ("CAT-001", 400, "Service type code required",
        "El code del tipo de servicio no puede ser null ni vacío"),
    QUOTATION_SERVICE_TYPE_CODE_PREFIX_INVALID ("CAT-002", 400, "Service type code prefix invalid",
        "El code del tipo de servicio debe empezar con S, A, C o I (categoría del kind)");

    private final String code;
    private final int status;
    private final String title;
    private final String detail;

    CatalogsError(String code, int status, String title, String detail) {
        this.code = code;
        this.status = status;
        this.title = title;
        this.detail = detail;
    }

    @Override public String code()   { return code; }
    @Override public int    status() { return status; }
    @Override public String title()  { return title; }
    @Override public String detail() { return detail; }
}
