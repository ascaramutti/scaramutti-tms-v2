package com.scaramutti.tms.operations.service.cmd;

import com.scaramutti.tms.operations.model.ServiceStatusTransition;

import java.time.OffsetDateTime;

/**
 * Transicion de estado ya normalizada desde el request.
 *
 * <p>{@code transition} llega resuelta: de aca para abajo el destino no es texto, con lo cual
 * ninguna capa de negocio tiene que volver a preguntarse si el valor era valido. {@code dateTime}
 * en null sigue significando "ahora" y lo resuelve el service, que es quien sabe si la transicion
 * fecha algo. {@code note} viene recortada y ya medida contra el minimo que corresponda.
 */
public record ChangeServiceStatusCommand(
    long serviceId,
    String ifMatch,
    ServiceStatusTransition transition,
    OffsetDateTime dateTime,
    String note,
    boolean force
) {}
