package com.scaramutti.tms.operations.service.cmd;

import com.scaramutti.tms.operations.model.ServiceStatus;

import java.time.LocalDate;

/**
 * Filtros del listado de servicios, ya normalizados desde los query params. Todos son
 * opcionales y se combinan entre si; las fechas acotan la fecha TENTATIVA, con ambos extremos
 * incluidos.
 */
public record ListServicesQuery(
    String q,
    ServiceStatus status,
    Integer clientId,
    LocalDate dateFrom,
    LocalDate dateTo,
    int page,
    int size
) {}
