package com.scaramutti.tms.catalogs.paymentterm.service.cmd;

/**
 * Query interna del service. Desacopla la capa REST del dominio Catalogs.
 *
 * `isActive` es nullable: null = sin filtro (lista todas); true/false = filtra.
 * No usamos Optional como tipo de campo (anti-pattern: Optional fue diseñado
 * como tipo de retorno, no como contenedor persistente).
 */
public record ListPaymentTermsQuery(Boolean isActive) {}
