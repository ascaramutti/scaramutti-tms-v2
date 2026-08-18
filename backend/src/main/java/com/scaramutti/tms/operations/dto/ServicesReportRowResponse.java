package com.scaramutti.tms.operations.dto;

import com.scaramutti.tms.operations.dto.embedded.ServiceAdditionalDriverSummary;
import com.scaramutti.tms.operations.model.TripScope;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Una fila del reporte de facturacion: un viaje completado dentro de la semana pedida.
 *
 * <p>A diferencia del listado, aca {@code price} y {@code currencyCode} SIEMPRE viajan: al rol de
 * despacho no se le omiten los importes, se le niega el endpoint entero (RN-OP8), asi que quien
 * recibe una fila es porque puede ver lo que se cobro.
 */
@Schema(description = "Viaje completado dentro de la semana, con lo que se cobró por él")
public record ServicesReportRowResponse(

    long serviceId,

    String code,

    String clientName,

    TripScope tripScope,

    String origin,

    String destination,

    @Schema(description = "Fecha real de inicio del viaje")
    OffsetDateTime startDateTime,

    @Schema(description = "Fecha real de fin: es la que decide en qué semana cae el viaje")
    OffsetDateTime endDateTime,

    BigDecimal price,

    @Schema(description = "Código de la moneda en que se cobró. No se convierte: los totales van por moneda")
    String currencyCode,

    @Schema(description = "Conductor principal del viaje")
    String principalDriver,

    @Schema(description = "Conductores sumados en ruta, con el motivo de cada relevo. Lista vacía si no hubo, nunca null")
    List<ServiceAdditionalDriverSummary> additionalDrivers
) {
}
