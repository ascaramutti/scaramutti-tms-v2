package com.scaramutti.tms.clients.service.cmd;

/**
 * Query interna del service. Encapsula filtros + paginacion para listClients.
 *
 *  - q:        texto libre ya trim + uppercased por el ResourceMapper
 *              (null = sin filtro fuzzy). Se uppercasea en el mapper porque
 *              name/ruc se almacenan en mayusculas y el operador pg_trgm `%`
 *              es case-sensitive.
 *  - isActive: null = sin filtro; true/false = filtra.
 *  - page:     base 0, validado @Min(0) en el Resource.
 *  - size:     1..100, validado @Min(1)/@Max(100) en el Resource.
 *
 * El service no transforma el Query — solo lo orquesta entre repo y mapper.
 */
public record ListClientsQuery(String q, Boolean isActive, int page, int size) {}
