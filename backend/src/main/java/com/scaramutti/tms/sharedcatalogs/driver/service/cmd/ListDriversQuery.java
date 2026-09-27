package com.scaramutti.tms.sharedcatalogs.driver.service.cmd;

/**
 * Filtro del listado de conductores (GET /drivers), agrupado desde la capa REST.
 * Cada filtro nulo no filtra: {@code isActive} mira la ficha y {@code isAssignable}, si se puede
 * asignar hoy.
 */
public record ListDriversQuery(
    Boolean isActive,
    Boolean isAssignable
) {}
