package com.scaramutti.tms.operations.dto.embedded;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Vista del conductor asignado para embeber en las respuestas del servicio de transporte. El id
 * es el de {@code public.drivers}, que es el que guarda la asignacion; el nombre sale del
 * trabajador asociado, porque la fila del conductor solo tiene su licencia y su estado.
 *
 * <p>Es un record propio y no se reusa el del catalogo de conductores ({@code DriverRef}) por lo
 * mismo que el resto de los resumenes embebidos de este modulo viven aca: el detalle del viaje
 * decide que muestra de cada entidad, y atarlo al DTO de otro paquete haria que agregarle un
 * campo al catalogo lo agregue tambien, sin que nadie lo pida, a todas las respuestas del viaje.
 *
 * <p>Los datos son LIVE, no snapshot: si el trabajador cambia de apellido, el viaje ya asignado
 * muestra el nuevo. Mismo criterio que {@link ServiceClientSummary}.
 */
public record ServiceDriverSummary(

    @Schema(description = "ID interno del conductor", example = "4")
    Integer id,

    @Schema(description = "Nombre completo, del trabajador asociado", example = "Juan Pérez Huamán")
    String fullName
) {}
