package com.scaramutti.tms.operations.dto;

import com.scaramutti.tms.operations.dto.embedded.ServiceUserSummary;
import com.scaramutti.tms.operations.model.ServiceEventType;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.time.OffsetDateTime;

/**
 * Linea de la bitacora del viaje. El tipo viaja aparte del texto para que la interfaz pinte
 * cada entrada con su etiqueta sin tener que interpretar lo escrito.
 */
public record ServiceEventResponse(

    Long id,

    ServiceEventType eventType,

    @Schema(description = "Texto de la entrada", example = "Servicio registrado")
    String note,

    ServiceUserSummary createdBy,

    OffsetDateTime createdAt
) {}
