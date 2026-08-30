package com.scaramutti.tms.operations.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.scaramutti.tms.operations.dto.embedded.ServiceClientSummary;
import com.scaramutti.tms.operations.dto.embedded.ServiceDriverSummary;
import com.scaramutti.tms.operations.model.ServiceStatus;
import com.scaramutti.tms.operations.model.TripScope;
import com.scaramutti.tms.sharedcatalogs.fleetunit.dto.FleetUnitRef;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * Fila del listado de servicios. Origen y destino viajan separados porque la tabla los muestra
 * en dos lineas.
 *
 * <p>{@code price} y {@code currencyCode} son los UNICOS campos que pueden faltar: al rol de
 * despacho el servidor no le muestra precios, y la anotacion de inclusion —puesta en esos dos
 * campos y NO en el record entero, para que un campo futuro que quede en null falle a la vista
 * en vez de desaparecer en silencio— hace que viajen AUSENTES del JSON, que es lo que declara
 * el contrato. Los demas campos siempre tienen valor.
 *
 * <p>{@code driver} y {@code tractor} SI viajan en null cuando el viaje todavia no los tiene, y
 * eso es informacion, no un dato faltante: la tabla los muestra vacios y ese es justo el estado
 * en el que el despacho busca los viajes. La carreta no esta aca: es opcional y la tabla no la
 * muestra, asi que vive solo en el detalle.
 */
public record ServiceSummaryResponse(

    Long id,

    @Schema(description = "Codigo visible del viaje", example = "SRV-0042")
    String code,

    ServiceClientSummary client,

    String origin,

    String destination,

    LocalDate tentativeDate,

    TripScope tripScope,

    ServiceStatus status,

    @Schema(nullable = true, description = "Conductor asignado; null mientras el viaje esté pendiente de asignación")
    ServiceDriverSummary driver,

    @Schema(nullable = true, description = "Tracto asignado; null mientras el viaje esté pendiente de asignación")
    FleetUnitRef tractor,

    @Schema(description = "Ausente para el rol de despacho")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    BigDecimal price,

    @Schema(description = "Ausente para el rol de despacho", example = "PEN")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    String currencyCode,

    OffsetDateTime createdAt
) {}
