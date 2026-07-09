package com.scaramutti.tms.warehouse.productcategory.service.cmd;

/**
 * Query interna del service. isActive nullable: null = sin filtro (lista todas).
 */
public record ListWarehouseProductCategoriesQuery(Boolean isActive) {}
