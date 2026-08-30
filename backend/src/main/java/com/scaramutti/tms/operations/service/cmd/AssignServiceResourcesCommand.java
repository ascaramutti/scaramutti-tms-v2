package com.scaramutti.tms.operations.service.cmd;

/**
 * Asignacion de los recursos principales de un viaje, ya normalizada desde el request.
 *
 * <p>{@code trailerId} en null significa "sin carreta", que es un caso legitimo y no un dato que
 * falte. {@code force} llega ya resuelto a un booleano: el cuerpo lo admite ausente y eso
 * equivale a no forzar, pero de aca para abajo la ausencia no existe.
 */
public record AssignServiceResourcesCommand(
    long serviceId,
    Integer driverId,
    Integer tractorId,
    Integer trailerId,
    String note,
    boolean force
) {}
