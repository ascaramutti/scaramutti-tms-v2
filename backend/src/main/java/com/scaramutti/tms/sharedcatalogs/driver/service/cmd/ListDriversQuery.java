package com.scaramutti.tms.sharedcatalogs.driver.service.cmd;

/**
 * Filtro del listado de conductores (GET /drivers), agrupado desde la capa REST.
 * {@code isActive} nulo = activos e inactivos.
 */
public record ListDriversQuery(
    Boolean isActive
) {}
