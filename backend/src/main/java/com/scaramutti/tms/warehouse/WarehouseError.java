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
        "Ya existe un proveedor con el RUC indicado"),
    PRODUCT_NOT_FOUND               ("WH-003", 404, "Not Found",
        "El producto indicado no existe"),
    PRODUCT_CATEGORY_NOT_FOUND      ("WH-004", 400, "Bad Request",
        "La categoría indicada no existe o está inactiva"),
    PRODUCT_UNIT_NOT_FOUND          ("WH-004", 400, "Bad Request",
        "La unidad de medida indicada no existe o está inactiva"),
    PRODUCT_IDENTITY_DUPLICATED     ("WH-010", 409, "Conflict",
        "Ya existe un producto con el mismo nombre, marca y número de parte"),
    PRODUCT_NOT_FOUND_OR_INACTIVE   ("WH-004", 400, "Bad Request",
        "El producto indicado no existe o está inactivo"),
    OPENING_BALANCE_DUPLICATED      ("WH-009", 409, "Conflict",
        "El producto ya tiene una apertura de inventario registrada"),
    OPENING_BALANCE_HAS_MOVEMENTS   ("WH-011", 409, "Conflict",
        "El producto ya tiene movimientos registrados; la apertura debe ser el primer movimiento"),
    SUPPLIER_NOT_FOUND_OR_INACTIVE  ("WH-004", 400, "Bad Request",
        "El proveedor indicado no existe o está inactivo"),
    CURRENCY_NOT_FOUND_OR_INACTIVE  ("WH-004", 400, "Bad Request",
        "La moneda indicada no existe o está inactiva"),
    PURCHASE_INVOICE_DUPLICATED_ACTIVE("WH-002", 409, "Conflict",
        "Ya existe una factura activa con ese número para el proveedor indicado"),
    PURCHASE_INVOICE_NOT_FOUND      ("WH-003", 404, "Not Found",
        "La factura indicada no existe"),
    PURCHASE_INVOICE_EDIT_NEGATIVE_STOCK("WH-006", 409, "Conflict",
        "La edición dejaría el stock del producto en negativo (ya se retiró más de lo que quedaría)"),
    PURCHASE_INVOICE_CANCEL_NEGATIVE_STOCK("WH-007", 409, "Conflict",
        "La anulación dejaría el stock del producto en negativo al descontar esta factura"),
    PURCHASE_INVOICE_ALREADY_CANCELLED("WH-008", 409, "Conflict",
        "La factura está anulada: no se puede editar ni volver a anular"),
    WITHDRAWAL_INSUFFICIENT_STOCK   ("WH-001", 409, "Conflict",
        "Stock insuficiente para el retiro"),
    WITHDRAWAL_MULTIPLE_FLEET_UNITS ("WH-005", 400, "Bad Request",
        "La unidad destino admite a lo sumo una: tracto, carreta o escolta"),
    FLEET_UNIT_NOT_FOUND_OR_INACTIVE("WH-004", 400, "Bad Request",
        "La unidad de flota indicada no existe o está inactiva"),
    WORKER_NOT_FOUND_OR_INACTIVE    ("WH-004", 400, "Bad Request",
        "El trabajador indicado no existe o está inactivo");

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
