package com.scaramutti.tms.operations.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import com.scaramutti.tms.operations.dto.embedded.ServiceCargoTypeSummary;
import com.scaramutti.tms.operations.dto.embedded.ServiceClientSummary;
import com.scaramutti.tms.operations.dto.embedded.ServiceDriverSummary;
import com.scaramutti.tms.operations.dto.embedded.ServiceUserSummary;
import com.scaramutti.tms.operations.model.ServiceStatus;
import com.scaramutti.tms.operations.model.TripScope;
import com.scaramutti.tms.sharedcatalogs.fleetunit.dto.FleetUnitRef;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Detalle del servicio de transporte con su bitacora. Origen y destino viajan separados porque
 * la tabla del listado los muestra en dos lineas.
 *
 * <p>Los recursos asignados (conductor, tracto, carreta) viajan desde que existe el endpoint que
 * los asigna: son null mientras el viaje este pendiente de asignacion, y la carreta puede seguir
 * en null despues, porque es opcional. Los REFUERZOS viajan aparte, en su propia lista: no
 * reemplazan a los principales, se suman.
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

    @Schema(nullable = true, description = "Conductor asignado; null mientras el viaje esté pendiente de asignación")
    ServiceDriverSummary driver,

    @Schema(nullable = true, description = "Tracto asignado; null mientras el viaje esté pendiente de asignación")
    FleetUnitRef tractor,

    @Schema(nullable = true, description = "Carreta asignada; puede seguir en null después de asignar, porque es opcional")
    FleetUnitRef trailer,

    /*
     * Las fechas reales viajan aca porque la EDICION las corrige: sin poder leerlas, el formulario
     * no puede mostrarlas ni precargarlas, y la precondicion del PUT ("solo se corrige la que ya
     * existe") seria inobservable para quien llama. Las FIJA la transicion de estado; este detalle
     * solo las muestra.
     */
    @Schema(nullable = true, description = "Inicio real del viaje; lo fija la transicion a \"en ruta\" y la edicion lo corrige; null mientras el viaje no haya arrancado")
    OffsetDateTime startDateTime,

    @Schema(nullable = true, description = "Fin real del viaje; lo fija la transicion a \"completado\" y la edicion lo corrige; null mientras el viaje no haya cerrado")
    OffsetDateTime endDateTime,

    @Schema(description = "Refuerzos sumados con el viaje en ruta, en orden cronologico ascendente. Lista vacia si no hay, nunca null")
    List<ServiceAdditionalResourceResponse> additionalResources,

    @Schema(description = "Bitacora en orden cronologico ascendente")
    List<ServiceEventResponse> events,

    ServiceUserSummary createdBy,

    OffsetDateTime createdAt,

    @Schema(description = "Ultima actualizacion. Para el If-Match reenviar el header ETag tal cual, no este valor")
    OffsetDateTime updatedAt
) {}
