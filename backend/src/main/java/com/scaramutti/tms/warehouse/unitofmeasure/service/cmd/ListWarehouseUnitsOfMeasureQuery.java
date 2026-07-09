package com.scaramutti.tms.warehouse.unitofmeasure.service.cmd;

/**
 * Query interna del service. isActive nullable: null = sin filtro (lista todas).
 */
public record ListWarehouseUnitsOfMeasureQuery(Boolean isActive) {}
