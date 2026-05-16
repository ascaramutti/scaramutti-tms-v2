package com.scaramutti.tms.catalogs.quotationservicetype.service.cmd;

/**
 * Query interna del service. Desacopla la capa REST del dominio Catalogs.
 *
 * `isActive` es nullable: null = sin filtro (lista todas); true/false = filtra.
 * No usamos Optional como tipo de campo (anti-pattern).
 */
public record ListQuotationServiceTypesQuery(Boolean isActive) {}
