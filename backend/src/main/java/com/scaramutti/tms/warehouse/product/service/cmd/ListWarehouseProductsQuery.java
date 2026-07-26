package com.scaramutti.tms.warehouse.product.service.cmd;

/**
 * Query interna del listado de productos (GET /warehouse/products). Todos los
 * filtros son opcionales:
 *  - q: null = sin filtro; si viene, ya llega trimmed (NO uppercased — el name
 *    de producto se guarda tal cual y la búsqueda es case-insensitive via ILIKE).
 *  - categoryId: null = todas las categorías.
 *  - isActive: null = activos e inactivos.
 *  - lowOnly: true = solo productos con stock &lt; minStock (RN-WH11, leído de la VIEW).
 */
public record ListWarehouseProductsQuery(
    String q, Integer categoryId, Boolean isActive, boolean lowOnly, int page, int size
) {}
