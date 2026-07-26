package com.scaramutti.tms.sharedcatalogs.worker.service.cmd;

/**
 * Filtros del listado de trabajadores (GET /workers), agrupados desde la capa REST.
 * {@code q} ya viene trimmed/normalizado ("" -&gt; null) por el ResourceMapper; nulo = sin
 * filtro. {@code isActive} nulo = activos e inactivos.
 */
public record ListWorkersQuery(
    String q,
    Boolean isActive
) {}
