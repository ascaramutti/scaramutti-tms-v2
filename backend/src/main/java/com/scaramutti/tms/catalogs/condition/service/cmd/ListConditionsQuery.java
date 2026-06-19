package com.scaramutti.tms.catalogs.condition.service.cmd;

/**
 * Query interna del service. Desacopla la capa REST del dominio Catalogs.
 *
 * {@code isActive} es nullable: null = sin filtro (lista todas); true/false = filtra.
 * Espeja {@code ListPaymentTermsQuery}. (No usamos Optional como campo: es anti-pattern.)
 */
public record ListConditionsQuery(Boolean isActive) {}
