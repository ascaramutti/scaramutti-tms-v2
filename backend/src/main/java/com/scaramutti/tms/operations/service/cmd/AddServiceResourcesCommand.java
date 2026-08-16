package com.scaramutti.tms.operations.service.cmd;

/**
 * Refuerzos que se le suman a un viaje ya en ruta, ya normalizados desde el request.
 *
 * <p>Los tres ids son opcionales por separado, pero de aca para abajo hay al menos uno: el "al
 * menos un recurso" ya lo verifico el mapper, asi que el service no vuelve a preguntarlo.
 *
 * <p>{@code reason} llega ya RECORTADO y medido contra su minimo, y {@code force} ya resuelto a un
 * booleano: el cuerpo lo admite ausente y eso equivale a no forzar, pero de aca para abajo la
 * ausencia no existe.
 */
public record AddServiceResourcesCommand(
    long serviceId,
    Integer driverId,
    Integer tractorId,
    Integer trailerId,
    String reason,
    boolean force
) {}
