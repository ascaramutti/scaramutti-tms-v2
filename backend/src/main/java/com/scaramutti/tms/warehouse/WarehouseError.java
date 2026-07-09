package com.scaramutti.tms.warehouse;

import com.scaramutti.tms.shared.exception.ApiError;

/**
 * Catalogo de errores del modulo Almacen con codigos trazables (WH-XXX).
 * Los codigos se agregan a medida que cada endpoint los necesita, segun
 * almacen/10_CONTRATO_ALMACEN.md.
 */
public enum WarehouseError implements ApiError {

    PRODUCT_CATEGORY_NAME_DUPLICATED("WH-010", 409, "Conflict",
        "Ya existe una categoría con el nombre indicado"),
    SUPPLIER_NAME_DUPLICATED        ("WH-010", 409, "Conflict",
        "Ya existe un proveedor con el nombre indicado"),
    SUPPLIER_RUC_DUPLICATED         ("WH-010", 409, "Conflict",
        "Ya existe un proveedor con el RUC indicado");

    private final String code;
    private final int status;
    private final String title;
    private final String detail;

    WarehouseError(String code, int status, String title, String detail) {
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
