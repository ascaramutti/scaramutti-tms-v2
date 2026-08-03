package com.scaramutti.tms.operations.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import com.scaramutti.tms.operations.dto.embedded.ServiceCargoTypeSummary;
import com.scaramutti.tms.operations.dto.embedded.ServiceClientSummary;
import com.scaramutti.tms.operations.dto.embedded.ServiceUserSummary;
import com.scaramutti.tms.operations.model.ServiceStatus;
import com.scaramutti.tms.operations.model.TripScope;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Detalle del servicio de transporte con su bitacora. Origen y destino viajan separados porque
 * la tabla del listado los muestra en dos lineas.
 *
 * <p>Los recursos asignados (conductor, tracto, carreta), las fechas reales de inicio y fin y
 * los refuerzos NO estan en esta respuesta todavia: llegan con los endpoints que los producen
 * (asignacion, transiciones de estado y recursos adicionales). Un servicio recien creado no
 * tiene ninguno.
 */
public record ServiceDetailResponse(

    Long id,

    @Schema(description = "Codigo visible del viaje", example = "SRV-0042")
    String code,

    ServiceClientSummary client,

    String origin,

    String destination,

    LocalDate tentativeDate,

    TripScope tripScope,

    ServiceCargoTypeSummary cargoType,

    @Schema(description = "Peso de la carga en kilogramos")
    BigDecimal weightKg,

    @Schema(nullable = true) BigDecimal lengthM,

    @Schema(nullable = true) BigDecimal widthM,

    @Schema(nullable = true) BigDecimal heightM,

    @Schema(nullable = true) String observations,

    @Schema(description = "Ausente para el rol de despacho")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    BigDecimal price,

    @Schema(description = "Ausente para el rol de despacho", example = "PEN")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    String currencyCode,

    ServiceStatus status,

    @Schema(description = "Bitacora en orden cronologico ascendente")
    List<ServiceEventResponse> events,

    ServiceUserSummary createdBy,

    OffsetDateTime createdAt,

    @Schema(description = "Ultima actualizacion. Para el If-Match reenviar el header ETag tal cual, no este valor")
    OffsetDateTime updatedAt
) {}
