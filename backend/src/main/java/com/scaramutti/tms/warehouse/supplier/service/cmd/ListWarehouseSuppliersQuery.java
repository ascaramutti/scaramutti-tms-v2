package com.scaramutti.tms.warehouse.supplier.service.cmd;

/**
 * Query interna del service. q nullable: null = sin filtro. isActive
 * nullable: null = sin filtro (lista todos).
 */
public record ListWarehouseSuppliersQuery(String q, Boolean isActive, int page, int size) {}
