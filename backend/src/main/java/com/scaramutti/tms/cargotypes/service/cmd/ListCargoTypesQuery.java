package com.scaramutti.tms.cargotypes.service.cmd;

/**
 * Query interna del service. Encapsula filtros + paginacion para listCargoTypes.
 *
 *  - q:        texto libre ya trim + uppercased por el ResourceMapper
 *              (null = sin filtro fuzzy). Bean Validation garantiza minLength=3
 *              y maxLength=200 antes de llegar al mapper. Se uppercasea en
 *              el mapper porque la funcion similarity() del ORDER BY ranking
 *              es case-sensitive (aunque ILIKE no lo es; el upper homogeneiza
 *              la comparacion contra los datos almacenados en mayusculas).
 *  - isActive: null = sin filtro; true/false = filtra.
 *  - page:     base 0, validado @Min(0) en el Resource.
 *  - size:     1..100, validado @Min(1)/@Max(100) en el Resource.
 *
 * El service no transforma el Query — solo lo orquesta entre repo y mapper.
 */
public record ListCargoTypesQuery(String q, Boolean isActive, int page, int size) {}
