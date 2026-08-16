package com.scaramutti.tms.operations.dto;

import com.scaramutti.tms.operations.dto.embedded.ServiceDriverSummary;
import com.scaramutti.tms.operations.dto.embedded.ServiceUserSummary;
import com.scaramutti.tms.sharedcatalogs.fleetunit.dto.FleetUnitRef;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.time.OffsetDateTime;

/**
 * Recurso de REFUERZO de un viaje que ya estaba en ruta, con el motivo por el que se sumo y quien
 * lo sumo.
 *
 * <p>Una fila es un PEDIDO, no un recurso: puede traer conductor, tracto y carreta a la vez, o uno
 * solo. Los que no participaron viajan en null, y por eso los tres son opcionales pese a que el
 * pedido que la creo exigio al menos uno.
 */
public record ServiceAdditionalResourceResponse(

    Long id,

    @Schema(nullable = true, description = "Conductor sumado; null si este refuerzo no incluyó ninguno")
    ServiceDriverSummary driver,

    @Schema(nullable = true, description = "Tracto sumado; null si este refuerzo no incluyó ninguno")
    FleetUnitRef tractor,

    @Schema(nullable = true, description = "Carreta sumada; null si este refuerzo no incluyó ninguna")
    FleetUnitRef trailer,

    @Schema(description = "Por qué se sumó. Obligatorio al crearlo, así que nunca viaja vacío")
    String reason,

    ServiceUserSummary assignedBy,

    OffsetDateTime assignedAt
) {}
